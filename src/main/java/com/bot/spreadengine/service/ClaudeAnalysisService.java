package com.bot.spreadengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ClaudeAnalysisService {

    private final WebClient webClient;

    @Value("${anthropic.api.key:}")
    private String apiKey;

    @Value("${anthropic.model:claude-haiku-4-5-20251001}")
    private String model;

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
                    log.error("Claude connection failed or timed out", e);
                    Map<String, Object> result = new HashMap<>();
                    result.put("auditPass", false);
                    result.put("note", "AUDIT BLOCKED (OFFLINE/TIMEOUT)");
                    return Mono.just(result);
                });
    }

    public String getModelName() { return model; }
}
