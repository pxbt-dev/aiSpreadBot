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
                double liquidity = toDouble(market.getOrDefault("liquidity", 0));

                // Gamma API v2: token IDs are in clobTokenIds (JSON string array)
                // Fallback to legacy tokens[].token_id for backwards compat
                String[] clobIds = parseClobTokenIds(market.getOrDefault("clobTokenIds", null));
                if (clobIds[0].isEmpty()) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> tokens = (List<Map<String, Object>>) market.getOrDefault("tokens", List.of());
                    if (tokens.size() >= 1) clobIds[0] = String.valueOf(tokens.get(0).getOrDefault("token_id", ""));
                    if (tokens.size() >= 2) clobIds[1] = String.valueOf(tokens.get(1).getOrDefault("token_id", ""));
                }

                // Only consider binary markets (2 tokens) with meaningful liquidity
                if (clobIds[0].isEmpty() || liquidity < MIN_LIQUIDITY) {
                    if (isWeatherMarket(question) && liquidity > 0) {
                        log.debug("⚠️ Skipped low-liquidity weather market (${} liq): {}", liquidity, question);
                    }
                    return Mono.empty();
                }

                String tokenId = clobIds[0];
                String noTokenId = clobIds[1].isEmpty() ? null : clobIds[1];

                boolean keywordMatch = isWeatherMarket(question);

                // For keyword matches, ask Claude to confirm it's a genuine meteorological market.
                // Non-keyword markets skip Claude (they're never treated as weather anyway).
                Mono<Boolean> weatherCheck = keywordMatch
                    ? claudeAnalysisService.isWeatherMarket(question)
                    : Mono.just(false);

                // --- Extract prices from Gamma data (outcomePrices field) ---
                // Gamma API returns outcomePrices as a JSON string array e.g. "[\"0.65\",\"0.35\"]"
                // or as a List. Prefer this over CLOB /midpoint which may be unavailable post-V2.
                double[] gammaPrices = parseOutcomePrices(market.getOrDefault("outcomePrices", null));
                double gammaYesMid = gammaPrices[0];
                double gammaNoMid  = gammaPrices[1];

                return weatherCheck.flatMap(isWeather -> {
                    // Use Gamma prices if available (non-zero); fall back to CLOB only if missing
                    Mono<Double> yesMidMono = gammaYesMid > 0
                        ? Mono.just(gammaYesMid)
                        : polymarketService.getMidpoint(tokenId);
                    Mono<Double> noMidMono = gammaNoMid > 0
                        ? Mono.just(gammaNoMid)
                        : (noTokenId != null
                            ? polymarketService.getMidpoint(noTokenId).onErrorReturn(0.0)
                            : Mono.just(0.0));
                    return yesMidMono.zipWith(noMidMono, (yesMid, noMid) -> {
                        double proximity = 1.0 - Math.abs(yesMid - 0.5) * 2.0;
                        double score = liquidity * proximity * (isWeather ? 2.0 : 1.0);
                        // arbSpread > 0: buy both sides for < $1 (guaranteed profit)
                        // arbSpread < 0: sell both sides for > $1 (guaranteed profit)
                        double arbSpread = noMid > 0 ? 1.0 - (yesMid + noMid) : 0.0;
                        if (Math.abs(arbSpread) > 0.03) {
                            log.warn("⚖️ ARB INVARIANT: YES={} + NO={} = {} (spread={}) — {}",
                                String.format("%.3f", yesMid), String.format("%.3f", noMid),
                                String.format("%.3f", yesMid + noMid), String.format("%+.3f", arbSpread), question);
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
                markets.forEach(m -> log.info("  candidate [weather={}, score={}, mid={}] {}",
                    m.isWeather(), String.format("%.0f", m.score()), String.format("%.3f", m.mid()), m.question()));

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
                activeMarkets.forEach(m -> log.info("  → [{}] mid={} — {}",
                    m.isWeather() ? "WEATHER/ARB" : "PRIMARY/MM", String.format("%.3f", m.mid()), m.question()));

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

    /**
     * Parses the Gamma API clobTokenIds field into [yesTokenId, noTokenId].
     * Gamma v2 returns this as a JSON-encoded string array: "[\"123...\",\"456...\"]"
     * Returns ["", ""] on parse failure so callers can fall back to legacy tokens field.
     */
    private String[] parseClobTokenIds(Object raw) {
        if (raw == null) return new String[]{"", ""};
        try {
            String s = raw.toString().trim();
            if (s.startsWith("[")) {
                s = s.replaceAll("[\\[\\]\"\\s]", "");
                String[] parts = s.split(",");
                String yes = parts.length > 0 ? parts[0].trim() : "";
                String no  = parts.length > 1 ? parts[1].trim() : "";
                return new String[]{yes, no};
            }
        } catch (Exception e) {
            log.warn("Failed to parse clobTokenIds from '{}': {}", raw, e.getMessage());
        }
        return new String[]{"", ""};
    }

    /**
     * Parses the Gamma API outcomePrices field into [yesMid, noMid].
     * Gamma returns this as a JSON-encoded string array: "[\"0.65\",\"0.35\"]"
     * or as a java.util.List when already deserialized.
     * Returns [0.0, 0.0] on any parse failure so callers fall back to CLOB.
     */
    @SuppressWarnings("unchecked")
    private double[] parseOutcomePrices(Object raw) {
        if (raw == null) return new double[]{0.0, 0.0};
        try {
            List<Object> prices;
            if (raw instanceof List) {
                prices = (List<Object>) raw;
            } else {
                // Strip JSON-encoded string: "[\"0.65\",\"0.35\"]"
                String s = raw.toString().trim();
                if (s.startsWith("[")) {
                    s = s.replaceAll("[\\[\\]\"\\s]", "");
                    String[] parts = s.split(",");
                    double yes = parts.length > 0 ? Double.parseDouble(parts[0]) : 0.0;
                    double no  = parts.length > 1 ? Double.parseDouble(parts[1]) : 0.0;
                    log.debug("📊 Gamma outcomePrices (string): YES={} NO={}", yes, no);
                    return new double[]{yes, no};
                }
                return new double[]{0.0, 0.0};
            }
            double yes = prices.size() > 0 ? toDouble(prices.get(0)) : 0.0;
            double no  = prices.size() > 1 ? toDouble(prices.get(1)) : 0.0;
            log.debug("📊 Gamma outcomePrices (list): YES={} NO={}", yes, no);
            return new double[]{yes, no};
        } catch (Exception e) {
            log.warn("Failed to parse outcomePrices from '{}': {}", raw, e.getMessage());
            return new double[]{0.0, 0.0};
        }
    }
}
