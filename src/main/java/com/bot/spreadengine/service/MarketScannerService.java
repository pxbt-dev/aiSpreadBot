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

    public record ScannedMarket(String tokenId, String question, double mid, double score) {}

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
                if (tokens.size() < 2 || liquidity < 100) return Mono.empty();

                // Pick the YES token (first token)
                String tokenId = (String) tokens.get(0).get("token_id");
                if (tokenId == null) return Mono.empty();

                return polymarketService.getMidpoint(tokenId)
                    .map(mid -> {
                        // Score: liquidity × how close mid is to 0.5 (0.5 = max uncertainty = best spread)
                        double proximity = 1.0 - Math.abs(mid - 0.5) * 2.0;
                        double score = liquidity * proximity;
                        return new ScannedMarket(tokenId, question, mid, score);
                    });
            })
            .sort((a, b) -> Double.compare(b.score(), a.score()))
            .take(2)
            .collectList()
            .subscribe(markets -> {
                if (markets.isEmpty()) {
                    log.warn("⚠️ Scanner found no tradeable markets — keeping existing tokens");
                    return;
                }
                activeMarkets.clear();
                activeMarkets.addAll(markets);
                log.info("✅ Scanner selected {} markets:", markets.size());
                markets.forEach(m -> log.info("  → [score={:.2f}, mid={}] {}  token={}",
                        m.score(), m.mid(), m.question(), m.tokenId()));
            }, e -> log.error("Market scan failed: {}", e.getMessage()));
    }

    /** Best market for market-making (most liquid, closest to 0.5) */
    public String getPrimaryTokenId() {
        return activeMarkets.isEmpty() ? null : activeMarkets.get(0).tokenId();
    }

    /** Second-best market */
    public String getSecondaryTokenId() {
        return activeMarkets.size() < 2 ? getPrimaryTokenId() : activeMarkets.get(1).tokenId();
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
