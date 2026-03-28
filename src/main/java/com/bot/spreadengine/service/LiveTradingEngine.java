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

    public LiveTradingEngine(PolymarketService polymarketService, WeatherService weatherService, WekaAnalysisService wekaService, 
                             TechnicalAnalysisService taService, WyckoffService wyckoffService,
                             SpaceWeatherService spaceWeatherService, SentimentService sentimentService,
                             SimpMessagingTemplate messagingTemplate, HistoricalSolarService historyService,
                             PositionService positionService) {
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

        // Phase 6: Sync historical solar data for ensemble training
        this.historyService.syncRecentHistory();
        
        updateSolarData();
    }

    @Autowired
    private ClaudeAnalysisService claudeAnalysisService;

    private double currentConfidence = 0.0;
    private String currentAuditNote = "READY";
    private double sentimentScore = 0.0;
    private double solarMultiplier = 1.0;
    private final Map<String, List<Double[]>> priceBuffers = new ConcurrentHashMap<>();
    private static final int BUFFER_SIZE = 50;

    // Real active Token IDs
    private static final String POLITICS_TOKEN_ID = "16040015440196279900485035793550429453516625694844857319147506590755961451627";
    private static final String WEATHER_TOKEN_ID = "46368744070631387314868557200103674213564381440952153040375462649565213460036";

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
        polymarketService.getMidpoint(POLITICS_TOKEN_ID)
            .subscribe(midpoint -> {
                addToBuffer(POLITICS_TOKEN_ID, midpoint, 100.0);
                
                List<Double[]> history = priceBuffers.get(POLITICS_TOKEN_ID);
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

    @Scheduled(fixedRate = 5000)
    public void executeWeatherArb() {
        weatherService.getPrecipitationProbability()
            .zipWith(polymarketService.getMidpoint(WEATHER_TOKEN_ID).onErrorResume(e -> Mono.empty()))
            .subscribe(tuple -> {
                double noaaProb = tuple.getT1();
                double marketProb = tuple.getT2();
                double gap = Math.abs(noaaProb - marketProb);

                // AI ENSEMBLE CONSENSUS
                double[] features = new double[]{noaaProb, 0.2, 0.4, solarMultiplier};
                
                sentimentService.getSentimentScore("NYC Rain Probability")
                    .subscribe(sentiment -> {
                        double consensus = wekaService.getConsensusScore("Weather", features);
                        double finalConfidence = (consensus * 0.7) + (sentiment * 0.3);
                        currentConfidence = finalConfidence;

                        boolean isSpring = wyckoffService.detectSpring(priceBuffers.getOrDefault(WEATHER_TOKEN_ID, new ArrayList<>()));
                        log.info("Ensemble Brain: Consensus={}, Sentiment={} -> Confidence={}", 
                            String.format("%.2f", consensus), String.format("%.2f", sentiment), String.format("%.2f", finalConfidence));

                        if (gap >= 0.16 && !isSpring && finalConfidence > 0.6) {
                            double kellySize = (positionService.getBankroll() * 0.1) * (finalConfidence); 
                            
                            if (finalConfidence >= 0.85) {
                                claudeAnalysisService.auditTrade(solarMultiplier, consensus, sentiment, 
                                                                 "BUY", kellySize, finalConfidence, gap, 
                                                                 tuple.getT1(), tuple.getT2())
                                    .subscribe(auditResult -> {
                                        boolean auditPass = (boolean) auditResult.get("auditPass");
                                        currentAuditNote = (String) auditResult.get("note");
                                        
                                        if (auditPass) {
                                            log.info("🔥 ENSEMBLE APPROVED: Confidence={}, Sizing: ${}", 
                                                String.format("%.2f", finalConfidence), String.format("%.2f", kellySize));
                                            
                                            messagingTemplate.convertAndSend("/topic/events", 
                                                new SpreadEvent("AUDIT_PASS", currentAuditNote.replace("AI AUDITED: ", ""), (int)(finalConfidence*100)));

                                            // Unified Position Tracking
                                            positionService.addTrade(WEATHER_TOKEN_ID, "Weather", "BUY", (int)(kellySize / tuple.getT2()), tuple.getT2());

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
                                // Unified Position Tracking
                                positionService.addTrade(WEATHER_TOKEN_ID, "Weather", "BUY", (int)(kellySize / tuple.getT2()), tuple.getT2());

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
        double[] features = new double[]{0.5, 0.2, 0.4, solarMultiplier};
        double consensus = wekaService.getConsensusScore("Weather", features);
        
        sentimentService.getSentimentScore("Market General")
            .subscribe(sentiment -> {
                AiInsightEvent insight = new AiInsightEvent();
                insight.setConfidence((int)(consensus * 100));
                insight.setSentiment(sentiment);
                this.sentimentScore = sentiment;
                
                List<Double[]> history = priceBuffers.getOrDefault(POLITICS_TOKEN_ID, new ArrayList<>());
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
