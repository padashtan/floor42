package dev.floor42.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

/**
 * A time budget for a fuzz target, measured after the call: {@code assertTimeoutPreemptively} starts a thread per
 * input, which divides the fuzzer's speed by ten. A call that never returns is stopped by libFuzzer's own timeout.
 */
public final class FuzzBudget {

    private FuzzBudget() {}

    public static void within(Duration budget, Runnable call) {
        long start = System.nanoTime();
        call.run();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("time taken").isLessThan(budget);
    }
}
