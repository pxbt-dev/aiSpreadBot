package com.bot.spreadengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.DoubleNum;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Slf4j
@Service
public class TechnicalAnalysisService {

    /**
     * Analyzes Order Book Depth using ta4j indicators
     */
    public Map<String, Double> analyzeOrderDepth(String market, List<Double[]> priceHistory) {
        BarSeries series = new BaseBarSeriesBuilder()
                .withName(market)
                .withNumTypeOf(DoubleNum.class)
                .build();

        long now = System.currentTimeMillis();
        for (int i = 0; i < priceHistory.size(); i++) {
            Double[] pt = priceHistory.get(i); // [price, volume]
            ZonedDateTime time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now - (priceHistory.size() - i) * 1000L), ZoneId.systemDefault());
            // Using price as OHLC for simplicity in depth analysis
            series.addBar(time, pt[0], pt[0], pt[0], pt[0], pt[1]);
        }

        ClosePriceIndicator close = new ClosePriceIndicator(series);
        RSIIndicator rsi = new RSIIndicator(close, Math.min(series.getBarCount(), 14));
        
        // MACD
        MACDIndicator macd = new MACDIndicator(close, 12, 26);
        
        // Bollinger Bands
        BollingerBandsMiddleIndicator middle = new BollingerBandsMiddleIndicator(close);
        StandardDeviationIndicator sd = new StandardDeviationIndicator(close, 20);
        BollingerBandsUpperIndicator upper = new BollingerBandsUpperIndicator(middle, sd);
        BollingerBandsLowerIndicator lower = new BollingerBandsLowerIndicator(middle, sd);

        int lastIdx = series.getEndIndex();
        Map<String, Double> results = new HashMap<>();
        results.put("rsi", rsi.getValue(lastIdx).doubleValue());
        results.put("macd", macd.getValue(lastIdx).doubleValue());
        results.put("bbUpper", upper.getValue(lastIdx).doubleValue());
        results.put("bbLower", lower.getValue(lastIdx).doubleValue());
        results.put("currentPrice", close.getValue(lastIdx).doubleValue());

        log.debug("📊 TA for {}: RSI={}, MACD={}, BB_UP={}", market, results.get("rsi"), results.get("macd"), results.get("bbUpper"));
        return results;
    }
}
