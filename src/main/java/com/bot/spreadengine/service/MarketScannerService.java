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
    private final CopyOnWriteArrayList<ScannedMarket> activeMarkets = new CopyOnWriteArrayList<>();

    public MarketScannerService(PolymarketService polymarketService) {
        this.polymarketService = polymarketService;
    }

    public record ScannedMarket(String tokenId, String question, double mid, double score, boolean isWeather) {}

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

                // Pick the YES token (first token)
                String tokenId = (String) tokens.get(0).get("token_id");
                if (tokenId == null) return Mono.empty();

                boolean isWeather = isWeatherMarket(question);

                return polymarketService.getMidpoint(tokenId)
                    .map(mid -> {
                        // Score: liquidity × how close mid is to 0.5 (0.5 = max uncertainty = best spread)
                        // Weather markets get a 2× bonus so they always win the secondary slot
                        double proximity = 1.0 - Math.abs(mid - 0.5) * 2.0;
                        double score = liquidity * proximity * (isWeather ? 2.0 : 1.0);
                        return new ScannedMarket(tokenId, question, mid, score, isWeather);
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

    /** Best weather market for arb; falls back to second-best overall if none found */
    public String getSecondaryTokenId() {
        return activeMarkets.size() < 2 ? getPrimaryTokenId() : activeMarkets.get(1).tokenId();
    }

    /** Full market record for the secondary slot (includes question/ticker). */
    public ScannedMarket getSecondaryMarket() {
        return activeMarkets.size() < 2 ? (activeMarkets.isEmpty() ? null : activeMarkets.get(0)) : activeMarkets.get(1);
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
