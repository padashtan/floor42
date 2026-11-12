package dev.floor42.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * Linear-time check for a text reader: a warm-up, then the time for 10000 and for 40000 characters (best of 3 each).
 * The 40000 run must stay under 10 s (the quadratic bugs found took 18 s and more) and under 8 times the 10000 run, plus
 * a floor for timer noise. A tight absolute budget would trip on a loaded gate, a growth ratio does not.
 */
public final class Scaling {

    private Scaling() {}

    /** {@code unit} repeated up to {@code chars} characters, after {@code prefix}. */
    public static String of(String prefix, String unit, int chars) {
        return prefix + unit.repeat(Math.max(1, chars / unit.length()));
    }

    public static void linear(String what, String prefix, String unit, Consumer<String> read) {
        for (int i = 0; i < 3; i++) read.accept(of(prefix, unit, 2_000));
        Duration small = best(of(prefix, unit, 10_000), read);
        Duration big = best(of(prefix, unit, 40_000), read);
        assertThat(big).as("%s: 40000 characters", what).isLessThan(Duration.ofSeconds(10));
        assertThat(big.toMillis())
                .as("%s: 40000 characters took %s, 10000 took %s", what, big, small)
                .isLessThanOrEqualTo(8 * small.toMillis() + 150);
    }

    private static Duration best(String input, Consumer<String> read) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t = System.nanoTime();
            read.accept(input);
            best = Math.min(best, System.nanoTime() - t);
        }
        return Duration.ofNanos(best);
    }
}
