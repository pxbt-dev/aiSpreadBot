package com.bot.spreadengine.service;

import com.bot.spreadengine.model.SpreadEvent;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Gabagool-style pure arbitrage:
 *   1. Find binary markets whose YES + NO midpoints sum to < $0.97 (covers 2% fee + leaves profit).
 *   2. Buy BOTH legs simultaneously — the $1 payout is guaranteed regardless of outcome.
 *   3. Track each open pair; when the market resolves (mid → 0.99+) mark it settled.
 *
 * Priority: short-duration markets (ending within 4 hours) have the highest arb frequency
 * because price discovery is volatile near resolution — exactly where YES+NO diverges from $1.
 *
 * Inspired by: github.com/strongca22-cpu/gabagool
 */
@Service
@Slf4j
public class GabagoolService {

    // ─── Thresholds ────────────────────────────────────────────────────────────
    /** Per-leg price threshold — only buy a leg if it's priced below this. */
    public static final double LEG_THRESHOLD = 0.48;
    /**
     * Max combined cost to enter. Polymarket's 2% fee applies to the PAYOUT ($1),
     * so the winning leg pays out $0.98, not $1.00. Profit = 0.98 - combined.
     * At MAX_COMBINED_COST=0.965: profit = $0.98 - $0.965 = $0.015 (1.5% per contract).
     */
    public static final double MAX_COMBINED_COST = 0.965;
    /** Min profit after the 2% payout fee. Uses corrected formula: 0.98 - combined. */
    public static final double MIN_PROFIT_MARGIN = 0.01;
    /** Markets resolving within this many hours are prioritised. */
    private static final int SHORT_DURATION_HOURS = 4;
    /** Fraction of bankroll to risk per arb pair (gabagool uses small fixed sizes). */
    private static final double POSITION_SIZE_PCT = 0.04; // 4% per pair
    private static final double MAX_POSITION_USD   = 2.00; // hard cap per pair

    // ─── Dependencies ──────────────────────────────────────────────────────────
    private final PolymarketService polymarketService;
    private final PositionService positionService;
    private final RiskManagementService riskManagementService;
    private final SimpMessagingTemplate messagingTemplate;
    private final PerformanceTracker performanceTracker;

    public GabagoolService(PolymarketService polymarketService,
                           PositionService positionService,
                           RiskManagementService riskManagementService,
                           SimpMessagingTemplate messagingTemplate,
                           PerformanceTracker performanceTracker) {
        this.polymarketService   = polymarketService;
        this.positionService     = positionService;
        this.riskManagementService = riskManagementService;
        this.messagingTemplate   = messagingTemplate;
        this.performanceTracker  = performanceTracker;
    }

    // ─── State ─────────────────────────────────────────────────────────────────

    /** A matched YES+NO pair entered for guaranteed profit. */
    @Data
    public static class ArbPair {
        private final String  pairId;
        private final String  marketId;
        private final String  question;
        private final String  yesTokenId;
        private final String  noTokenId;
        private final double  yesCost;          // price paid per YES contract
        private final double  noCost;           // price paid per NO contract
        private final int     yesQty;
        private final int     noQty;
        private final double  perContractProfit; // = 1.0 - (yesCost + noCost)
        private final double  totalCost;         // (yesCost * yesQty) + (noCost * noQty)
        private final double  totalLockedProfit; // perContractProfit * min(yesQty, noQty)
        private final Instant openedAt;
        private boolean resolved     = false;
        private double  settledProfit = 0.0;
    }

    private final CopyOnWriteArrayList<ArbPair> activePairs = new CopyOnWriteArrayList<>();

    // ─── Market scanning ───────────────────────────────────────────────────────

    /**
     * Scans Gamma API for short-duration markets with YES+NO arb opportunities.
     * Runs every 5 minutes — frequent enough to catch fast-moving 15-min markets.
     */
    @Scheduled(fixedRate = 300_000)
    public void scanForArbPairs() {
        if (riskManagementService.isKillSwitchActive()) return;

        log.info("🔍 [Gabagool] Scanning for short-duration arb pairs...");

        Instant cutoff = Instant.now().plus(SHORT_DURATION_HOURS, ChronoUnit.HOURS);

        polymarketService.fetchActiveMarkets()
            .flatMapMany(Flux::fromIterable)
            .filter(market -> {
                // Only binary markets (2 tokens)
                Object tokens = market.get("tokens");
                if (!(tokens instanceof List) || ((List<?>) tokens).size() < 2) return false;

                // Prefer markets ending within SHORT_DURATION_HOURS
                Object endDateStr = market.get("end_date_iso");
                if (endDateStr != null) {
                    try {
                        Instant endDate = Instant.parse(endDateStr.toString());
                        return endDate.isBefore(cutoff);
                    } catch (Exception ignored) {}
                }
                // If no end date, include anyway (scanner will score it later)
                return true;
            })
            .flatMap(market -> evaluateForArb(market))
            .subscribe(
                pair -> enterArbPair(pair),
                err  -> log.debug("[Gabagool] Scan error: {}", err.getMessage())
            );
    }

