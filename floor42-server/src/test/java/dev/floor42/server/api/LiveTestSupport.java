package dev.floor42.server.api;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

public final class LiveTestSupport {

    private LiveTestSupport() {}

    /** Polls until the condition holds; fails with the description after the timeout. */
    public static void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) throw new AssertionError("Timed out waiting for: " + what);
            Thread.sleep(20);
        }
    }
}
