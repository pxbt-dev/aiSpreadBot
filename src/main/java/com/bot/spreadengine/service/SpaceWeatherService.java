package com.bot.spreadengine.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class SpaceWeatherService {

    private final WebClient webClient;
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("ddMMMyy", Locale.US);
    private static final String NOAA_URL = "https://services.swpc.noaa.gov/text/45-day-forecast.txt";

    public SpaceWeatherService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .build();
    }

    @Data
    public static class SolarForecast {
        private LocalDate date;
        private int ap;
        private int flux;
    }

    public Mono<List<SolarForecast>> fetchForecast() {
        return webClient.get()
                .uri(NOAA_URL)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseForecast)
                .doOnError(e -> log.error("❌ Failed to fetch Solar Forecast: {}", e.getMessage()))
                .onErrorReturn(new ArrayList<>());
    }

    private List<SolarForecast> parseForecast(String text) {
        List<SolarForecast> forecasts = new ArrayList<>();
        if (text == null) return forecasts;

        log.debug("📡 NOAA Raw Body (start): {}", text.substring(0, Math.min(text.length(), 500)));

        // Strip HTML tags if present (SWPC often wraps in <pre>)
        String cleanText = text.replaceAll("<[^>]*>", "");
        String[] lines = cleanText.split("\n");
        boolean inApSection = false;

        for (String line : lines) {
            line = line.trim();
            if (line.contains("45-DAY AP FORECAST")) {
                inApSection = true;
                continue;
            }
            if (line.contains("45-DAY F10.7 CM FLUX FORECAST")) {
                inApSection = false; // Stop AP parsing
                continue;
            }

            if (inApSection && !line.isEmpty() && !line.startsWith("#") && !line.startsWith(":")) {
                // Tokens are usually "DATE VALUE DATE VALUE..."
                String[] tokens = line.split("\\s+");
                for (int i = 0; i < tokens.length - 1; i += 2) {
                    try {
                        String dateStr = tokens[i];
                        String apStr = tokens[i + 1];
                        
                        // Basic validation that it looks like a date (e.g. 25Mar26)
                        if (dateStr.length() == 7) {
                            SolarForecast f = new SolarForecast();
                            f.setDate(LocalDate.parse(dateStr, dateFormatter));
                            f.setAp(Integer.parseInt(apStr));
                            forecasts.add(f);
                        }
                    } catch (Exception e) {
                        // Skip non-data tokens
                    }
                }
            }
        }
        log.info("☀️ Parsed {} solar forecast days. Max AP: {}", forecasts.size(), 
                forecasts.stream().mapToInt(SolarForecast::getAp).max().orElse(0));
        return forecasts;
    }

    /**
     * Returns a volatility multiplier based on current date's AP index
     */
    public double getVolatilityMultiplier(List<SolarForecast> forecasts) {
        LocalDate today = LocalDate.now();
        return forecasts.stream()
                .filter(f -> f.getDate().equals(today))
                .findFirst()
                .map(f -> {
                    if (f.getAp() >= 40) return 2.0;  // High Volatility
                    if (f.getAp() >= 25) return 1.5;  // Moderate Volatility
                    if (f.getAp() >= 15) return 1.2;  // Slight Volatility
                    return 1.0;
                }).orElse(1.0);
    }
}
