package com.bot.spreadengine.controller;

import com.bot.spreadengine.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

/**
 * REST endpoints for live bot diagnostics and on-demand Claude analysis.
 *
 * GET /api/status          — full snapshot of bot state (JSON)
 * GET /api/markets         — active scanned markets
 * GET /api/positions        — open positions + trade history
 * GET /api/noaa            — live NOAA weather conditions
 * GET /api/claude-analyze  — packages full state into a Claude prompt and returns AI analysis
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class DiagnosticsController {

    private final PositionService positionService;
    private final MarketScannerService marketScanner;
    private final WeatherService weatherService;
    private final LiveTradingEngine liveTradingEngine;
    private final WekaAnalysisService wekaService;
    private final ClaudeAnalysisService claudeAnalysisService;
    private final SpaceWeatherService spaceWeatherService;

    public DiagnosticsController(PositionService positionService,
                                 MarketScannerService marketScanner,
                                 WeatherService weatherService,
                                 LiveTradingEngine liveTradingEngine,
                                 WekaAnalysisService wekaService,
                                 ClaudeAnalysisService claudeAnalysisService,
                                 SpaceWeatherService spaceWeatherService) {
        this.positionService = positionService;
        this.marketScanner = marketScanner;
        this.weatherService = weatherService;
        this.liveTradingEngine = liveTradingEngine;
        this.wekaService = wekaService;
        this.claudeAnalysisService = claudeAnalysisService;
        this.spaceWeatherService = spaceWeatherService;
    }

    /** Full snapshot of bot state — useful for pasting into Claude or debugging. */
    @GetMapping("/status")
    public Mono<Map<String, Object>> getStatus() {
        return weatherService.getCurrentConditions().map(noaa -> {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("timestamp", Instant.now().toString());
            status.put("bankroll", positionService.getBankroll());
            status.put("totalProfit", positionService.getTotalProfit());
            status.put("totalTrades", positionService.getTotalTrades());
            status.put("killSwitchActive", positionService.isKillSwitchActive());
            status.put("aiConfidence", liveTradingEngine.getCurrentConfidence());
            status.put("sentimentScore", liveTradingEngine.getSentimentScore());
            status.put("auditNote", liveTradingEngine.getCurrentAuditNote());
            status.put("claudeModel", claudeAnalysisService.getModelName());

            // NOAA
            Map<String, Object> weather = new LinkedHashMap<>();
            weather.put("precipProbability", noaa[0]);
            weather.put("tempCelsius", noaa[1]);
            weather.put("humidity", noaa[2]);
            status.put("noaa", weather);

            // WEKA
            double normalizedTemp = Math.max(0.0, Math.min(1.0, (noaa[1] + 20.0) / 60.0));
            double wekaScore = wekaService.getConsensusScore("Weather", new double[]{noaa[0], normalizedTemp, noaa[2], 1.0});
            status.put("wekaConsensus", wekaScore);

            // Markets
            List<Map<String, Object>> markets = new ArrayList<>();
            for (MarketScannerService.ScannedMarket m : marketScanner.getActiveMarkets()) {
                Map<String, Object> mMap = new LinkedHashMap<>();
                mMap.put("tokenId", m.tokenId());
                mMap.put("question", m.question());
                mMap.put("mid", m.mid());
                mMap.put("score", m.score());
                mMap.put("isWeather", m.isWeather());
                markets.add(mMap);
            }
            status.put("activeMarkets", markets);

            // Positions
            status.put("openPositions", positionService.getPositions());
            status.put("recentTrades", positionService.getTradeHistory()
                .stream().skip(Math.max(0, positionService.getTradeHistory().size() - 10)).toList());

            return status;
        }).defaultIfEmpty(Map.of("error", "NOAA unavailable"));
    }

    /** Active markets only — quick lookup for token IDs */
    @GetMapping("/markets")
    public List<Map<String, Object>> getMarkets() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MarketScannerService.ScannedMarket m : marketScanner.getActiveMarkets()) {
            Map<String, Object> mMap = new LinkedHashMap<>();
            mMap.put("tokenId", m.tokenId());
            mMap.put("question", m.question());
            mMap.put("mid", m.mid());
            mMap.put("isWeather", m.isWeather());
            result.add(mMap);
        }
        return result;
    }

    /** Open positions and last 20 trades */
    @GetMapping("/positions")
    public Map<String, Object> getPositions() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("openPositions", positionService.getPositions());
        result.put("bankroll", positionService.getBankroll());
        result.put("totalProfit", positionService.getTotalProfit());
        result.put("killSwitchActive", positionService.isKillSwitchActive());
        List<PositionService.TradeRecord> history = positionService.getTradeHistory();
        result.put("recentTrades", history.stream().skip(Math.max(0, history.size() - 20)).toList());
        return result;
    }

    /**
     * Trade gate diagnostic — shows exactly why the bot is or isn't trading.
     * Checks each condition in the executeWeatherArb gate and reports pass/fail.
     */
    @GetMapping("/debug")
    public Mono<Map<String, Object>> getDebug() {
        MarketScannerService.ScannedMarket secondary = marketScanner.getSecondaryMarket();

        return weatherService.getCurrentConditions().map(noaa -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("timestamp", Instant.now().toString());

            // Scanner
            d.put("scannerHasSecondaryMarket", secondary != null);
            if (secondary != null) {
                d.put("secondaryMarket", secondary.question());
                d.put("secondaryTokenId", secondary.tokenId());
                d.put("secondaryMid", secondary.mid());
                d.put("isWeatherMarket", secondary.isWeather());
            }

            // NOAA
            double noaaProb = noaa[0];
            double marketMid = secondary != null ? secondary.mid() : -1.0;
            double gap = Math.abs(noaaProb - marketMid);
            d.put("noaaPrecipProb", noaaProb);
            d.put("marketMid", marketMid);
            d.put("gap", gap);
            d.put("gapThreshold", 0.16);
            d.put("gapCheckPasses", gap >= 0.16);

            // Confidence
            double confidence = liveTradingEngine.getCurrentConfidence();
            d.put("currentConfidence", confidence);
            d.put("confidenceThreshold", 0.6);
            d.put("confidenceCheckPasses", confidence > 0.6);

            // Kill switch
            d.put("killSwitchActive", positionService.isKillSwitchActive());

            // Verdict
            boolean wouldTrade = secondary != null && gap >= 0.16 && confidence > 0.6
                && !positionService.isKillSwitchActive();
            d.put("WOULD_TRADE", wouldTrade);
            if (!wouldTrade) {
                List<String> blockers = new ArrayList<>();
                if (secondary == null) blockers.add("No secondary market found by scanner");
                if (gap < 0.16) blockers.add(String.format("Gap %.1f%% < 16%% threshold", gap * 100));
                if (confidence <= 0.6) blockers.add(String.format("Confidence %.0f%% <= 60%% threshold (set ANTHROPIC_API_KEY to get real sentiment)", confidence * 100));
                if (positionService.isKillSwitchActive()) blockers.add("Kill switch active");
                d.put("blockers", blockers);
            }
            return d;
        }).defaultIfEmpty(Map.of("error", "NOAA unavailable"));
    }

    /** Live NOAA conditions */
    @GetMapping("/noaa")
    public Mono<Map<String, Object>> getNoaa() {
        return weatherService.getCurrentConditions().map(noaa -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("precipProbability", noaa[0]);
            result.put("tempCelsius", noaa[1]);
            result.put("humidity", noaa[2]);
            result.put("source", "NOAA NWS api.weather.gov gridpoints/OKX/33,37 (NYC Central Park)");
            result.put("timestamp", Instant.now().toString());
            return result;
        }).defaultIfEmpty(Map.of("error", "NOAA API unavailable"));
    }

    /**
     * Packages the full bot state into a Claude prompt and returns an AI analysis.
     * Great for pasting into Claude or automating "what's the bot doing and why?" checks.
     */
    @GetMapping("/claude-analyze")
    public Mono<Map<String, Object>> claudeAnalyze() {
        return weatherService.getCurrentConditions().flatMap(noaa -> {
            double normalizedTemp = Math.max(0.0, Math.min(1.0, (noaa[1] + 20.0) / 60.0));
            double wekaScore = wekaService.getConsensusScore("Weather",
                new double[]{noaa[0], normalizedTemp, noaa[2], 1.0});

            List<PositionService.Position> positions = positionService.getPositions();
            List<PositionService.TradeRecord> history = positionService.getTradeHistory();
            List<MarketScannerService.ScannedMarket> markets = marketScanner.getActiveMarkets();

            String prompt = String.format(
                "You are a senior quantitative analyst reviewing a prediction market trading bot. " +
                "Provide a concise diagnostic summary and actionable recommendations.\n\n" +
                "=== BOT STATE ===\n" +
                "Bankroll: $%.2f (started $%.2f) | Kill Switch: %s\n" +
                "Session P&L: $%.2f | Total Trades: %d\n" +
                "AI Confidence: %.1f%% | WEKA Consensus: %.2f | Sentiment: %.2f\n" +
                "Last Audit: %s\n\n" +
                "=== LIVE NOAA DATA ===\n" +
                "Precip Probability: %.1f%% | Temp: %.1f°C | Humidity: %.1f%%\n\n" +
                "=== ACTIVE MARKETS ===\n%s\n\n" +
                "=== OPEN POSITIONS (%d) ===\n%s\n\n" +
                "=== LAST 5 TRADES ===\n%s\n\n" +
                "Analyse: Are there any risk concerns? Why is/isn't the bot trading? " +
                "What should be adjusted? Respond in 3-5 bullet points.",
                positionService.getBankroll(), PositionService.INITIAL_BANKROLL,
                positionService.isKillSwitchActive() ? "ACTIVE ⚠️" : "armed",
                positionService.getTotalProfit(), positionService.getTotalTrades(),
                liveTradingEngine.getCurrentConfidence() * 100, wekaScore,
                liveTradingEngine.getSentimentScore(),
                liveTradingEngine.getCurrentAuditNote(),
                noaa[0] * 100, noaa[1], noaa[2] * 100,
                formatMarkets(markets),
                positions.size(),
                formatPositions(positions),
                formatTrades(history.stream().skip(Math.max(0, history.size() - 5)).toList())
            );

            log.info("📋 /api/claude-analyze — sending diagnostic prompt to Claude");

            Map<String, Object> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);

            Map<String, Object> request = new HashMap<>();
            request.put("model", claudeAnalysisService.getModelName());
            request.put("max_tokens", 1024);
            request.put("messages", List.of(message));

            // Reuse the same WebClient from ClaudeAnalysisService via a dedicated call
            return claudeAnalysisService.analyzeDiagnostics(prompt).map(analysis -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("timestamp", Instant.now().toString());
                result.put("analysis", analysis);
                result.put("contextSnapshot", Map.of(
                    "bankroll", positionService.getBankroll(),
                    "aiConfidence", liveTradingEngine.getCurrentConfidence(),
                    "wekaConsensus", wekaScore,
                    "noaaPrecip", noaa[0],
                    "openPositions", positions.size()
                ));
                return result;
            });
        });
    }

    private String formatMarkets(List<MarketScannerService.ScannedMarket> markets) {
        if (markets.isEmpty()) return "  (none scanned yet)";
        StringBuilder sb = new StringBuilder();
        for (MarketScannerService.ScannedMarket m : markets) {
            sb.append(String.format("  [%s] %s — mid=%.3f%n",
                m.isWeather() ? "WEATHER" : "PRIMARY", m.question(), m.mid()));
        }
        return sb.toString().trim();
    }

    private String formatPositions(List<PositionService.Position> positions) {
        if (positions.isEmpty()) return "  (none)";
        StringBuilder sb = new StringBuilder();
        for (PositionService.Position p : positions) {
            sb.append(String.format("  %s %s x%d @ $%.3f — mark $%.3f — P&L $%.2f%n",
                p.getSide(), p.getTicker(), p.getSize(), p.getEntryPrice(), p.getLastPrice(), p.getPnl()));
        }
        return sb.toString().trim();
    }

    private String formatTrades(List<PositionService.TradeRecord> trades) {
        if (trades.isEmpty()) return "  (none)";
        StringBuilder sb = new StringBuilder();
        for (PositionService.TradeRecord t : trades) {
            sb.append(String.format("  %s %s x%d @ $%.3f  P&L=$%.2f%n",
                t.getSide(), t.getAsset(), t.getQty(), t.getPrice(), t.getRealizedPnL()));
        }
        return sb.toString().trim();
    }
}
