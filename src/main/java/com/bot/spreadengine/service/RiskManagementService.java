package com.bot.spreadengine.service;

import com.bot.spreadengine.model.SpreadEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Monitors open positions every 3 seconds and enforces:
 *  - Stop-loss:     exit if mark falls 25% below entry
 *  - Take-profit:   exit if mark rises 30% above entry
 *  - Position cap:  refuse new entries beyond 25% of bankroll per token
 *  - Kill switch:   halt all new trades if bankroll drops 40% from start
 */
@Service
@Slf4j
public class RiskManagementService {

    public static final double STOP_LOSS_PCT    = 0.25;   // -25% from entry
    public static final double TAKE_PROFIT_PCT  = 0.30;   // +30% from entry
    public static final double MAX_POSITION_PCT = 0.25;   // max 25% of bankroll per token

    private final PositionService positionService;
    private final PolymarketService polymarketService;
    private final SimpMessagingTemplate messagingTemplate;

    public RiskManagementService(PositionService positionService,
                                 PolymarketService polymarketService,
                                 SimpMessagingTemplate messagingTemplate) {
        this.positionService = positionService;
        this.polymarketService = polymarketService;
        this.messagingTemplate = messagingTemplate;
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

        for (PositionService.Position pos : positionService.getPositions()) {
            double entryPrice = pos.getEntryPrice();
            if (entryPrice <= 0) continue;

            polymarketService.getMidpoint(pos.getTokenId()).subscribe(mid -> {
                if (mid <= 0) return;

                boolean isBuy = pos.getSide().equalsIgnoreCase("BUY");
                double pnlPct = isBuy ? (mid - entryPrice) / entryPrice
                                      : (entryPrice - mid) / entryPrice;

                if (pnlPct <= -STOP_LOSS_PCT) {
                    log.warn("🛑 STOP-LOSS: {} entry={} mark={} pnl={:.1f}%",
                        pos.getTicker(), entryPrice, mid, pnlPct * 100);
                    exitPosition(pos, mid, "STOP_LOSS");
                } else if (pnlPct >= TAKE_PROFIT_PCT) {
                    log.info("✅ TAKE-PROFIT: {} entry={} mark={} pnl={:.1f}%",
                        pos.getTicker(), entryPrice, mid, pnlPct * 100);
                    exitPosition(pos, mid, "TAKE_PROFIT");
                }
            }, err -> log.debug("Risk check mid unavailable for {}: {}", pos.getTokenId(), err.getMessage()));
        }
    }

    private void exitPosition(PositionService.Position pos, double mid, String reason) {
        String exitSide = pos.getSide().equalsIgnoreCase("BUY") ? "SELL" : "BUY";
        positionService.addTrade(pos.getTokenId(), pos.getTicker(), exitSide, pos.getSize(), mid);

        double pnlPct = pos.getEntryPrice() > 0
            ? ((mid - pos.getEntryPrice()) / pos.getEntryPrice()) * 100 : 0;
        String label = reason.equals("STOP_LOSS")
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
