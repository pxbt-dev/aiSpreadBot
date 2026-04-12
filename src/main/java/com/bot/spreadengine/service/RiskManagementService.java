package com.bot.spreadengine.service;

import com.bot.spreadengine.model.SpreadEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Monitors open positions every 3 seconds and enforces:
 *  - Stop-loss:        exit if absolute dollar loss exceeds MAX_LOSS_PER_POSITION
 *  - Take-profit:      exit if mark rises 30% above entry
 *  - Pre-event exit:   exit LOW-REWARD markets (<$500/game) within 10 min of game start
 *                      HIGH-REWARD markets (EPL, NBA, UFC…) are kept open for live rewards
 *  - Position cap:     refuse new entries beyond 25% of bankroll per token
 *  - Kill switch:      halt all new trades if bankroll drops 40% from start
 */
@Service
@Slf4j
public class RiskManagementService {

    /** Max absolute dollar loss per position before stop-loss fires. */
    public static final double MAX_LOSS_PER_POSITION = 0.20;
    public static final double TAKE_PROFIT_PCT       = 0.30;  // +30% from entry
    public static final double MAX_POSITION_PCT      = 0.25;  // max 25% of bankroll per token
    /** Markets earning < this $/game are not worth the event-resolution risk — exit pre-game. */
    private static final double LOW_REWARD_THRESHOLD = 500.0;
    /** Exit low-reward markets this many minutes before game start. */
    private static final long PRE_EVENT_EXIT_MINUTES = 10;

    private final PositionService positionService;
    private final PolymarketService polymarketService;
    private final SimpMessagingTemplate messagingTemplate;
    private final MarketScannerService marketScanner;
    private final PerformanceTracker performanceTracker;

    public RiskManagementService(PositionService positionService,
                                 PolymarketService polymarketService,
                                 SimpMessagingTemplate messagingTemplate,
                                 MarketScannerService marketScanner,
                                 PerformanceTracker performanceTracker) {
        this.positionService     = positionService;
        this.polymarketService   = polymarketService;
        this.messagingTemplate   = messagingTemplate;
        this.marketScanner       = marketScanner;
        this.performanceTracker  = performanceTracker;
    }

