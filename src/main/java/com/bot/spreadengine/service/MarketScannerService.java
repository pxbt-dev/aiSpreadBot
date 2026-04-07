package com.bot.spreadengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class MarketScannerService {

    private final PolymarketService polymarketService;
    private final ClaudeAnalysisService claudeAnalysisService;
    private final CopyOnWriteArrayList<ScannedMarket> activeMarkets = new CopyOnWriteArrayList<>();

    public MarketScannerService(PolymarketService polymarketService, ClaudeAnalysisService claudeAnalysisService) {
        this.polymarketService = polymarketService;
        this.claudeAnalysisService = claudeAnalysisService;
    }

    /**
     * arbSpread = 1.0 − (YES_mid + NO_mid).
     * Positive: sum < 1 → can buy both sides for < $1, guaranteed $1 payout.
     * Negative: sum > 1 → can sell both sides for > $1, guaranteed profit.
     * Zero (|arbSpread| < 0.03): market is consistent, no guaranteed arb.
     */
    public record ScannedMarket(String tokenId, String noTokenId, String question, double mid, double noMid, double score, boolean isWeather, double arbSpread) {}

    // Keywords that identify weather / precipitation markets
    private static final List<String> WEATHER_KEYWORDS = List.of(
        "rain", "precip", "snow", "storm", "hurricane", "flood",
        "temperature", "weather", "celsius", "fahrenheit", "drought",
        "wind", "tornado", "blizzard", "hail", "fog", "thunder", "lightning",
        "inches", "wildfire", "freeze", "frost", "cold", "hot", "heat"
    );

    private static final double MIN_LIQUIDITY = 10.0; // lowered from 100 — weather markets are niche

    @PostConstruct
    public void init() {
        scan();
    }

    @Scheduled(fixedRate = 3600000) // Hourly
    public void scan() {
        log.info("🔍 Market scanner starting...");
        polymarketService.fetchActiveMarkets()
            .flatMapMany(markets -> Flux.fromIterable(markets))
            .flatMap(market -> {
                String question = (String) market.getOrDefault("question", "Unknown");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tokens = (List<Map<String, Object>>) market.getOrDefault("tokens", List.of());
                double liquidity = toDouble(market.getOrDefault("liquidity", 0));

                // Only consider binary markets (2 tokens) with meaningful liquidity
                if (tokens.size() < 2 || liquidity < MIN_LIQUIDITY) {
                    if (isWeatherMarket(question) && liquidity > 0) {
                        log.debug("⚠️ Skipped low-liquidity weather market (${} liq): {}", liquidity, question);
                    }
                    return Mono.empty();
                }

                // Pick the YES token (first token); NO token is second
                String tokenId = (String) tokens.get(0).get("token_id");
                if (tokenId == null) return Mono.empty();
                String noTokenId = tokens.size() > 1 ? (String) tokens.get(1).get("token_id") : null;

                boolean keywordMatch = isWeatherMarket(question);

                // For keyword matches, ask Claude to confirm it's a genuine meteorological market.
                // Non-keyword markets skip Claude (they're never treated as weather anyway).
                Mono<Boolean> weatherCheck = keywordMatch
                    ? claudeAnalysisService.isWeatherMarket(question)
                    : Mono.just(false);

                return weatherCheck.flatMap(isWeather -> {
                    Mono<Double> yesMidMono = polymarketService.getMidpoint(tokenId);
                    // Fetch NO midpoint to check arbitrage invariant (YES + NO should ≈ 1.0)
                    Mono<Double> noMidMono = noTokenId != null
                        ? polymarketService.getMidpoint(noTokenId).onErrorReturn(0.0)
                        : Mono.just(0.0);
                    return yesMidMono.zipWith(noMidMono, (yesMid, noMid) -> {
                        double proximity = 1.0 - Math.abs(yesMid - 0.5) * 2.0;
                        double score = liquidity * proximity * (isWeather ? 2.0 : 1.0);
                        // arbSpread > 0: buy both sides for < $1 (guaranteed profit)
                        // arbSpread < 0: sell both sides for > $1 (guaranteed profit)
                        double arbSpread = noMid > 0 ? 1.0 - (yesMid + noMid) : 0.0;
                        if (Math.abs(arbSpread) > 0.03) {
                            log.warn("⚖️ ARB INVARIANT: YES={:.3f} + NO={:.3f} = {:.3f} (spread={:+.3f}) — {}",
                                yesMid, noMid, yesMid + noMid, arbSpread, question);
                        }
                        return new ScannedMarket(tokenId, noTokenId, question, yesMid, noMid, score, isWeather, arbSpread);
                    });
                });
            })
            .sort((a, b) -> Double.compare(b.score(), a.score()))
            .take(10) // Wider net so we can split primary/secondary properly
            .collectList()
            .subscribe(markets -> {
                long weatherCount = markets.stream().filter(ScannedMarket::isWeather).count();
                log.info("🔍 Scanner top-10 candidates: {} total, {} weather", markets.size(), weatherCount);
                markets.forEach(m -> log.info("  candidate [weather={}, score={:.0f}, mid={:.3f}] {}",
                    m.isWeather(), m.score(), m.mid(), m.question()));

                if (markets.isEmpty()) {
                    log.warn("⚠️ Scanner found no tradeable markets — keeping existing tokens");
                    return;
                }
                activeMarkets.clear();

                // Primary: best non-weather market for market-making
                // Secondary: best weather market for arb; fall back to second-best overall
                ScannedMarket primary = markets.stream()
                    .filter(m -> !m.isWeather())
                    .findFirst()
                    .orElse(markets.get(0));

                ScannedMarket secondary = markets.stream()
                    .filter(m -> m.isWeather() && !m.tokenId().equals(primary.tokenId()))
                    .findFirst()
                    .orElse(markets.stream()
                        .filter(m -> !m.tokenId().equals(primary.tokenId()))
                        .findFirst()
                        .orElse(null));

                activeMarkets.add(primary);
                if (secondary != null && !secondary.tokenId().equals(primary.tokenId())) {
                    activeMarkets.add(secondary);
                }

                log.info("✅ Scanner selected {} markets:", activeMarkets.size());
                activeMarkets.forEach(m -> log.info("  → [{}] mid={:.3f} — {}",
                    m.isWeather() ? "WEATHER/ARB" : "PRIMARY/MM", m.mid(), m.question()));

                if (weatherCount == 0) {
                    log.warn("⚠️ No weather markets found in top 200 active markets — weather arb disabled until next scan");
                }
            }, e -> log.error("Market scan failed: {}", e.getMessage()));
    }

    private boolean isWeatherMarket(String question) {
        if (question == null) return false;
        String q = question.toLowerCase();
        return WEATHER_KEYWORDS.stream().anyMatch(q::contains);
    }

    /** Best non-weather market for market-making (most liquid, closest to 0.5) */
    public String getPrimaryTokenId() {
        return activeMarkets.isEmpty() ? null : activeMarkets.get(0).tokenId();
    }

    /** Best weather market for arb; returns null if no weather market was found (no fallback to non-weather). */
    public String getSecondaryTokenId() {
        return activeMarkets.stream()
            .filter(ScannedMarket::isWeather)
            .map(ScannedMarket::tokenId)
            .findFirst()
            .orElse(null);
    }

    /** Full market record for the secondary slot; returns null if no validated weather market exists. */
    public ScannedMarket getSecondaryMarket() {
        return activeMarkets.stream()
            .filter(ScannedMarket::isWeather)
            .findFirst()
            .orElse(null);
    }

    public List<ScannedMarket> getActiveMarkets() {
        return new ArrayList<>(activeMarkets);
    }

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        try { return Double.parseDouble(val.toString()); }
        catch (NumberFormatException e) { return 0.0; }
    }
}
