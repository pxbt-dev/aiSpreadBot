package com.bot.spreadengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-market and per-strategy performance over the session.
 *
 * <p>Learning mechanism:
 * <ul>
 *   <li>Each closed position is recorded (ticker → wins, trades, totalPnL).</li>
 *   <li>After 5+ trades the win rate drives a score multiplier fed back into the
 *       scanner — good markets get boosted, losers get penalised.</li>
 *   <li>Any stop-loss triggers a 30-minute cooldown: the market is excluded from
 *       emitTrade() selection while the cooldown is active.</li>
 * </ul>
 */
@Service
@Slf4j
public class PerformanceTracker {

    private static final int    MIN_TRADES_FOR_SIGNAL = 5;
    private static final long   COOLDOWN_MS           = 30 * 60 * 1000L; // 30 min
    /**
     * UCB exploration constant. Higher → more weight on under-explored markets.
     * 0.5 is a conservative default that balances exploit vs explore.
     */
    private static final double UCB_C                 = 0.5;

    public record MarketStats(int trades, int wins, double totalPnL, long cooldownUntil) {
        public double winRate()    { return trades == 0 ? 0.0 : (double) wins / trades; }
        public boolean onCooldown(){ return System.currentTimeMillis() < cooldownUntil;  }
        /** Minutes remaining on cooldown (0 if not on cooldown). */
        public long cooldownMinutes() {
            long ms = cooldownUntil - System.currentTimeMillis();
            return ms > 0 ? ms / 60_000 : 0;
        }
    }

    /** question/ticker → lifetime stats for this session */
    private final ConcurrentHashMap<String, MarketStats> stats = new ConcurrentHashMap<>();

    /** Per-strategy session totals {strategy → [wins, trades, totalPnL]} */
    private final ConcurrentHashMap<String, double[]> strategyStats = new ConcurrentHashMap<>();

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Record a closed position.
     *
     * @param ticker    short display name for the market
     * @param pnl       realised PnL for this close (signed, dollars)
     * @param strategy  strategy tag (MARKET_MAKING, WEATHER_ARB, …)
     * @param isStopLoss true → apply 30-min cooldown
     */
    public void record(String ticker, double pnl, String strategy, boolean isStopLoss) {
        long newCooldown = isStopLoss ? System.currentTimeMillis() + COOLDOWN_MS : 0L;

        stats.merge(ticker,
            new MarketStats(1, pnl > 0 ? 1 : 0, pnl, newCooldown),
            (old, n) -> new MarketStats(
                old.trades() + 1,
                old.wins()   + (pnl > 0 ? 1 : 0),
                old.totalPnL() + pnl,
                isStopLoss ? newCooldown : old.cooldownUntil()
            ));

        // Strategy aggregation
        strategyStats.merge(strategy == null ? "MARKET_MAKING" : strategy,
            new double[]{pnl > 0 ? 1 : 0, 1, pnl},
            (old, n) -> new double[]{old[0] + n[0], old[1] + 1, old[2] + pnl});

        if (isStopLoss) {
            log.warn("📚 LEARN: '{}' stop-lossed — 30-min cooldown applied", ticker);
        } else {
            MarketStats s = stats.get(ticker);
            log.debug("📚 LEARN: '{}' pnl={} | winRate={}%",
                ticker, String.format("%.4f", pnl),
                String.format("%.0f", s.winRate() * 100));
        }
    }

    /** Returns true if this market is currently in its post-stop-loss cooldown. */
    public boolean isOnCooldown(String ticker) {
        MarketStats s = stats.get(ticker);
        return s != null && s.onCooldown();
    }

    /**
     * UCB (Upper Confidence Bound) score multiplier for the scanner.
     *
     * <p>score_i = estimatedValue_i + C × √(ln(N+1) / n_i)
     * <ul>
     *   <li>estimatedValue_i = win rate for market i (0–1)</li>
     *   <li>N = total trades across all markets (global pull count)</li>
     *   <li>n_i = trades for this market (arm pull count)</li>
     * </ul>
     * Markets with few trades get a large exploration bonus, pushing them to the top
     * until enough data accumulates. Markets with high win rates are exploited.
     * Output is clamped to [0.30, 1.50].
     */
    public double getScoreMultiplier(String ticker) {
        int N = stats.values().stream().mapToInt(MarketStats::trades).sum();
        MarketStats s = stats.get(ticker);

        // Market never traded — give exploration bonus proportional to how much
        // we've traded elsewhere; neutral when there's no global data yet.
        if (s == null || s.trades() == 0) {
            if (N == 0) return 1.0;
            double bonus = UCB_C * Math.sqrt(Math.log(N + 1.0));
            return Math.min(1.50, 1.0 + Math.min(0.50, bonus));
        }

        int n = s.trades();
        double estimatedValue  = s.winRate();                             // [0, 1]
        double explorationTerm = UCB_C * Math.sqrt(Math.log(N + 1.0) / n);
        double ucb = estimatedValue + explorationTerm;                    // typically [0, ~2]

        // Linear map: ucb=0 → 0.30, ucb=1.0 → 1.10, ucb=1.5 → 1.50
        double multiplier = 0.30 + (ucb / 1.5) * 1.20;
        double result = Math.max(0.30, Math.min(1.50, multiplier));

        if (n >= MIN_TRADES_FOR_SIGNAL) {
            log.debug("📊 UCB [{}]: wr={:.2f} explo={:.3f} ucb={:.3f} → x{:.2f}",
                ticker, estimatedValue, explorationTerm, ucb, result);
        }
        return result;
    }

    /** Snapshot of all market stats for broadcast to the UI. */
    public Map<String, MarketStats> getAllStats() {
        return Collections.unmodifiableMap(stats);
    }

    /** Per-strategy aggregates for the analytics panel. */
    public Map<String, double[]> getStrategyStats() {
        return Collections.unmodifiableMap(strategyStats);
    }
}