    /**
     * Fetches YES and NO midpoints for a candidate market and checks the arb invariant.
     * Returns a Mono<ArbPair> if the opportunity is valid, or empty.
     */
    @SuppressWarnings("unchecked")
    private Mono<ArbPair> evaluateForArb(Map<String, Object> market) {
        List<Map<String, Object>> tokens =
            (List<Map<String, Object>>) market.get("tokens");

        String question   = (String) market.getOrDefault("question", "Unknown");
        String marketId   = (String) market.getOrDefault("id", "unknown");
        String yesTokenId = (String) tokens.get(0).get("token_id");
        String noTokenId  = (String) tokens.get(1).get("token_id");

        if (yesTokenId == null || noTokenId == null) return Mono.empty();

        // Skip if we already have an active (unresolved) pair for this market
        boolean alreadyOpen = activePairs.stream()
            .anyMatch(p -> p.getMarketId().equals(marketId) && !p.isResolved());
        if (alreadyOpen) return Mono.empty();

        Mono<Double> yesMidMono = polymarketService.getMidpoint(yesTokenId).onErrorReturn(0.0);
        Mono<Double> noMidMono  = polymarketService.getMidpoint(noTokenId).onErrorReturn(0.0);

        return yesMidMono.zipWith(noMidMono, (yesMid, noMid) -> {
            if (yesMid <= 0 || noMid <= 0)              return null;
            if (yesMid > LEG_THRESHOLD)                 return null; // YES leg not cheap enough
            if (noMid  > LEG_THRESHOLD)                 return null; // NO  leg not cheap enough
            double combined     = yesMid + noMid;
            // Correct fee: Polymarket deducts 2% from the $1 payout → net payout = $0.98
            double lockedProfit = 0.98 - combined;
            if (combined >= MAX_COMBINED_COST)           return null; // not enough margin
            if (lockedProfit < MIN_PROFIT_MARGIN)        return null; // too small after fees

            double bankroll = positionService.getBankroll();
            double legSize  = Math.min(bankroll * POSITION_SIZE_PCT, MAX_POSITION_USD);
            int    yesQty   = yesMid > 0 ? (int)(legSize / yesMid) : 0;
            int    noQty    = noMid  > 0 ? (int)(legSize / noMid)  : 0;
            if (yesQty <= 0 || noQty <= 0) return null;

            double totalCost         = (yesMid * yesQty) + (noMid * noQty);
            double totalLockedProfit = lockedProfit * Math.min(yesQty, noQty);

            String shortQ = question.length() > 50 ? question.substring(0, 47) + "..." : question;
            String pairId = "ARB-" + System.currentTimeMillis();
            return new ArbPair(pairId, marketId, shortQ,
                               yesTokenId, noTokenId,
                               yesMid, noMid, yesQty, noQty,
                               lockedProfit, totalCost, totalLockedProfit,
                               Instant.now());
        }).filter(Objects::nonNull);
    }

    /**
     * Enters an arb pair: buys both YES and NO legs, records the position pair.
     */
    private void enterArbPair(ArbPair pair) {
        if (riskManagementService.isKillSwitchActive()) return;
        if (positionService.getBankroll() < pair.getTotalCost()) {
            log.warn("[Gabagool] Insufficient bankroll for pair {}: need ${}", pair.getPairId(),
                String.format("%.2f", pair.getTotalCost()));
            return;
        }

        log.info("⚖️ [Gabagool] ENTERING PAIR {} — YES @ ${} x{} + NO @ ${} x{} | locked +${}",
            pair.getPairId(),
            String.format("%.3f", pair.getYesCost()), pair.getYesQty(),
            String.format("%.3f", pair.getNoCost()),  pair.getNoQty(),
            String.format("%.4f", pair.getTotalLockedProfit())); // Lombok-generated getter

        // Record both legs in position service
        positionService.addTrade(pair.getYesTokenId(), pair.getQuestion() + " [YES]",
                                 "BUY", pair.getYesQty(), pair.getYesCost(), "GABAGOOL");
        positionService.addTrade(pair.getNoTokenId(),  pair.getQuestion() + " [NO]",
                                 "BUY", pair.getNoQty(),  pair.getNoCost(),  "GABAGOOL");

        activePairs.add(pair);

        messagingTemplate.convertAndSend("/topic/events", new SpreadEvent("GABAGOOL_ENTRY",
            String.format("ARB PAIR: +$%.3f locked — %s", pair.getTotalLockedProfit(), pair.getQuestion()),
            (int)(pair.getPerContractProfit() * 100)));
        messagingTemplate.convertAndSend("/topic/arb-pairs", getActivePairsSummary());
    }

    // ─── Auto-redeemer ─────────────────────────────────────────────────────────

