package com.bot.spreadengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ClaudeAnalysisService {

    private final WebClient webClient;

    @Value("${anthropic.api.key:}")
    private String apiKey;

    @Value("${anthropic.model:claude-haiku-4-5-20251001}")
    private String model;

    /**
     * Permanent result cache — once a title is classified, never call Claude again.
     * Weather market classifications are stable; titles don't change mid-session.
     */
    private final Map<String, Boolean> weatherCache = new ConcurrentHashMap<>();

    /**
     * In-flight deduplication — if two concurrent scan pages surface the same market
     * simultaneously, only one HTTP call is made; both subscribers share the result.
     */
    private final Map<String, Mono<Boolean>> inflightValidations = new ConcurrentHashMap<>();

    public ClaudeAnalysisService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl("https://api.anthropic.com").build();
    }

    public Mono<Map<String, Object>> auditTrade(double solarMultiplier, double wekaConsensus, double sentiment,
                                                String direction, double size, double confidence, double gap,
                                                double noaaProb, double marketProb) {
        if (apiKey == null || apiKey.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("auditPass", true);
            result.put("note", "CLAUDE DISABLED (NO API KEY)");
            return Mono.just(result);
        }

        String prompt = String.format(
            "You are a quantitative risk auditor for a prediction market trading bot operating on Polymarket.\n\n" +
            "Evaluate the following proposed trade holistically using your own reasoning. " +
            "Do not apply rigid rules — assess the overall risk/reward profile.\n\n" +
            "TRADE DETAILS:\n" +
            "- Direction: %s | Size: $%.2f\n" +
            "- NOAA Real-World Probability: %.2f%%\n" +
            "- Polymarket Implied Probability: %.2f%%\n" +
            "- Arbitrage Gap: %.2f%% (minimum viable edge is ~16%%)\n\n" +
            "ENSEMBLE CONFIDENCE:\n" +
            "- Overall Confidence: %.2f (scale 0-1)\n" +
            "- Weka ML Score: %.2f\n" +
            "- Sentiment Score: %.2f\n\n" +
            "RISK ENVIRONMENT:\n" +
            "- Geomagnetic Solar Multiplier: %.2fx " +
            "(1.0 = normal, >1.3 = elevated systemic risk, >1.5 = high chaos)\n\n" +
            "Consider: Is the edge real and sufficient? Is confidence genuinely high? " +
            "Does the risk environment support execution?\n\n" +
            "Respond ONLY in JSON: {\"audit_pass\": boolean, \"reasoning\": \"one concise sentence\"}",
            direction, size, noaaProb * 100, marketProb * 100, gap * 100,
            confidence, wekaConsensus, sentiment, solarMultiplier
        );

        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("max_tokens", 1024);
        request.put("messages", List.of(message));

        return webClient.post()
                .uri("/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(java.time.Duration.ofSeconds(2))
                .map(response -> {
                    try {
                        List<Map<String, Object>> contentList = (List<Map<String, Object>>) response.get("content");
                        String responseText = (String) contentList.get(0).get("text");
                        log.info("🤖 Claude Audit Response: {}", responseText);
                        
                        // Extract RAW JSON
                        int startIdx = responseText.indexOf("{");
                        int endIdx = responseText.lastIndexOf("}");
                        if (startIdx != -1 && endIdx != -1) {
                            responseText = responseText.substring(startIdx, endIdx + 1);
                        }

                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(responseText);
                        
                        boolean auditPass = rootNode.path("audit_pass").asBoolean(false);
                        String reasoning = rootNode.path("reasoning").asText("No reasoning provided");

                        Map<String, Object> result = new HashMap<>();
                        result.put("auditPass", auditPass);
                        result.put("note", "AI AUDITED: " + reasoning);
                        return result;
                    } catch (Exception e) {
                        log.error("Claude parse error", e);
                        Map<String, Object> result = new HashMap<>();
                        result.put("auditPass", false);
                        result.put("note", "AUDIT FAILED (PARSE ERROR)");
                        return result;
                    }
                })
                .onErrorResume(e -> {
                    if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
                        log.error("Claude API error {} — body: {}", wcre.getStatusCode(), wcre.getResponseBodyAsString());
                    } else {
                        log.error("Claude connection failed or timed out: {}", e.getMessage());
                    }
                    Map<String, Object> result = new HashMap<>();
                    result.put("auditPass", false);
                    result.put("note", "AUDIT BLOCKED (OFFLINE/TIMEOUT)");
                    return Mono.just(result);
                });
    }

    public Mono<String> analyzeDiagnostics(String prompt) {
        if (apiKey == null || apiKey.isEmpty()) {
            return Mono.just("Claude disabled — no API key set. Check ANTHROPIC_API_KEY env var.");
        }

        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("max_tokens", 1024);
        request.put("messages", List.of(message));

        return webClient.post()
                .uri("/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(java.time.Duration.ofSeconds(30))
                .map(response -> {
                    try {
                        List<Map<String, Object>> contentList = (List<Map<String, Object>>) response.get("content");
                        return (String) contentList.get(0).get("text");
                    } catch (Exception e) {
                        log.error("Diagnostics parse error", e);
                        return "Parse error: " + e.getMessage();
                    }
                })
                .onErrorResume(e -> {
                    if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
                        log.error("Claude diagnostics API error {} — body: {}", wcre.getStatusCode(), wcre.getResponseBodyAsString());
                        return Mono.just("Claude API error: " + wcre.getStatusCode() + " — " + wcre.getResponseBodyAsString());
                    }
                    log.error("Claude diagnostics failed: {}", e.getMessage());
                    return Mono.just("Claude unavailable: " + e.getMessage());
                });
    }

    /**
     * Asks Claude whether a Polymarket market title describes a measurable meteorological outcome
     * (precipitation, temperature extremes, named storms, etc.) rather than politics, sports, or finance.
     * Returns true if the market is suitable for weather arb; false otherwise.
     * Falls back to true (permissive) if Claude is unavailable, so keyword pre-filtering still applies.
     */
    public Mono<Boolean> isWeatherMarket(String marketTitle) {
        if (apiKey == null || apiKey.isEmpty()) {
            return Mono.just(true); // no key — rely on keyword filter only
        }

        // Result cache: same title never calls Claude twice across scan cycles
        Boolean cached = weatherCache.get(marketTitle);
        if (cached != null) return Mono.just(cached);

        // In-flight dedup: if a call for this exact title is already in progress,
        // return the same Mono so both callers share the single HTTP request
        return inflightValidations.computeIfAbsent(marketTitle, title -> {
            String prompt = String.format(
                "You are classifying Polymarket prediction market titles.\n\n" +
                "Respond ONLY with valid JSON: {\"is_weather\": boolean}\n\n" +
                "Rules:\n" +
                "- true  → the market asks about a specific, measurable meteorological event " +
                "(precipitation amount, temperature threshold, named storm landfall, snowfall, drought, etc.)\n" +
                "- false → the market is about politics, elections, sports, economics, crypto, or any non-meteorological topic\n\n" +
                "Market title: \"%s\"",
                title.replace("\"", "'")
            );

            Map<String, Object> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);

            Map<String, Object> request = new HashMap<>();
            request.put("model", model);
            request.put("max_tokens", 64);
            request.put("messages", List.of(message));

            return webClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(java.time.Duration.ofSeconds(5))
                    .map(response -> {
                        try {
                            List<Map<String, Object>> contentList = (List<Map<String, Object>>) response.get("content");
                            String text = (String) contentList.get(0).get("text");
                            int start = text.indexOf("{");
                            int end = text.lastIndexOf("}");
                            if (start == -1 || end == -1) return true;
                            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(text.substring(start, end + 1));
                            boolean result = root.path("is_weather").asBoolean(true);
                            log.info("🌦️ Claude market validation [{}]: \"{}\"", result ? "WEATHER" : "NOT-WEATHER", title);
                            return result;
                        } catch (Exception e) {
                            log.warn("Claude market validation parse error — allowing market: {}", e.getMessage());
                            return true;
                        }
                    })
                    .onErrorResume(e -> {
                        log.warn("Claude market validation unavailable — allowing market: {}", e.getMessage());
                        return Mono.just(true);
                    })
                    .doOnSuccess(result -> {
                        // Persist to result cache and remove from in-flight map
                        weatherCache.put(title, result);
                        inflightValidations.remove(title);
                    })
                    .cache(); // make the Mono replayable so all subscribers share the one result
        });
    }

    public String getModelName() { return model; }
}
