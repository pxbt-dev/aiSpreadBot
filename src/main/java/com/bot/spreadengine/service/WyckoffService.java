package com.bot.spreadengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class WyckoffService {

    public enum MarketPhase {
        ACCUMULATION, // Institutional buying
        DISTRIBUTION, // Institutional selling
        MARKUP,       // Trend up
        MARKDOWN,     // Trend down
        NEUTRAL
    }

    /**
     * Determines the market phase based on price and volume relationship
     */
    public MarketPhase detectPhase(List<Double[]> priceHistory) {
        if (priceHistory == null || priceHistory.size() < 20) return MarketPhase.NEUTRAL;

        // Simplified Heuristic:
        // 1. Calculate Average Price and Volume
        double avgPrice = priceHistory.stream().mapToDouble(p -> p[0]).average().orElse(0);
        double avgVol = priceHistory.stream().mapToDouble(p -> p[1]).average().orElse(0);

        Double[] current = priceHistory.get(priceHistory.size() - 1);
        Double[] previous = priceHistory.get(priceHistory.size() - 2);

        double priceChange = (current[0] - previous[0]) / previous[0];
        double volChange = (current[1] - avgVol) / avgVol;

        // Wyckoff "Effort vs Result"
        if (Math.abs(priceChange) < 0.001 && volChange > 0.5) {
            // High Volume, Low Price Movement = Absorbtion
            if (current[0] < avgPrice) return MarketPhase.ACCUMULATION;
            else return MarketPhase.DISTRIBUTION;
        }

        if (priceChange > 0.01 && volChange > 0.2) return MarketPhase.MARKUP;
        if (priceChange < -0.01 && volChange > 0.2) return MarketPhase.MARKDOWN;

        return MarketPhase.NEUTRAL;
    }

    /**
     * Detects a "Spring" (False breakdown used for accumulation)
     */
    public boolean detectSpring(List<Double[]> history) {
        if (history.size() < 10) return false;
        
        double minPrice = history.stream().limit(history.size()-1).mapToDouble(p -> p[0]).min().orElse(0);
        Double[] current = history.get(history.size() - 1);
        
        // Price dips below recent minimum but recovers quickly (or is in the process)
        return current[0] < minPrice && history.get(history.size()-1)[1] > 1.5; // High volume dip
    }
}
