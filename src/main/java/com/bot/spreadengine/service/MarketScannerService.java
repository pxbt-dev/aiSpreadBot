package com.bot.spreadengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.bot.spreadengine.model.SpreadEvent;
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
    private final PerformanceTracker performanceTracker;
    private final SimpMessagingTemplate messagingTemplate;
    private final CopyOnWriteArrayList<ScannedMarket> activeMarkets = new CopyOnWriteArrayList<>();

    // Token-keyed caches populated during scan — used by RiskManagementService
    private final Map<String, java.time.Instant> tokenGameStartTimes = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Double> tokenRewardTiers = new java.util.concurrent.ConcurrentHashMap<>();

    public java.time.Instant getGameStartTime(String tokenId) { return tokenGameStartTimes.get(tokenId); }
    public double getRewardPerGame(String tokenId) { return tokenRewardTiers.getOrDefault(tokenId, 0.0); }

    public MarketScannerService(PolymarketService polymarketService,
                                ClaudeAnalysisService claudeAnalysisService,
                                PerformanceTracker performanceTracker,
                                SimpMessagingTemplate messagingTemplate) {
        this.polymarketService   = polymarketService;
        this.claudeAnalysisService = claudeAnalysisService;
        this.performanceTracker  = performanceTracker;
        this.messagingTemplate   = messagingTemplate;
    }

    /**
     * arbSpread = 1.0 − (YES_mid + NO_mid).
     * Positive: sum < 1 → can buy both sides for < $1, guaranteed $1 payout.
     * Negative: sum > 1 → can sell both sides for > $1, guaranteed profit.
     * Zero (|arbSpread| < 0.03): market is consistent, no guaranteed arb.
     */
    public record ScannedMarket(String tokenId, String noTokenId, String question, double mid, double noMid, double score, boolean isWeather, double arbSpread, java.time.Instant gameStartTime, double rewardPerGame) {}

    /**
     * Classifies a market by its reward pool per game from the April 2026 incentive program.
     * Used to decide whether to exit inventory before an event starts (only worth it for low-reward markets).
     */
    static double classifyRewardPerGame(String question) {
        if (question == null) return 0.0;
        String q = question.toLowerCase();
        // Tier 1: > $5000/game
        if (q.contains("champions league") || q.contains("ucl"))         return 24000;
        if (q.contains("premier league") || q.contains("epl"))           return 10000;
        if (q.contains("nba") || q.contains("cavaliers") || q.contains("rockets") || q.contains("lakers") ||
            q.contains("clippers") || q.contains("pacers") || q.contains("mavericks") ||
            q.contains("spurs") || q.contains("thunder") || q.contains("76ers") ||
            q.contains("nets") || q.contains("trail blazers") || q.contains("kings") ||
            q.contains("oilers") || q.contains("flames") || q.contains("kraken"))  return 7700;
        if (q.contains("ufc") || q.contains("mma"))                       return 4250;
        if (q.contains("europa league"))                                  return 4750;
        if (q.contains("ipl") || q.contains("indian premier league") || q.contains("cricket")) return 4500;
        // Tier 2: $1000–$5000/game
        if (q.contains("la liga") || q.contains("real madrid") || q.contains("barcelona") ||
            q.contains("atletico") || q.contains("rayo vallecano") || q.contains("celta") ||
            q.contains("cadiz") || q.contains("elche") || q.contains("granada"))   return 3300;
        if (q.contains("serie a") || q.contains("juventus") || q.contains("inter milan") ||
            q.contains("sampdoria") || q.contains("pescara") || q.contains("avellino") ||
            q.contains("catanzaro") || q.contains("palermo") || q.contains("frosinone") ||
            q.contains("cagliari"))                                        return 3300;
        if (q.contains("bundesliga") || q.contains("schalke") || q.contains("elversberg") ||
            q.contains("preußen münster") || q.contains("preusen munster") ||
            q.contains("greuther"))                                        return 3000;
        if (q.contains("ligue 1"))                                        return 2100;
        if (q.contains("mlb") || q.contains("astros") || q.contains("mariners") ||
            q.contains("braves") || q.contains("guardians") || q.contains("diamondbacks") ||
            q.contains("phillies"))                                        return 1650;
        if (q.contains("mls") || q.contains("toronto fc") || q.contains("montreal") ||
            q.contains("philadelphia union") || q.contains("inter miami") ||
            q.contains("new york red bulls") || q.contains("fc dallas") ||
            q.contains("st. louis"))                                       return 1650;
        if (q.contains("nhl") || q.contains("penguins") || q.contains("capitals"))   return 1500;
        if (q.contains("turkish super lig") || q.contains("konyaspor") ||
            q.contains("fatih karagumruk") || q.contains("rams basaksehir") ||
            q.contains("goztepe") || q.contains("kasimpasa") || q.contains("samsunspor")) return 2000;
        if (q.contains("liga mx") || q.contains("queretaro") || q.contains("necaxa") ||
            q.contains("atlas") || q.contains("monterrey"))               return 1650;
        if (q.contains("conference league"))                               return 1500;
        if (q.contains("atp") || q.contains("wta") || q.contains("tennis"))          return 1450;
        // Tier 3: $200–$1000/game
        if (q.contains("eredivisie") || q.contains("heracles") || q.contains("ajax")) return 900;
        if (q.contains("liga portugal") || q.contains("braga") || q.contains("arouca")) return 750;
        if (q.contains("efl championship") || q.contains("middlesbrough") ||
            q.contains("portsmouth") || q.contains("burnley") || q.contains("brentford") ||
            q.contains("norwich") || q.contains("ipswich"))               return 500;
        if (q.contains("j1 league") || q.contains("fc tokyo") || q.contains("mirassol") ||
            q.contains("ec bahia"))                                        return 1100;
        if (q.contains("a-league") || q.contains("auckland") || q.contains("western sydney") ||
            q.contains("adelaide"))                                        return 350;
        if (q.contains("k league") || q.contains("korean"))               return 750;
        if (q.contains("scottish") || q.contains("hibernian") || q.contains("kilmarnock") ||
            q.contains("dundee") || q.contains("livingston"))             return 100;
        if (q.contains("russian premier") || q.contains("united russia"))  return 375;
        if (q.contains("argentine") || q.contains("platense") || q.contains("huracan") ||
            q.contains("newell") || q.contains("racing club") || q.contains("river plate")) return 550;
        if (q.contains("brasileirao") || q.contains("brasileira"))        return 550;
        if (q.contains("chinese super league") || q.contains("tianjin") || q.contains("yunnan")) return 350;
        // Low-reward (< $200/game): Egyptian, Scandinavian, etc.
        if (q.contains("egyptian") || q.contains("el ahly") || q.contains("enppi") ||
            q.contains("ceramica") || q.contains("smouha"))               return 100;
        // Default: no known rewards
        return 0.0;
    }

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

    @Scheduled(fixedRate = 120_000) // Every 2 minutes
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

                // Parse game start time — Gamma API provides game_start_time for sports markets
                java.time.Instant gameStartTime = parseInstant(market.getOrDefault("game_start_time", null));
                double rewardPerGame = classifyRewardPerGame(question);

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
                    return yesMidMono.zipWith(noMidMono)
                        .flatMap(prices -> {
                            double yesMid = prices.getT1();
                            double noMid  = prices.getT2();
                            // ── Penny market filter ───────────────────────────────────────
                            // A $0.002 spread on a sub-cent market is 20-36% of price —
                            // impossible to fill and inflates simulated PnL with phantom gains.
                            if (yesMid > 0 && yesMid < 0.01) {
                                log.debug("🪙 PENNY FILTER: skipping {} (mid={})", question, yesMid);
                                return Mono.empty();
                            }

                            double proximity = 1.0 - Math.abs(yesMid - 0.5) * 2.0;
                            double perfMultiplier = performanceTracker.getScoreMultiplier(tokenId);
                            double score = liquidity * proximity * (isWeather ? 2.0 : 1.0) * perfMultiplier;
                            double arbSpread = noMid > 0 ? 1.0 - (yesMid + noMid) : 0.0;
                            if (Math.abs(arbSpread) > 0.03) {
                                log.warn("⚖️ ARB INVARIANT: YES={} + NO={} = {} (spread={}) — {}",
                                    String.format("%.3f", yesMid), String.format("%.3f", noMid),
                                    String.format("%.3f", yesMid + noMid), String.format("%+.3f", arbSpread), question);
                            }
                            if (gameStartTime != null) {
                                tokenGameStartTimes.put(tokenId, gameStartTime);
                                if (noTokenId != null) tokenGameStartTimes.put(noTokenId, gameStartTime);
                            }
                            tokenRewardTiers.put(tokenId, rewardPerGame);
                            if (noTokenId != null) tokenRewardTiers.put(noTokenId, rewardPerGame);
                            return Mono.just(new ScannedMarket(tokenId, noTokenId, question, yesMid, noMid, score, isWeather, arbSpread, gameStartTime, rewardPerGame));
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
                // Add all non-weather candidates for market-making diversity (up to 8)
                List<ScannedMarket> mmMarkets = markets.stream()
                    .filter(m -> !m.isWeather())
                    .collect(java.util.stream.Collectors.toList());
                if (mmMarkets.isEmpty()) mmMarkets.add(markets.get(0)); // fallback: use best overall

                // Add the best weather market for weather arb (if any)
                markets.stream()
                    .filter(ScannedMarket::isWeather)
                    .findFirst()
                    .ifPresent(mmMarkets::add);

                activeMarkets.addAll(mmMarkets);

                log.info("✅ Scanner selected {} markets:", activeMarkets.size());
                activeMarkets.forEach(m -> log.info("  → [{}] mid={} — {}",
                    m.isWeather() ? "WEATHER/ARB" : "MM", String.format("%.3f", m.mid()), m.question()));

                if (weatherCount == 0) {
                    log.warn("⚠️ No weather markets found in top 200 active markets — weather arb disabled until next scan");
                }

                // One Claude call per scan cycle — asynchronous, non-blocking
                claudeAnalysisService.analyzeMarketScan(markets)
                    .subscribe(insight -> messagingTemplate.convertAndSend("/topic/events",
                        new SpreadEvent("SCAN_INSIGHT", insight, 0)),
                        e -> log.warn("Scan insight failed: {}", e.getMessage()));
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

    private java.time.Instant parseInstant(Object raw) {
        if (raw == null) return null;
        try { return java.time.Instant.parse(raw.toString()); }
        catch (Exception e) { return null; }
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
