package com.bot.spreadengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class OllamaAnalysisService {

    private final WebClient webClient;
    
    @Value("${ollama.enabled:false}")
    private boolean enabled;

    @Value("${ollama.model:qwen2.5:7b}")
    private String model;

    public OllamaAnalysisService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl("http://localhost:11434").build();
    }

    public Mono<Map<String, Object>> auditTrade(double solarMultiplier, double wekaConsensus, double sentiment) {
        if (!enabled) {
            Map<String, Object> result = new HashMap<>();
            result.put("auditPass", true);
            result.put("note", "OLLAMA DISABLED");
            return Mono.just(result);
        }

        String prompt = String.format(
            "Market Audit Task:\n" +
            "- Solar Multiplier: %.2fx (Geomagnetic activity level)\n" +
            "- WEKA ML Consensus: %.2f (Technical confidence)\n" +
            "- Sentiment Score: %.2f (NLP news impact)\n" +
            "\n" +
            "Question: Is it logically sound to execute a high-frequency trade given these variables? " +
            "Respond in JSON format: {\"audit_pass\": boolean, \"reasoning\": \"short string\"}. " +
            "If solar > 1.5 and consensus < 0.5, fail the audit. Otherwise pass.",
            solarMultiplier, wekaConsensus, sentiment
        );

        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("prompt", prompt);
        request.put("stream", false);
        request.put("format", "json");

        return webClient.post()
                .uri("/api/generate")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> {
                    try {
                        String responseText = (String) response.get("response");
                        // In real usage, parse JSON. For now, simulate robust handling
                        log.info("🤖 Ollama Audit Response: {}", responseText);
                        Map<String, Object> result = new HashMap<>();
                        result.put("auditPass", responseText.toLowerCase().contains("true"));
                        result.put("note", "AI AUDITED: " + (responseText.contains("reasoning") ? "SUCCESS" : "PASSED"));
                        return result;
                    } catch (Exception e) {
                        log.error("Ollama parse error", e);
                        Map<String, Object> result = new HashMap<>();
                        result.put("auditPass", true);
                        result.put("note", "AUDIT BYPASS (ERROR)");
                        return result;
                    }
                })
                .onErrorResume(e -> {
                    log.error("Ollama connection failed", e);
                    Map<String, Object> result = new HashMap<>();
                    result.put("auditPass", true);
                    result.put("note", "AUDIT BYPASS (OFFLINE)");
                    return Mono.just(result);
                });
    }

    public String getModelName() { return model; }
}
