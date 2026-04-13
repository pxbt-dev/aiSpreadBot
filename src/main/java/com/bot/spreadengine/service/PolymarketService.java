package com.bot.spreadengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@Slf4j
public class PolymarketService {

    private final WebClient webClient;
    private final AtomicInteger skippedMarketsCount = new AtomicInteger(0);
    private static final String GAMMA_BASE_URL = "https://gamma-api.polymarket.com";
    private static final String CLOB_BASE_URL = "https://clob.polymarket.com";

    @org.springframework.beans.factory.annotation.Value("${polymarket.api.key:}")
    private String apiKey;
    @org.springframework.beans.factory.annotation.Value("${polymarket.api.secret:}")
    private String apiSecret;
    @org.springframework.beans.factory.annotation.Value("${polymarket.api.passphrase:}")
    private String apiPassphrase;
    @org.springframework.beans.factory.annotation.Value("${polymarket.dry-run:true}")
    private boolean dryRun;

    public PolymarketService(WebClient.Builder webClientBuilder) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
        this.webClient = webClientBuilder.exchangeStrategies(strategies).build();
    }

    public int getSkippedMarketsCount() { return skippedMarketsCount.get(); }

    private String generateSignature(long timestamp, String method, String requestPath, String body) {
        try {
            String prehashString = timestamp + method.toUpperCase() + requestPath + (body != null ? body : "");
            javax.crypto.Mac sha256_HMAC = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secret_key = new javax.crypto.spec.SecretKeySpec(
                java.util.Base64.getUrlDecoder().decode(apiSecret), "HmacSHA256");
            sha256_HMAC.init(secret_key);
            return java.util.Base64.getEncoder().encodeToString(sha256_HMAC.doFinal(prehashString.getBytes()));
        } catch (Exception e) {
            log.error("Failed to generate signature: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Fetches top trending events from Polymarket Gamma API (keyset pagination).
     */
    public Mono<List<Map<String, Object>>> getTrendingEvents() {
        return webClient.get()
                .uri(GAMMA_BASE_URL + "/events/keyset?limit=10&active=true")
                .retrieve()
                .bodyToMono(Map.class)
                .map(map -> {
                    Object events = map.get("events");
                    return events instanceof List ? (List<Map<String, Object>>) events : Collections.<Map<String, Object>>emptyList();
                })
                .onErrorResume(e -> {
                    log.error("Error fetching trending events from Polymarket: {}", e.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    /**
     * Fetches active, non-closed markets from Gamma API ordered by liquidity (keyset pagination).
     */
    public Mono<List<Map<String, Object>>> fetchActiveMarkets() {
        return webClient.get()
                .uri(GAMMA_BASE_URL + "/markets/keyset?active=true&closed=false&limit=200&order=liquidity&ascending=false")
                .retrieve()
                .bodyToMono(Map.class)
                .map(map -> {
                    Object markets = map.get("markets");
                    return markets instanceof List ? (List<Map<String, Object>>) markets : Collections.<Map<String, Object>>emptyList();
                })
                .onErrorResume(e -> {
                    log.error("Error fetching active markets: {}", e.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    /**
     * Fetches weather/science category markets regardless of liquidity rank (keyset pagination).
     * Weather markets are niche and often outside the top-200 by liquidity.
     */
    public Mono<List<Map<String, Object>>> fetchWeatherMarkets() {
        return webClient.get()
                .uri(GAMMA_BASE_URL + "/markets/keyset?active=true&closed=false&limit=100&tag_slug=weather")
                .retrieve()
                .bodyToMono(Map.class)
                .map(map -> {
                    Object markets = map.get("markets");
                    return markets instanceof List ? (List<Map<String, Object>>) markets : Collections.<Map<String, Object>>emptyList();
                })
                .onErrorResume(e -> {
                    log.warn("Weather-category fetch unavailable (tag may not exist): {}", e.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    /**
     * Fetches live prices for a specific market asset.
     */
    public Mono<Map<String, Object>> getMarketPrice(String assetId) {
        return webClient.get()
                .uri(CLOB_BASE_URL + "/price?token_id=" + assetId + "&side=buy")
                .retrieve()
                .bodyToMono(Map.class)
                .map(map -> (Map<String, Object>) map)
                .onErrorResume(e -> {
                    log.error("Error fetching price for asset {}: {}", assetId, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Fetches the current mid-price for a specific token.
     */
    public Mono<Double> getMidpoint(String tokenId) {
        String path = "/midpoint?token_id=" + tokenId;
        WebClient.RequestHeadersSpec<?> request = webClient.get().uri(CLOB_BASE_URL + path);
        if (apiKey != null && !apiKey.isEmpty()) {
            long ts = System.currentTimeMillis() / 1000;
            request = request.header("POLY_API_KEY", apiKey)
                    .header("POLY_PASSPHRASE", apiPassphrase)
                    .header("POLY_TIMESTAMP", String.valueOf(ts))
                    .header("POLY_SIGNATURE", generateSignature(ts, "GET", "/midpoint?token_id=" + tokenId, null));
        }

        return request.retrieve()
                .onStatus(status -> status.value() == 404, response -> Mono.error(new RuntimeException("NOT_FOUND")))
                .bodyToMono(Map.class)
                .map(map -> {
                    Object mid = map.get("mid");
                    return mid != null ? Double.parseDouble(mid.toString()) : 0.0;
                })
                .onErrorResume(e -> {
                    boolean isNotFound = "NOT_FOUND".equals(e.getMessage())
                            || (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre
                                && wcre.getStatusCode().value() == 404);
                    if (isNotFound) {
                        log.warn("Market not found or no orderbook for token {}", tokenId);
                        skippedMarketsCount.incrementAndGet();
                        return Mono.empty();
                    }
                    log.error("Error fetching midpoint for token {}: {}", tokenId, e.getMessage());
                    return Mono.error(e);
                });
    }

    /**
     * Fetches the full order book for a specific token.
     */
    public Mono<Map<String, Object>> getOrderBook(String tokenId) {
        return webClient.get()
                .uri(CLOB_BASE_URL + "/book?token_id=" + tokenId)
                .retrieve()
                .bodyToMono(Map.class)
                .map(map -> (Map<String, Object>) map)
                .onErrorResume(e -> {
                    log.error("Error fetching order book for token {}: {}", tokenId, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Fetches currently active markets from the CLOB.
     */
    /**
     * Places a live order on the Polymarket CLOB.
     */
    public Mono<Map<String, Object>> placeOrder(Map<String, Object> orderPayload) {
        String path = "/order";
        long timestamp = System.currentTimeMillis() / 1000;
        String body = ""; // Stringify orderPayload
        try {
            body = new ObjectMapper().writeValueAsString(orderPayload);
        } catch (Exception e) {
            return Mono.error(e);
        }
        
        String signature = generateSignature(timestamp, "POST", path, body);
        
        if (dryRun) {
            log.info("[DRY RUN] Simulating order placement: {}", orderPayload);
            Map<String, Object> mockResponse = new java.util.HashMap<>();
            mockResponse.put("status", "OK");
            mockResponse.put("orderID", "SIM-" + System.currentTimeMillis());
            return Mono.just(mockResponse);
        }

        return webClient.post()
                .uri(CLOB_BASE_URL + path)
                .header("POLY_API_KEY", apiKey)
                .header("POLY_PASSPHRASE", apiPassphrase)
                .header("POLY_TIMESTAMP", String.valueOf(timestamp))
                .header("POLY_SIGNATURE", signature)
                .bodyValue(orderPayload)
                .retrieve()
                .bodyToMono(Map.class)
                .map(map -> (Map<String, Object>) map)
                .onErrorResume(e -> {
                    log.error("Error placing order: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<Map<String, Object>> getClobMarkets(String cursor) {
        String path = "/markets";
        String fullPath = path + (cursor != null && !cursor.isEmpty() ? "?next_cursor=" + cursor : "");
        
        WebClient.RequestHeadersSpec<?> request = webClient.get().uri(CLOB_BASE_URL + fullPath);
        if (apiKey != null && !apiKey.isEmpty()) {
            long ts = System.currentTimeMillis() / 1000;
            request = request.header("POLY_API_KEY", apiKey)
                    .header("POLY_PASSPHRASE", apiPassphrase)
                    .header("POLY_TIMESTAMP", String.valueOf(ts))
                    .header("POLY_SIGNATURE", generateSignature(ts, "GET", fullPath, null));
        }

        return request.retrieve()
                .bodyToMono(Map.class)
                .map(map -> (Map<String, Object>) map)
                .onErrorResume(e -> {
                    log.error("Error fetching CLOB markets: {}", e.getMessage());
                    return Mono.empty();
                });
    }
}
