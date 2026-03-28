package com.bot.spreadengine.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class PositionService {

    private final Map<String, Position> openPositions = new ConcurrentHashMap<>();
    private final java.util.List<TradeRecord> tradeHistory = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private double bankroll = 16.69;
    private double totalProfit = 0.0;
    private double totalVolume = 0.0;
    private int totalTrades = 0;

    @Data
    public static class Position {
        private String tokenId;
        private String ticker;
        private String side;
        private int size;
        private double entryPrice;
        private double lastPrice;
        private double pnl;
    }

    @Data
    @lombok.AllArgsConstructor
    public static class TradeRecord {
        private String timestamp;
        private String asset;
        private String side;
        private int qty;
        private double price;
        private double realizedPnL;
    }

    public synchronized void addTrade(String tokenId, String ticker, String side, int qty, double fillPrice) {
        Position pos = openPositions.getOrDefault(tokenId, new Position());
        pos.setTokenId(tokenId);
        pos.setTicker(ticker);
        
        int oldSize = pos.getSize();
        double oldEntry = pos.getEntryPrice();
        boolean isBuy = side.equalsIgnoreCase("BUY") || side.equalsIgnoreCase("BID");
        
        double realizedTradePnL = 0.0;

        // Convert quantity to signed: BUY is positive, SELL is negative
        int tradeQty = isBuy ? qty : -qty;
        int newSize = oldSize + tradeQty;
        
        // Handle Bankroll and Realized Profit
        double tradeValue = qty * fillPrice;
        totalVolume += tradeValue;
        if (isBuy) {
            bankroll -= tradeValue;
            // Realized profit logic (if we were short)
            if (oldSize < 0) {
                int closingQty = Math.min(qty, Math.abs(oldSize));
                realizedTradePnL = closingQty * (oldEntry - fillPrice);
                totalProfit += realizedTradePnL;
                log.info("💰 REALIZED PROFIT (SHORT COVER): ${} on {}", 
                    String.format("%.4f", realizedTradePnL), ticker);
            }
        } else {
            bankroll += tradeValue;
            // Realized profit logic (if we were long)
            if (oldSize > 0) {
                int closingQty = Math.min(qty, oldSize);
                realizedTradePnL = closingQty * (fillPrice - oldEntry);
                totalProfit += realizedTradePnL;
                log.info("💰 REALIZED PROFIT: ${} on {} (Price: {}, Entry: {})", 
                    String.format("%.4f", realizedTradePnL), ticker, fillPrice, oldEntry);
            }
        }
        
        tradeHistory.add(new TradeRecord(
            java.time.Instant.now().toString(),
            ticker,
            side.toUpperCase(),
            qty,
            fillPrice,
            realizedTradePnL
        ));

        totalTrades++;

        if (newSize == 0) {
            openPositions.remove(tokenId);
        } else {
            // Update Entry Price for the REMAINING or NEW position
            if (oldSize == 0 || (oldSize > 0 && newSize > 0) || (oldSize < 0 && newSize < 0)) {
                // Same side: Weighted average
                double totalCost = Math.abs(oldSize * oldEntry) + tradeValue;
                pos.setEntryPrice(totalCost / Math.abs(newSize));
            } else {
                // Side flip: Entry is the new trade price
                pos.setEntryPrice(fillPrice);
            }
            
            pos.setSize(Math.abs(newSize));
            pos.setSide(newSize > 0 ? "BUY" : "SELL");
            pos.setLastPrice(fillPrice);
            pos.setPnl((fillPrice - pos.getEntryPrice()) * newSize);
            openPositions.put(tokenId, pos);
        }
    }

    public void updateMarkPrice(String tokenId, double mid) {
        Position pos = openPositions.get(tokenId);
        if (pos != null) {
            pos.setLastPrice(mid);
            pos.setPnl((mid - pos.getEntryPrice()) * pos.getSize());
        }
    }

    public java.util.List<Position> getPositions() {
        return new java.util.ArrayList<>(openPositions.values());
    }

    public double getBankroll() { return bankroll; }
    public double getTotalProfit() { return totalProfit; }
    public double getTotalVolume() { return totalVolume; }
    public int getTotalTrades() { return totalTrades; }
    public java.util.List<TradeRecord> getTradeHistory() {
        return new java.util.ArrayList<>(tradeHistory);
    }

    public Map<String, Position> getPositionMap() { return openPositions; }
    public void resetSession() {
        openPositions.clear();
        tradeHistory.clear();
        this.totalProfit = 0.0;
        this.totalVolume = 0.0;
        this.totalTrades = 0;
        this.bankroll = 16.69;
        log.info("🧹 Session Reset: Clean Slate Initialized.");
    }
}