    /** Scans all open positions for stop-loss / take-profit triggers. */
    @Scheduled(fixedRate = 3000)
    public void checkPositionRisk() {
        if (positionService.getPositionMap().isEmpty()) return;

        if (isKillSwitchActive()) {
            messagingTemplate.convertAndSend("/topic/events",
                new SpreadEvent("KILL_SWITCH",
                    String.format("KILL SWITCH ACTIVE — bankroll $%.2f (started $%.2f)",
                        positionService.getBankroll(), PositionService.INITIAL_BANKROLL), 0));
            log.error("☠️ KILL SWITCH ACTIVE: bankroll ${} below threshold", positionService.getBankroll());
        }

        java.time.Instant now = java.time.Instant.now();

        for (PositionService.Position pos : positionService.getPositions()) {
            double entryPrice = pos.getEntryPrice();
            if (entryPrice <= 0) continue;

            String tokenId = pos.getTokenId();

            // ── Pre-event exit: low-reward markets only ──────────────────────
            // High-reward markets (EPL, NBA, UFC…) earn 2.5× more during live play —
            // DO NOT exit those. Only exit markets not worth the event-resolution risk.
            double rewardPerGame = marketScanner.getRewardPerGame(tokenId);
            if (rewardPerGame < LOW_REWARD_THRESHOLD) {
                java.time.Instant gameStart = marketScanner.getGameStartTime(tokenId);
                if (gameStart != null) {
                    long minutesUntilStart = java.time.Duration.between(now, gameStart).toMinutes();
                    if (minutesUntilStart >= 0 && minutesUntilStart <= PRE_EVENT_EXIT_MINUTES) {
                        log.warn("⏰ PRE-EVENT EXIT ({}$/game, {}min to start): {} x{}",
                            (int) rewardPerGame, minutesUntilStart, pos.getTicker(), pos.getSize());
                        polymarketService.getMidpoint(tokenId).subscribe(
                            mid -> exitPosition(pos, mid > 0 ? mid : entryPrice, "STOP_LOSS"),
                            err -> exitPosition(pos, entryPrice, "STOP_LOSS"));
                        continue;
                    }
                }
            }

            // ── Stop-loss (absolute dollar) + take-profit ────────────────────
            polymarketService.getMidpoint(tokenId).subscribe(mid -> {
                if (mid <= 0) return;

                boolean isBuy = pos.getSide().equalsIgnoreCase("BUY");
                // Absolute loss = (entry − mark) × size  for longs; (mark − entry) × size for shorts
                double absoluteLoss = isBuy
                    ? (entryPrice - mid) * pos.getSize()
                    : (mid - entryPrice) * pos.getSize();
                double pnlPct = isBuy ? (mid - entryPrice) / entryPrice
                                      : (entryPrice - mid)  / entryPrice;

                if (absoluteLoss >= MAX_LOSS_PER_POSITION) {
                    log.warn("🛑 STOP-LOSS: {} entry={} mark={} loss=${} (cap=${})",
                        pos.getTicker(), entryPrice, mid,
                        String.format("%.3f", absoluteLoss),
                        String.format("%.2f", MAX_LOSS_PER_POSITION));
                    exitPosition(pos, mid, "STOP_LOSS");
                } else if (pnlPct >= TAKE_PROFIT_PCT) {
                    log.info("✅ TAKE-PROFIT: {} entry={} mark={} pnl=+{:.1f}%",
                        pos.getTicker(), entryPrice, mid, pnlPct * 100);
                    exitPosition(pos, mid, "TAKE_PROFIT");
                }
            }, err -> log.debug("Risk check mid unavailable for {}: {}", tokenId, err.getMessage()));
        }
    }

    private void exitPosition(PositionService.Position pos, double mid, String reason) {
        String exitSide = pos.getSide().equalsIgnoreCase("BUY") ? "SELL" : "BUY";
        positionService.addTrade(pos.getTokenId(), pos.getTicker(), exitSide, pos.getSize(), mid, pos.getStrategy());

        boolean isBuy = pos.getSide().equalsIgnoreCase("BUY");
        double realizedPnL = isBuy
            ? (mid - pos.getEntryPrice()) * pos.getSize()
            : (pos.getEntryPrice() - mid)  * pos.getSize();
        boolean isStopLoss = reason.equals("STOP_LOSS");

        performanceTracker.record(pos.getTicker(), realizedPnL, pos.getStrategy(), isStopLoss);

        double pnlPct = pos.getEntryPrice() > 0
            ? ((mid - pos.getEntryPrice()) / pos.getEntryPrice()) * 100 : 0;
        String label = isStopLoss
            ? String.format("STOP-LOSS HIT: %s @ $%.3f (%.1f%%)", pos.getTicker(), mid, pnlPct)
            : String.format("TAKE-PROFIT HIT: %s @ $%.3f (+%.1f%%)", pos.getTicker(), mid, pnlPct);

        messagingTemplate.convertAndSend("/topic/events", new SpreadEvent(reason, label, (int)(mid * 100)));
        log.info("🚪 EXIT [{}]: {} x{} @ ${}", reason, pos.getTicker(), pos.getSize(), mid);
    }

    /**
     * Returns true if adding {@code tradeValue} to the existing position for
     * {@code tokenId} would exceed the per-token position cap.
     */
    public boolean isPositionCapReached(String tokenId, double tradeValue) {
        double maxAllowed = positionService.getBankroll() * MAX_POSITION_PCT;
        PositionService.Position existing = positionService.getPositionMap().get(tokenId);
        double currentExposure = existing != null
            ? existing.getSize() * existing.getEntryPrice() : 0.0;
        return (currentExposure + tradeValue) > maxAllowed;
    }

    public boolean isKillSwitchActive() {
        return positionService.isKillSwitchActive();
    }
}
