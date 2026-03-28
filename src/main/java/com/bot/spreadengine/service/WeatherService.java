package com.bot.spreadengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class WeatherService {

    private final WebClient webClient;

    // NOAA NWS gridpoint for NYC Central Park (OKX office, grid 33,37)
    private static final String NOAA_FORECAST_URL = "https://api.weather.gov/gridpoints/OKX/33,37/forecast";

    public WeatherService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .defaultHeader("User-Agent", "aiSpreadBot/1.0 (contact@bot.com)")
                .build();
    }

    public Mono<Map<String, Object>> getLiveForecast() {
        return webClient.get()
                .uri(NOAA_FORECAST_URL)
                .retrieve()
                .bodyToMono(Map.class)
                .map(map -> (Map<String, Object>) map)
                .onErrorResume(e -> {
                    log.error("Error fetching NOAA forecast: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Returns the real precipitation probability (0.0–1.0) from the current NOAA forecast.
     * Falls back to 0.5 if the API is unavailable.
     */
    @SuppressWarnings("unchecked")
    public Mono<Double> getPrecipitationProbability() {
        return getLiveForecast()
                .map(forecast -> {
                    try {
                        Map<String, Object> props = (Map<String, Object>) forecast.get("properties");
                        List<Map<String, Object>> periods = (List<Map<String, Object>>) props.get("periods");
                        if (periods != null && !periods.isEmpty()) {
                            Map<String, Object> precip =
                                    (Map<String, Object>) periods.get(0).get("probabilityOfPrecipitation");
                            if (precip != null && precip.get("value") != null) {
                                double pct = Double.parseDouble(precip.get("value").toString());
                                log.info("🌧️ NOAA NYC Precip Probability: {}%", pct);
                                return pct / 100.0;
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse NOAA precipitation probability: {}", e.getMessage());
                    }
                    return 0.5;
                })
                .defaultIfEmpty(0.5);
    }

    /**
     * Returns [precipProbability, temperatureCelsius, relativeHumidity] for the current period.
     * Used to build real Weka feature vectors.
     */
    @SuppressWarnings("unchecked")
    public Mono<double[]> getCurrentConditions() {
        return getLiveForecast()
                .map(forecast -> {
                    double precipProb = 0.5;
                    double tempC     = 15.0;
                    double humidity  = 0.5;
                    try {
                        Map<String, Object> props = (Map<String, Object>) forecast.get("properties");
                        List<Map<String, Object>> periods = (List<Map<String, Object>>) props.get("periods");
                        if (periods != null && !periods.isEmpty()) {
                            Map<String, Object> period = periods.get(0);

                            Map<String, Object> precip = (Map<String, Object>) period.get("probabilityOfPrecipitation");
                            if (precip != null && precip.get("value") != null)
                                precipProb = Double.parseDouble(precip.get("value").toString()) / 100.0;

                            Object temp = period.get("temperature");
                            if (temp != null)
                                tempC = (Double.parseDouble(temp.toString()) - 32.0) * 5.0 / 9.0;

                            Map<String, Object> rh = (Map<String, Object>) period.get("relativeHumidity");
                            if (rh != null && rh.get("value") != null)
                                humidity = Double.parseDouble(rh.get("value").toString()) / 100.0;
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse NOAA conditions: {}", e.getMessage());
                    }
                    return new double[]{precipProb, tempC, humidity};
                })
                .defaultIfEmpty(new double[]{0.5, 15.0, 0.5});
    }
}
