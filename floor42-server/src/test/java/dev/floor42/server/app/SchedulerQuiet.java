package dev.floor42.server.app;

import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * What tests wait for instead of sleeping when they prove that nothing starts: the {@link MissionScheduler} runs no
 * background task (and so has none about to start). A loop that never ends fails here, with the tasks still running.
 */
public final class SchedulerQuiet {

    /** Longer than any fake session the live tests start ({@code [fake:slow=10]}). */
    public static final Duration PATIENCE = Duration.ofSeconds(60);

    private SchedulerQuiet() {}

    /**
     * Shows every fork to the probe as (calling thread, task) until {@code null} is set again: how a test sees the
     * last fork of a thread the scheduler does not track (a staffing hire, for instance).
     */
    public static void probe(MissionScheduler scheduler, BiConsumer<String, String> probe) {
        scheduler.probeForks(probe);
    }

    /** The tasks the filter counts that run now, without waiting. */
    public static List<String> running(MissionScheduler scheduler, Predicate<String> counted)
            throws InterruptedException {
        return scheduler.awaitQuiet(Duration.ZERO, counted);
    }

    /** The wake-ups not yet due, by name. */
    public static java.util.Set<String> wakeUps(MissionScheduler scheduler) {
        return scheduler.wakeUps();
    }

    /** The name of a mission's wake-up after its plan's window resets. */
    public static String planReset(dev.floor42.domain.MissionId m) {
        return HoldRulings.wakeUpOf(m);
    }

    /** Waits up to {@code within} for the named task to end; returns it if it still runs then. */
    public static List<String> busyAfter(MissionScheduler scheduler, Duration within, String name)
            throws InterruptedException {
        return scheduler.awaitQuiet(within, name::equals);
    }

    /** Waits until no background task runs at all. */
    public static void await(MissionScheduler scheduler) throws InterruptedException {
        await(scheduler, name -> true);
    }

    /** Waits until no background task the filter counts runs. */
    public static void await(MissionScheduler scheduler, Predicate<String> counted) throws InterruptedException {
        List<String> busy = scheduler.awaitQuiet(PATIENCE, counted);
        if (!busy.isEmpty()) throw new AssertionError("The dispatcher is still busy after " + PATIENCE + ": " + busy);
    }
}
