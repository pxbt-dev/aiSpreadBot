package com.bot.spreadengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.bot.spreadengine.model.SpreadEvent;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class LiveTradingEngine {

    private final PolymarketService polymarketService;
    private final WeatherService weatherService;
    private final SpaceWeatherService spaceWeatherService;
    private final WyckoffService wyckoffService;
    private final TechnicalAnalysisService taService;
    private final WekaAnalysisService wekaService;
    private final SentimentService sentimentService;
    private final SimpMessagingTemplate messagingTemplate;
    private final HistoricalSolarService historyService;
    private final PositionService positionService;
    private final MarketScannerService marketScanner;

    public LiveTradingEngine(PolymarketService polymarketService, WeatherService weatherService, WekaAnalysisService wekaService,
                             TechnicalAnalysisService taService, WyckoffService wyckoffService,
                             SpaceWeatherService spaceWeatherService, SentimentService sentimentService,
                             SimpMessagingTemplate messagingTemplate, HistoricalSolarService historyService,
                             PositionService positionService, MarketScannerService marketScanner) {
        this.polymarketService = polymarketService;
        this.weatherService = weatherService;
        this.wekaService = wekaService;
        this.taService = taService;
        this.wyckoffService = wyckoffService;
        this.spaceWeatherService = spaceWeatherService;
        this.sentimentService = sentimentService;
        this.messagingTemplate = messagingTemplate;
        this.historyService = historyService;
        this.positionService = positionService;
        this.marketScanner = marketScanner;

        // Phase 6: Sync historical solar data for ensemble training
        this.historyService.syncRecentHistory();
        
        updateSolarData();
    }

    @Autowired
    private ClaudeAnalysisService claudeAnalysisService;

    @Autowired
    private RiskManagementService riskManagementService;

    @Autowired
    private PerformanceTracker performanceTracker;

    private double currentConfidence = 0.0;
    private String currentAuditNote = "READY";
    private double sentimentScore = 0.0;
    private double solarMultiplier = 1.0;
    private final Map<String, List<Double[]>> priceBuffers = new ConcurrentHashMap<>();
    private static final int BUFFER_SIZE = 50;

    // Token IDs are resolved dynamically by MarketScannerService at startup and refreshed hourly

    @Scheduled(fixedRate = 3600000) // Hourly Solar Update
    public void updateSolarData() {
        spaceWeatherService.fetchForecast()
            .subscribe(forecasts -> {
                this.solarMultiplier = spaceWeatherService.getVolatilityMultiplier(forecasts);
                log.info("☀️ Solar Multiplier updated: {}", solarMultiplier);
                messagingTemplate.convertAndSend("/topic/events", 
                    new SpreadEvent("SOLAR_UPDATE", "Solar Multiplier: " + solarMultiplier, (int)(solarMultiplier * 100)));
            }, error -> log.error("Error in updateSolarData: {}", error.getMessage()));
    }

    @Scheduled(fixedRate = 2000)
    public void executeMarketMaking() {
        String tokenId = marketScanner.getPrimaryTokenId();
        if (tokenId == null) return;
        polymarketService.getMidpoint(tokenId)
            .subscribe(midpoint -> {
                addToBuffer(tokenId, midpoint, 100.0);
                
                List<Double[]> history = priceBuffers.get(tokenId);
                if (history != null && history.size() >= 10) {
                    Map<String, Double> ta = taService.analyzeOrderDepth("Politics", history);
                    WyckoffService.MarketPhase phase = wyckoffService.detectPhase(history);

                    double dynamicSpread = 0.04 * solarMultiplier;
                    if (phase == WyckoffService.MarketPhase.ACCUMULATION) dynamicSpread -= 0.01;
                    if (phase == WyckoffService.MarketPhase.DISTRIBUTION) dynamicSpread += 0.01;

                    log.info("MM Strategy [{}]: Mid={}, Spread={}, SolarX={} | RSI={}", 
                        phase, midpoint, dynamicSpread, solarMultiplier, String.format("%.2f", ta.getOrDefault("RSI", 50.0)));
                    
                    if (dynamicSpread >= 0.04) {
                        messagingTemplate.convertAndSend("/topic/events", 
                            new SpreadEvent("TEST_FILL", "SIMULATED MM FILL @ " + midpoint + " [Phase: " + phase + "]", null));
                    }
                }
            }, error -> log.error("Error in executeMarketMaking: {}", error.getMessage()));
    }

    // Minimum arbSpread to enter a pure structural arb (covers 2% Polymarket fee per leg)
    private static final double PURE_ARB_THRESHOLD = 0.04;
    // Gap threshold below which we consider the arb closed and exit any open position
    private static final double GAP_CLOSE_THRESHOLD = 0.05;
    // Polymarket taker fee (2%) deducted from EV before entry decision
    private static final double POLYMARKET_FEE = 0.02;

    /**
     * Favourite-longshot bias calibration: prediction markets systematically overprice
     * high-probability outcomes and underprice longshots. Correcting for this gives a
     * more accurate effective market price before computing edge.
     */
    private double calibrateMarketPrice(double rawMid) {
        if (rawMid >= 0.85) return rawMid - 0.07;
        if (rawMid >= 0.70) return rawMid - 0.03;
        if (rawMid <= 0.10) return rawMid + 0.03;
        if (rawMid <= 0.20) return rawMid + 0.015;
        return rawMid;
    }

    /**
     * EV = P_true × (1 / p_mkt) - 1, net of fee.
     * Positive net EV is required for entry.
     */
    private double computeExpectedValue(double pTrue, double calibratedPMkt, double fee) {
        if (calibratedPMkt <= 0) return -1.0;
        return pTrue * (1.0 / calibratedPMkt) - 1.0 - fee;
    }

    /**
     * Coefficient of Variation across independent probability signals.
     * CV = σ / μ over [weka, noaa]. High CV means signals disagree — reduce or skip.
     */
    private double computeSignalCV(double weka, double noaa) {
        double mean = (weka + noaa) / 2.0;
        if (mean <= 0) return 1.0;
        double variance = ((weka - mean) * (weka - mean) + (noaa - mean) * (noaa - mean)) / 2.0;
        return Math.sqrt(variance) / mean;
    }

    /**
     * Empirical Kelly: f* = (p×b − q) / b, then scaled by (1 − CV) and halved.
     * b = (1 − p_mkt) / p_mkt (net odds). Hard-capped at 10% bankroll / $2 absolute.
     */
    private double computeEmpiricalKelly(double pTrue, double calibratedPMkt, double cv, double bankroll) {
        if (calibratedPMkt <= 0 || calibratedPMkt >= 1.0) return 0.0;
        double b = (1.0 - calibratedPMkt) / calibratedPMkt;
        double q = 1.0 - pTrue;
        double fStar = Math.max(0.0, (pTrue * b - q) / b);
        double fEmp = fStar * (1.0 - cv);   // shrink when signals disagree
        double halfKelly = fEmp * 0.5;       // half-Kelly as per formula reference
        return Math.min(bankroll * halfKelly, Math.min(bankroll * 0.10, 2.00));
    }

    @Scheduled(fixedRate = 5000)
    public void executeWeatherArb() {
        MarketScannerService.ScannedMarket market = marketScanner.getSecondaryMarket();
        if (market == null) {
            log.debug("⛅ No weather market available — scanner found none in top 200");
            return;
        }
        String tokenId = market.tokenId();
        String marketTicker = market.question().length() > 40
            ? market.question().substring(0, 37) + "..."
            : market.question();
        // Fetch full NOAA conditions (precip, tempC, humidity) alongside the market mid
        weatherService.getCurrentConditions()
            .zipWith(polymarketService.getMidpoint(tokenId).onErrorResume(e -> Mono.empty()))
            .subscribe(tuple -> {
                double[] noaa   = tuple.getT1();
                double noaaProb = noaa[0];
                double tempC    = noaa[1];
                double humidity = noaa[2];
                double marketProb = tuple.getT2();
                double gap = Math.abs(noaaProb - marketProb);

                // --- 1. Calibration: remove favourite-longshot bias from market price
                double calibratedMarketProb = calibrateMarketPrice(marketProb);

                // --- 2. Expected Value (net of Polymarket fee)
                double netEV = computeExpectedValue(noaaProb, calibratedMarketProb, POLYMARKET_FEE);

                log.info("🌡️ NOAA conditions — precip={:.2f} temp={:.1f}°C humidity={:.2f} | mkt={:.3f} calibrated={:.3f} netEV={:.3f}",
                    noaaProb, tempC, humidity, marketProb, calibratedMarketProb, netEV);

                // Gap-reversal exit: if the arb has closed, exit any open position
                PositionService.Position openPos = positionService.getPositionMap().get(tokenId);
                if (openPos != null && gap < GAP_CLOSE_THRESHOLD) {
                    log.info("📉 ARB CLOSED (gap={:.3f}) — exiting {} x{}", gap, openPos.getTicker(), openPos.getSize());
                    // Feed real outcome back into WEKA before closing the position
                    double outcome = marketProb > openPos.getEntryPrice() ? 1.0 : 0.0;
                    wekaService.recordTradeOutcome(tokenId, outcome);
                    double gapCloseRealizedPnL = (marketProb - openPos.getEntryPrice()) * openPos.getSize();
                    performanceTracker.record(tokenId, gapCloseRealizedPnL, "WEATHER_ARB", false);
                    positionService.addTrade(tokenId, openPos.getTicker(), "SELL", openPos.getSize(), marketProb, "WEATHER_ARB");
                    messagingTemplate.convertAndSend("/topic/events",
                        new SpreadEvent("TRADE", String.format("ARB CLOSED EXIT: gap=%.1f%% @ $%.3f", gap * 100, marketProb), 0));
                    return;
                }

                // Kill switch: do not enter new positions
                if (riskManagementService.isKillSwitchActive()) {
                    log.warn("☠️ Kill switch active — skipping weather arb entry");
                    return;
                }

                // AI ENSEMBLE CONSENSUS — real NOAA features instead of hardcoded placeholders
                double normalizedTemp = Math.max(0.0, Math.min(1.0, (tempC + 20.0) / 60.0));
                double[] features = new double[]{noaaProb, normalizedTemp, humidity, solarMultiplier};

                double consensus = wekaService.getConsensusScore("Weather", features);

                // --- 3. Signal CV gate: skip if WEKA and NOAA disagree too much (CV > 0.3)
                // Bypass during WEKA bootstrap (< 5 real samples) — returning 0.5 is
                // uncertainty, not disagreement; enforcing CV then blocks every trade.
                double signalCV = computeSignalCV(consensus, noaaProb);
                boolean wekaBootstrap = wekaService.getRealSampleCount("Weather") < 5;
                if (!wekaBootstrap && signalCV > 0.3) {
                    log.warn("⚠️ Signal CV={:.2f} > 0.30 — WEKA={:.2f} vs NOAA={:.2f} disagree, skipping",
                        signalCV, consensus, noaaProb);
                    return;
                }
                if (wekaBootstrap) {
                    log.debug("🌱 WEKA bootstrap mode — CV gate bypassed (CV={:.2f})", signalCV);
                }

                // Confidence (used for Claude audit gate threshold only)
                double gapBonus = Math.min(1.0, Math.max(0.0, (gap - 0.16) / 0.34));
                double finalConfidence = (consensus * 0.8) + (gapBonus * 0.2);
                currentConfidence = finalConfidence;

                boolean isSpring = wyckoffService.detectSpring(priceBuffers.getOrDefault(tokenId, new ArrayList<>()));
                log.info("Weather Arb Brain: WEKA={}, NOAA={:.2f}, CV={:.2f}, netEV={:.3f}, Confidence={}",
                    String.format("%.2f", consensus), noaaProb, signalCV, netEV, String.format("%.2f", finalConfidence));

                {   // scope block
                    double sentiment = 0.5; // kept for Claude audit call signature only

                    // Entry gate: positive net EV (replaces raw gap >= 0.16) + confidence + no false breakdown
                    if (netEV > 0.0 && !isSpring && finalConfidence > 0.6) {
                        // --- 4. Empirical Kelly sizing: f* × (1 - CV) × 0.5 half-Kelly
                        double bankroll = positionService.getBankroll();
                        double kellySize = computeEmpiricalKelly(noaaProb, calibratedMarketProb, signalCV, bankroll);
                        double tradePrice = tuple.getT2();
                        int qty = tradePrice > 0 ? (int)(kellySize / tradePrice) : 0;

                        log.info("📐 Empirical Kelly: f*×(1-CV={:.2f})×0.5 → size=${:.3f} qty={}",
                            signalCV, kellySize, qty);

                        // Position cap: don't pile into the same token beyond 25% of bankroll
                        if (qty <= 0 || riskManagementService.isPositionCapReached(tokenId, kellySize)) {
                            log.warn("⚠️ POSITION CAP reached for {} — skipping entry", tokenId);
                            messagingTemplate.convertAndSend("/topic/events",
                                new SpreadEvent("SKIPPED", "POSITION CAP: max exposure reached", 0));
                            return;
                        }

                        if (finalConfidence >= 0.85) {
                            claudeAnalysisService.auditTrade(solarMultiplier, consensus, sentiment,
                                                             "BUY", kellySize, finalConfidence, gap,
                                                             noaaProb, marketProb)
                                .subscribe(auditResult -> {
                                    boolean auditPass = (boolean) auditResult.get("auditPass");
                                    currentAuditNote = (String) auditResult.get("note");

                                    if (auditPass) {
                                        log.info("🔥 ENSEMBLE APPROVED: Confidence={}, netEV={:.3f}, Sizing: ${}",
                                            String.format("%.2f", finalConfidence), netEV, String.format("%.2f", kellySize));
                                        messagingTemplate.convertAndSend("/topic/events",
                                            new SpreadEvent("AUDIT_PASS", currentAuditNote.replace("AI AUDITED: ", ""), (int)(finalConfidence*100)));
                                        wekaService.recordEntry(tokenId, features);
                                        positionService.addTrade(tokenId, marketTicker, "BUY", qty, tradePrice, "WEATHER_ARB");
                                        messagingTemplate.convertAndSend("/topic/events",
                                            new SpreadEvent("TRADE", "EXECUTED (" + (int)(finalConfidence*100) + "% AI CONF)", (int)(gap*100)));
                                    } else {
                                        log.warn("🛑 CLAUDE VETO: {}", currentAuditNote);
                                        messagingTemplate.convertAndSend("/topic/events",
                                            new SpreadEvent("AUDIT_VETO", "CLAUDE VETO: " + currentAuditNote.replace("AI AUDITED: ", "").replace("AUDIT BLOCKED ", ""), 0));
                                    }
                                }, error -> log.error("Error in auditTrade: {}", error.getMessage()));
                        } else {
                            log.info("🔥 ENSEMBLE APPROVED: Confidence={}, netEV={:.3f}, Sizing: ${}",
                                String.format("%.2f", finalConfidence), netEV, String.format("%.2f", kellySize));
                            wekaService.recordEntry(tokenId, features);
                            positionService.addTrade(tokenId, marketTicker, "BUY", qty, tradePrice, "WEATHER_ARB");
                            messagingTemplate.convertAndSend("/topic/events",
                                new SpreadEvent("TRADE", "ENSEMBLE APPROVED (" + (int)(finalConfidence*100) + "% AI CONF)", (int)(gap*100)));
                        }
                    }
                }   // end scope block
            }, error -> log.error("Error in executeWeatherArb: {}", error.getMessage()));
    }

    /**
     * Pure structural arbitrage: if YES + NO midpoints sum to < 1 - fees, buying both legs
     * guarantees profit regardless of outcome (gabagool-style). No NOAA or WEKA required.
     * Fires every 15 seconds across all scanned markets.
     */
    @Scheduled(fixedRate = 15000)
    public void executePureArb() {
        if (riskManagementService.isKillSwitchActive()) return;

        for (MarketScannerService.ScannedMarket market : marketScanner.getActiveMarkets()) {
            // Pre-filter using cached scan prices — avoids CLOB calls for obvious non-arbs
            if (market.arbSpread() <= PURE_ARB_THRESHOLD) continue;

            String yesTokenId = market.tokenId();
            String noTokenId  = market.noTokenId();
            if (noTokenId == null || noTokenId.isEmpty()) continue;

            String ticker = market.question().length() > 35
                ? market.question().substring(0, 32) + "..."
                : market.question();

            // Fetch live CLOB prices before committing — scan prices can be up to 1hr stale
            polymarketService.getMidpoint(yesTokenId)
                .zipWith(polymarketService.getMidpoint(noTokenId).onErrorReturn(0.0))
                .subscribe((prices) -> {
                    double yesMid = prices.getT1();
                    double noMid  = prices.getT2();
                    double liveArb = noMid > 0 ? 1.0 - (yesMid + noMid) : 0.0;

                    if (liveArb <= PURE_ARB_THRESHOLD) {
                        log.info("⚖️ PURE ARB stale — live YES+NO={} (spread={}) no longer viable — {}",
                            String.format("%.3f", yesMid + noMid), String.format("%+.3f", liveArb), ticker);
                        return;
                    }

                    double bankroll = positionService.getBankroll();
                    double legSize  = Math.min(bankroll * 0.03, 1.50);

                    int yesQty = yesMid > 0 ? (int)(legSize / yesMid) : 0;
                    int noQty  = noMid  > 0 ? (int)(legSize / noMid)  : 0;

                    if (yesQty <= 0 || noQty <= 0) return;
                    if (riskManagementService.isPositionCapReached(yesTokenId, legSize)) return;

                    log.info("⚖️ PURE ARB: live YES+NO={} spread={} — {} | YES x{} @ ${}, NO x{} @ ${}",
                        String.format("%.3f", yesMid + noMid), String.format("%+.3f", liveArb),
                        ticker, yesQty, String.format("%.3f", yesMid), noQty, String.format("%.3f", noMid));

                    positionService.addTrade(yesTokenId, ticker + " YES", "BUY", yesQty, yesMid, "STRUCTURAL_ARB");
                    positionService.addTrade(noTokenId,  ticker + " NO",  "BUY", noQty,  noMid,  "STRUCTURAL_ARB");

                    int gapPts = (int)(liveArb * 100);
                    messagingTemplate.convertAndSend("/topic/events",
                        new SpreadEvent("WEATHER_ARB",
                            String.format("PURE ARB: +%dpt locked — %s", gapPts, ticker), gapPts));
                }, error -> log.error("Error fetching live prices for pure arb {}: {}", ticker, error.getMessage()));

            // Only evaluate one candidate per cycle — async, so break after subscribing
            break;
        }
    }

    @lombok.Data
    public static class AiInsightEvent {
        private int confidence;
        private double sentiment;
        private double rsi;
        private String macd;
        private String timestamp = java.time.LocalTime.now().toString();
    }

    public double getCurrentConfidence() { return currentConfidence; }
    public String getCurrentAuditNote() { return currentAuditNote; }
    public double getSentimentScore() { return sentimentScore; }
    public double getSolarMultiplier() { return solarMultiplier; }

    @Scheduled(fixedRate = 5000)
    public void broadcastAiInsights() {
        weatherService.getCurrentConditions().subscribe(noaa -> {
            double normalizedTemp = Math.max(0.0, Math.min(1.0, (noaa[1] + 20.0) / 60.0));
            double[] features = new double[]{noaa[0], normalizedTemp, noaa[2], solarMultiplier};
            runAiInsightsBroadcast(features);
        }, err -> {
            // Fall back to neutral features if NOAA is unavailable
            runAiInsightsBroadcast(new double[]{0.5, 0.5, 0.5, solarMultiplier});
        });
    }

    private void runAiInsightsBroadcast(double[] features) {
        // Confidence mirrors the arb formula: WEKA physics 80%, no sentiment for weather
        double consensus = wekaService.getConsensusScore("Weather", features);
        this.currentConfidence = consensus;

        AiInsightEvent insight = new AiInsightEvent();
        insight.setConfidence((int)(consensus * 100));
        insight.setSentiment(0.5); // neutral placeholder — sentiment not used for weather arb

        String primary = marketScanner.getPrimaryTokenId();
        List<Double[]> history = priceBuffers.getOrDefault(primary != null ? primary : "", new ArrayList<>());
        if (!history.isEmpty()) {
            Map<String, Double> ta = taService.analyzeOrderDepth("Politics", history);
            insight.setRsi(ta.getOrDefault("RSI", 50.0));
            insight.setMacd(ta.getOrDefault("MACD", 0.0) > 0 ? "BULL" : "BEAR");
        }

        messagingTemplate.convertAndSend("/topic/ai-insights", insight);
    }

    private void addToBuffer(String tokenId, double price, double volume) {
        priceBuffers.computeIfAbsent(tokenId, k -> Collections.synchronizedList(new ArrayList<>()));
        List<Double[]> buffer = priceBuffers.get(tokenId);
        buffer.add(new Double[]{price, volume});
        if (buffer.size() > BUFFER_SIZE) buffer.remove(0);
    }
}
