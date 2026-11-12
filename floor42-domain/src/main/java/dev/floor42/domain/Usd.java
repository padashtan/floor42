package dev.floor42.domain;

/**
 * Amounts of money are added and compared in whole micro-USD, never as doubles: {@code 0.1} has no exact double, and
 * ten sessions of 0.10 USD add up to 0.9999999999999999, under a 1.00 cap. Events and views keep their doubles; only
 * sums and comparisons go through here.
 */
public final class Usd {

    private static final double MICROS = 1_000_000.0;

    private Usd() {}

    /** The amount in micro-USD, rounded to the nearest. */
    public static long micros(double usd) {
        return Math.round(usd * MICROS);
    }

    /** Back to USD, for display and for the events that carry doubles. */
    public static double usd(long micros) {
        return micros / MICROS;
    }
}
