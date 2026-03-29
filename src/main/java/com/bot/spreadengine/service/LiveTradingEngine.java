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

    // Gap threshold below which we consider the arb closed and exit any open position
    private static final double GAP_CLOSE_THRESHOLD = 0.05;

    @Scheduled(fixedRate = 5000)
    public void executeWeatherArb() {
        String tokenId = marketScanner.getSecondaryTokenId();
        if (tokenId == null) return;
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

                log.info("🌡️ NOAA conditions — precip={:.2f} temp={:.1f}°C humidity={:.2f}",
                    noaaProb, tempC, humidity);

                // Gap-reversal exit: if the arb has closed, exit any open position
                PositionService.Position openPos = positionService.getPositionMap().get(tokenId);
                if (openPos != null && gap < GAP_CLOSE_THRESHOLD) {
                    log.info("📉 ARB CLOSED (gap={:.3f}) — exiting {} x{}", gap, openPos.getTicker(), openPos.getSize());
                    positionService.addTrade(tokenId, openPos.getTicker(), "SELL", openPos.getSize(), marketProb);
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

                sentimentService.getSentimentScore("NYC Rain Probability")
                    .subscribe(sentiment -> {
                        double consensus = wekaService.getConsensusScore("Weather", features);
                        double finalConfidence = (consensus * 0.7) + (sentiment * 0.3);
                        currentConfidence = finalConfidence;

                        boolean isSpring = wyckoffService.detectSpring(priceBuffers.getOrDefault(tokenId, new ArrayList<>()));
                        log.info("Ensemble Brain: Consensus={}, Sentiment={} -> Confidence={}",
                            String.format("%.2f", consensus), String.format("%.2f", sentiment), String.format("%.2f", finalConfidence));

                        if (gap >= 0.16 && !isSpring && finalConfidence > 0.6) {
                            double kellySize = (positionService.getBankroll() * 0.1) * finalConfidence;
                            double tradePrice = tuple.getT2();
                            int qty = tradePrice > 0 ? (int)(kellySize / tradePrice) : 0;

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
                                            log.info("🔥 ENSEMBLE APPROVED: Confidence={}, Sizing: ${}",
                                                String.format("%.2f", finalConfidence), String.format("%.2f", kellySize));
                                            messagingTemplate.convertAndSend("/topic/events",
                                                new SpreadEvent("AUDIT_PASS", currentAuditNote.replace("AI AUDITED: ", ""), (int)(finalConfidence*100)));
                                            positionService.addTrade(tokenId, "Weather", "BUY", qty, tradePrice);
                                            messagingTemplate.convertAndSend("/topic/events",
                                                new SpreadEvent("TRADE", "EXECUTED (" + (int)(finalConfidence*100) + "% AI CONF)", (int)(gap*100)));
                                        } else {
                                            log.warn("🛑 CLAUDE VETO: {}", currentAuditNote);
                                            messagingTemplate.convertAndSend("/topic/events",
                                                new SpreadEvent("AUDIT_VETO", "CLAUDE VETO: " + currentAuditNote.replace("AI AUDITED: ", "").replace("AUDIT BLOCKED ", ""), 0));
                                        }
                                    }, error -> log.error("Error in auditTrade: {}", error.getMessage()));
                            } else {
                                log.info("🔥 ENSEMBLE APPROVED: Confidence={}, Sizing: ${}",
                                    String.format("%.2f", finalConfidence), String.format("%.2f", kellySize));
                                positionService.addTrade(tokenId, "Weather", "BUY", qty, tradePrice);
                                messagingTemplate.convertAndSend("/topic/events",
                                    new SpreadEvent("TRADE", "ENSEMBLE APPROVED (" + (int)(finalConfidence*100) + "% AI CONF)", (int)(gap*100)));
                            }
                        }
                    }, error -> log.error("Error in sentimentService inside weather arb: {}", error.getMessage()));
            }, error -> log.error("Error in executeWeatherArb: {}", error.getMessage()));
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
        double consensus = wekaService.getConsensusScore("Weather", features);
        
        sentimentService.getSentimentScore("Market General")
            .subscribe(sentiment -> {
                double blendedConfidence = (consensus * 0.7) + (sentiment * 0.3);
                this.currentConfidence = blendedConfidence;
                this.sentimentScore = sentiment;

                AiInsightEvent insight = new AiInsightEvent();
                insight.setConfidence((int)(blendedConfidence * 100));
                insight.setSentiment(sentiment);
                
                String primary = marketScanner.getPrimaryTokenId();
                List<Double[]> history = priceBuffers.getOrDefault(primary != null ? primary : "", new ArrayList<>());
                if (!history.isEmpty()) {
                    Map<String, Double> ta = taService.analyzeOrderDepth("Politics", history);
                    insight.setRsi(ta.getOrDefault("RSI", 50.0));
                    insight.setMacd(ta.getOrDefault("MACD", 0.0) > 0 ? "BULL" : "BEAR");
                }

                messagingTemplate.convertAndSend("/topic/ai-insights", insight);
            }, error -> log.error("Error in broadcastAiInsights: {}", error.getMessage()));
    }

    private void addToBuffer(String tokenId, double price, double volume) {
        priceBuffers.computeIfAbsent(tokenId, k -> Collections.synchronizedList(new ArrayList<>()));
        List<Double[]> buffer = priceBuffers.get(tokenId);
        buffer.add(new Double[]{price, volume});
        if (buffer.size() > BUFFER_SIZE) buffer.remove(0);
    }
}