    /**
     * Checks all active pairs every 30 seconds.
     * A pair is "resolved" when both leg midpoints have converged to near $1 and $0
     * (one side pays out, other expires worthless — net = $1 per pair).
     * In simulation: if either leg moves to > 0.95, we mark settled and record profit.
     */
    @Scheduled(fixedRate = 30_000)
    public void checkPairResolution() {
        List<ArbPair> open = activePairs.stream().filter(p -> !p.isResolved()).toList();
        if (open.isEmpty()) return;

        for (ArbPair pair : open) {
            Mono<Double> yesMono = polymarketService.getMidpoint(pair.getYesTokenId()).onErrorReturn(-1.0);
            Mono<Double> noMono  = polymarketService.getMidpoint(pair.getNoTokenId()).onErrorReturn(-1.0);

            yesMono.zipWith(noMono, (yesMid, noMid) -> {
                if (yesMid < 0 || noMid < 0) return false; // prices unavailable, skip

                boolean yesWon = yesMid >= 0.95;
                boolean noWon  = noMid  >= 0.95;

                if (yesWon || noWon) {
                    // Market resolved — winning leg pays $1 per contract, losing leg expires at $0
                    // Our net: winning leg qty × $1 + losing leg qty × $0 - total cost
                    // Since we sized legs equally, profit ≈ lockedProfit × qty
                    int   resolvedQty   = Math.min(pair.getYesQty(), pair.getNoQty());
                    double settledProfit = pair.getPerContractProfit() * resolvedQty;

                    pair.setResolved(true);
                    pair.setSettledProfit(settledProfit);

                    String winSide  = yesWon ? "YES" : "NO";
                    String loseSide = yesWon ? "NO"  : "YES";

                    // Close the winning leg at $1 (payout)
                    String winToken  = yesWon ? pair.getYesTokenId() : pair.getNoTokenId();
                    int    winQty    = yesWon ? pair.getYesQty()     : pair.getNoQty();
                    positionService.addTrade(winToken, pair.getQuestion() + " [" + winSide + "]",
                                             "SELL", winQty, 1.00, "GABAGOOL");

                    // Close the losing leg at $0 (expired)
                    String loseToken = yesWon ? pair.getNoTokenId()  : pair.getYesTokenId();
                    int    loseQty   = yesWon ? pair.getNoQty()      : pair.getYesQty();
                    positionService.addTrade(loseToken, pair.getQuestion() + " [" + loseSide + "]",
                                             "SELL", loseQty, 0.00, "GABAGOOL");

                    performanceTracker.record(pair.getYesTokenId(), settledProfit, "GABAGOOL", false);

                    log.info("✅ [Gabagool] SETTLED {} — {} won, profit +${} (locked was +${})",
                        pair.getPairId(), winSide,
                        String.format("%.4f", settledProfit),
                        String.format("%.4f", pair.getTotalLockedProfit()));

                    messagingTemplate.convertAndSend("/topic/events", new SpreadEvent("GABAGOOL_SETTLE",
                        String.format("SETTLED +$%.3f — %s [%s won]", settledProfit, pair.getQuestion(), winSide),
                        (int)(settledProfit * 100)));
                    messagingTemplate.convertAndSend("/topic/arb-pairs", getActivePairsSummary());
                    return true;
                }

                // Check if pair has been open too long (> 6h without resolution — likely stale data)
                if (pair.getOpenedAt().isBefore(Instant.now().minus(6, ChronoUnit.HOURS))) {
                    log.warn("[Gabagool] Pair {} open for > 6h without resolution — marking stale", pair.getPairId());
                    performanceTracker.record(pair.getYesTokenId(), 0.0, "GABAGOOL", false);
                    pair.setResolved(true);
                }
                return false;
            }).subscribe();
        }
    }

    // ─── Accessors ─────────────────────────────────────────────────────────────

    public List<ArbPair> getActivePairs() {
        return new ArrayList<>(activePairs);
    }

    public List<ArbPair> getOpenPairs() {
        return activePairs.stream().filter(p -> !p.isResolved()).toList();
    }

    public double getTotalLockedProfit() {
        return activePairs.stream()
            .filter(p -> !p.isResolved())
            .mapToDouble(ArbPair::getTotalLockedProfit)
            .sum();
    }

    public double getTotalSettledProfit() {
        return activePairs.stream()
            .filter(ArbPair::isResolved)
            .mapToDouble(ArbPair::getSettledProfit)
            .sum();
    }

    /** Summary DTO sent to the frontend via /topic/arb-pairs and embedded in stats. */
    public Map<String, Object> getActivePairsSummary() {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("openCount",      getOpenPairs().size());
        summary.put("totalLocked",    String.format("%.4f", getTotalLockedProfit()));
        summary.put("totalSettled",   String.format("%.4f", getTotalSettledProfit()));
        summary.put("pairs", activePairs.stream().map(p -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("pairId",        p.getPairId());
            m.put("question",      p.getQuestion());
            m.put("yesCost",       String.format("%.3f", p.getYesCost()));
            m.put("noCost",        String.format("%.3f", p.getNoCost()));
            m.put("lockedProfit",  String.format("%.4f", p.getTotalLockedProfit()));
            m.put("resolved",      p.isResolved());
            m.put("settledProfit", String.format("%.4f", p.getSettledProfit()));
            m.put("openedAt",      p.getOpenedAt().toString());
            return m;
        }).toList());
        return summary;
    }
}
