package com.bot.spreadengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Provides real-time and historical precipitation data for international cities
 * via the Open-Meteo API (free, no API key required, global coverage).
 *
 * Markets currently tracked:
 *   - Ankara, Turkey  (lat 39.93, lon 32.86)
 *   - Tokyo, Japan    (lat 35.68, lon 139.69)
 */
@Slf4j
@Service
public class InternationalWeatherService {

    private final WebClient webClient;

    private static final String FORECAST_BASE  = "https://api.open-meteo.com/v1/forecast";
    private static final String ARCHIVE_BASE   = "https://archive-api.open-meteo.com/v1/archive";

    public enum City {
        ANKARA("Ankara",  39.9334, 32.8597),
        TOKYO ("Tokyo",   35.6762, 139.6503),
        NYC   ("NYC",     40.7128, -74.0060);

        public final String name;
        public final double lat;
        public final double lon;

        City(String name, double lat, double lon) {
            this.name = name;
            this.lat  = lat;
            this.lon  = lon;
        }
    }

    public InternationalWeatherService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .defaultHeader("User-Agent", "aiSpreadBot/1.0")
                .build();
    }

    /**
     * Returns today's max precipitation probability (0.0–1.0) for the given city.
     *
     * Open-Meteo response shape:
     * { "daily": { "precipitation_probability_max": [30] } }
     */
    @SuppressWarnings("unchecked")
    public Mono<Double> getPrecipitationProbability(City city) {
        String uri = String.format(
                "%s?latitude=%.4f&longitude=%.4f&daily=precipitation_probability_max&forecast_days=1&timezone=auto",
                FORECAST_BASE, city.lat, city.lon);

        return webClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> {
                    try {
                        Map<String, Object> daily = (Map<String, Object>) body.get("daily");
                        List<Number> probs = (List<Number>) daily.get("precipitation_probability_max");
                        if (probs != null && !probs.isEmpty() && probs.get(0) != null) {
                            double pct = probs.get(0).doubleValue();
                            log.info("🌧️ {} Precip Probability (OpenMeteo): {}%", city.name, pct);
                            return pct / 100.0;
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse OpenMeteo response for {}: {}", city.name, e.getMessage());
                    }
                    return 0.5;
                })
                .onErrorResume(e -> {
                    log.error("OpenMeteo fetch failed for {}: {}", city.name, e.getMessage());
                    return Mono.just(0.5);
                });
    }

    /**
     * Fetches daily precipitation totals (mm) for a city over a date range.
     * Used to build historical Weka training data.
     *
     * Returns a parallel array of {date, precipMm} entries.
     * Open-Meteo archive response shape:
     * { "daily": { "time": ["2024-01-01", ...], "precipitation_sum": [1.4, ...] } }
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<LocalDate, Double>> getHistoricalPrecipitation(City city, LocalDate start, LocalDate end) {
        String uri = String.format(
                "%s?latitude=%.4f&longitude=%.4f&start_date=%s&end_date=%s&daily=precipitation_sum&timezone=UTC",
                ARCHIVE_BASE, city.lat, city.lon, start, end);

        return webClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> {
                    Map<LocalDate, Double> result = new java.util.LinkedHashMap<>();
                    try {
                        Map<String, Object> daily = (Map<String, Object>) body.get("daily");
                        List<String> dates  = (List<String>) daily.get("time");
                        List<Number> precip = (List<Number>) daily.get("precipitation_sum");

                        if (dates != null && precip != null) {
                            for (int i = 0; i < dates.size(); i++) {
                                Number p = precip.get(i);
                                if (p != null) {
                                    result.put(LocalDate.parse(dates.get(i)), p.doubleValue());
                                }
                            }
                            log.info("📅 {} historical precip loaded: {} days", city.name, result.size());
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse OpenMeteo archive for {}: {}", city.name, e.getMessage());
                    }
                    return result;
                })
                .onErrorResume(e -> {
                    log.error("OpenMeteo archive fetch failed for {}: {}", city.name, e.getMessage());
                    return Mono.just(java.util.Collections.emptyMap());
                });
    }
}
