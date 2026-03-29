package com.bot.spreadengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SentimentService {

    private final WebClient webClient;
    
    @Value("${anthropic.api.key:}")
    private String apiKey;

    @Value("${anthropic.model:claude-haiku-4-5-20251001}")
    private String model;

    public SentimentService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl("https://api.anthropic.com").build();
    }

    public Mono<Double> getSentimentScore(String topic) {
        if (apiKey == null || apiKey.isEmpty()) {
            return Mono.just(0.5);
        }

        String prompt = String.format(
            "You are a sentiment analyst for a prediction market trading bot.\n\n" +
            "Analyse the following topic and return a sentiment confidence score " +
            "representing how strongly current news/narrative supports a POSITIVE outcome " +
            "for this prediction market topic.\n\n" +
            "TOPIC: %s\n\n" +
            "Consider: recent news trends, public sentiment, narrative momentum, and any " +
            "known upcoming events that could affect the outcome.\n\n" +
            "Respond ONLY in JSON: {\"sentiment_score\": float between 0.0 and 1.0, " +
            "\"reasoning\": \"one concise sentence\"}\n\n" +
            "0.0 = very negative outlook, 0.5 = neutral, 1.0 = very positive outlook",
            topic
        );

        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("max_tokens", 256);
        request.put("messages", List.of(message));

        return webClient.post()
                .uri("/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(2))
                .map(response -> {
                    try {
                        List<Map<String, Object>> contentList = 
                            (List<Map<String, Object>>) response.get("content");
                        String responseText = (String) contentList.get(0).get("text");
                        log.info("🤖 Claude Sentiment Response: {}", responseText);

                        int startIdx = responseText.indexOf("{");
                        int endIdx = responseText.lastIndexOf("}");
                        if (startIdx != -1 && endIdx != -1) {
                            responseText = responseText.substring(startIdx, endIdx + 1);
                        }
                        
                        com.fasterxml.jackson.databind.ObjectMapper mapper = 
                            new com.fasterxml.jackson.databind.ObjectMapper();
                        com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(responseText);
                        
                        double score = rootNode.path("sentiment_score").asDouble(0.5);
                        log.info("📊 Sentiment score for '{}': {}", topic, score);
                        return Math.max(0.0, Math.min(1.0, score)); // Clamp 0-1
                        
                    } catch (Exception e) {
                        log.error("Sentiment parse error for topic: {}", topic, e);
                        return 0.5; // Neutral fallback on error
                    }
                })
                .onErrorResume(e -> {
                    if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
                        log.error("Sentiment API error {} — body: {}", wcre.getStatusCode(), wcre.getResponseBodyAsString());
                    } else {
                        log.error("Sentiment service error: {}", e.getMessage());
                    }
                    return Mono.just(0.5);
                });
    }
}
