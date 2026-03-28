package com.bot.spreadengine.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Calculates lunar phase for any date using the standard astronomical Julian Day formula.
 * No API or CSV required — works for any historical or future date.
 */
@Slf4j
@Service
public class LunarService {

    // Verified new moon: January 6, 2000 at 18:14 UTC → Julian Day 2451550.259
    private static final double KNOWN_NEW_MOON_JD = 2451550.259;
    private static final double LUNAR_CYCLE_DAYS  = 29.530589;

    /**
     * Normalized lunar phase for today.
     * 0.0 = new moon, ~0.5 = full moon, 1.0 = next new moon
     */
    public double getCurrentPhase() {
        return getPhase(LocalDate.now());
    }

    /**
     * Normalized lunar phase for a specific date.
     */
    public double getPhase(LocalDate date) {
        double jd = toJulianDay(date);
        double elapsed = (jd - KNOWN_NEW_MOON_JD) % LUNAR_CYCLE_DAYS;
        if (elapsed < 0) elapsed += LUNAR_CYCLE_DAYS;
        return elapsed / LUNAR_CYCLE_DAYS;
    }

    /**
     * Days remaining until the next full moon.
     */
    public double getDaysToNextFullMoon() {
        double phase = getCurrentPhase();
        double daysToFull = (0.5 - phase) * LUNAR_CYCLE_DAYS;
        if (daysToFull < 0) daysToFull += LUNAR_CYCLE_DAYS;
        return daysToFull;
    }

    public String getPhaseName() {
        return phaseName(getCurrentPhase());
    }

    public String getPhaseName(double phase) {
        return phaseName(phase);
    }

    private String phaseName(double phase) {
        if (phase < 0.0625 || phase >= 0.9375) return "NEW_MOON";
        if (phase < 0.1875) return "WAXING_CRESCENT";
        if (phase < 0.3125) return "FIRST_QUARTER";
        if (phase < 0.4375) return "WAXING_GIBBOUS";
        if (phase < 0.5625) return "FULL_MOON";
        if (phase < 0.6875) return "WANING_GIBBOUS";
        if (phase < 0.8125) return "LAST_QUARTER";
        return "WANING_CRESCENT";
    }

    private double toJulianDay(LocalDate date) {
        int y = date.getYear();
        int m = date.getMonthValue();
        int d = date.getDayOfMonth();
        if (m <= 2) { y--; m += 12; }
        int a = y / 100;
        int b = 2 - a + a / 4;
        return Math.floor(365.25 * (y + 4716))
             + Math.floor(30.6001 * (m + 1))
             + d + b - 1524.5;
    }
}
