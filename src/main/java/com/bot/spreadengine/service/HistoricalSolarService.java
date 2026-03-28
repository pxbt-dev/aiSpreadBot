package com.bot.spreadengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class HistoricalSolarService {

    private final WebClient webClient;
    private final WekaAnalysisService wekaService;
    private static final String HISTORY_URL = "https://services.swpc.noaa.gov/text/daily-geomagnetic-indices.txt";

    public HistoricalSolarService(WebClient.Builder webClientBuilder, WekaAnalysisService wekaService) {
        this.webClient = webClientBuilder
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .build();
        this.wekaService = wekaService;
    }

    public void syncRecentHistory() {
        log.info("🎞️ Syncing 30-day Solar History for machine learning...");
        webClient.get()
                .uri(HISTORY_URL)
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(this::parseAndTrainHistory, 
                        e -> log.error("❌ Failed to fetch historical solar data: {}", e.getMessage()));
    }

    private void parseAndTrainHistory(String text) {
        if (text == null) return;

        // Pattern for 2024 03 25  16 ... (Date followed by multiple indices)
        // We want Estimated Planetary A (usually the 7th numeric column)
        String[] lines = text.split("\n");
        int count = 0;
        
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(":")) continue;

            try {
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length >= 8) {
                    // Approximate columns: YYYY MM DD A_ Fredericksburg A_ College A_ Planetary
                    // Based on NOAA format, Planetary A is often index 7 or 8
                    double apIndex = Double.parseDouble(tokens[13]); // Based on DGD format for Estimated Planetary A
                    
                    // We feed this to Weka as training samples for "Weather" reliability
                    // Feature vector: forecastProb=0.5 (avg), tempDelta=0.0, humidityDelta=0.0, vol=solarX
                    double solarX = apIndex >= 40 ? 2.0 : (apIndex >= 25 ? 1.5 : (apIndex >= 15 ? 1.2 : 1.0));
                    
                    // actualOutcome is simulated as high reliability for low solar noise
                    double mockOutcome = (solarX > 1.5) ? 0.4 : 0.9; 
                    
                    wekaService.addSample("Weather", 0.5, 0.0, 0.0, solarX, mockOutcome);
                    count++;
                }
            } catch (Exception e) {
                // Skip malformed lines
            }
        }
        
        // Inject some baseline diversity to avoid "single class" Weka error
        wekaService.addSample("Weather", 0.9, 0.1, 0.1, 0.2, 0.98); 
        wekaService.addSample("Weather", 0.1, 0.9, 0.9, 2.0, 0.10); 

        log.info("🧠 Brain upgraded with {} historical solar samples. Rebuilding ensemble...", count);
        wekaService.buildAndSave("Weather");
    }
}
