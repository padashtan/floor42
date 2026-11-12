package dev.floor42.server.iam;

import io.quarkus.arc.ClientProxy;
import java.time.Clock;
import java.time.Duration;

/** Test hook: moves the clock of the step-up approvals (the field is package-private; the bean is proxied). */
public final class StepUpClock {

    private StepUpClock() {}

    public static void advance(StepUpApprovals approvals, Duration by) {
        StepUpApprovals real = ClientProxy.unwrap(approvals);
        real.clock = Clock.offset(Clock.systemUTC(), by);
    }

    public static void reset(StepUpApprovals approvals) {
        ClientProxy.unwrap(approvals).clock = Clock.systemUTC();
    }
}
