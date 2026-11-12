package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The scheduler on its own: what it runs, what it schedules, and what happens at shutdown. */
class MissionSchedulerTest {

    final MissionScheduler scheduler = new MissionScheduler();

    @Test
    void nothingIsForkedNorScheduledOnceTheDaemonStops() throws Exception {
        scheduler.stop(null);
        CountDownLatch never = new CountDownLatch(1);
        AtomicBoolean ran = new AtomicBoolean();
        scheduler.fork("late", () -> {
            ran.set(true);
            await(never);
        });
        scheduler.wakeUp("later", Instant.now(), () -> ran.set(true));

        // A task started would stay registered on the latch: nothing is, nothing ran.
        assertThat(scheduler.awaitQuiet(Duration.ZERO, n -> true)).isEmpty();
        assertThat(scheduler.wakeUps()).isEmpty();
        assertThat(ran).isFalse();
    }

    @Test
    void shutdownGivesTheWorkUnderWayItsGrace() throws Exception {
        AtomicBoolean done = new AtomicBoolean();
        scheduler.fork("short", () -> {
            sleep(300);
            done.set(true);
        });
        scheduler.stop(null);
        assertThat(done)
                .as("the work under way ended before the journal closes")
                .isTrue();
    }

    @Test
    void theGraceIsBoundedAndTheWorkIsNotInterrupted() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        scheduler.fork("stuck", () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        Instant before = Instant.now();
        scheduler.stop(null);
        Duration took = Duration.between(before, Instant.now());
        assertThat(took).isGreaterThanOrEqualTo(MissionScheduler.GRACE.minusMillis(50));
        assertThat(took).isLessThan(MissionScheduler.GRACE.plusSeconds(2));
        assertThat(scheduler.awaitQuiet(Duration.ZERO, n -> true)).containsExactly("stuck");
        release.countDown();
        assertThat(scheduler.awaitQuiet(Duration.ofSeconds(5), n -> true)).isEmpty();
        assertThat(interrupted)
                .as("a session cut here would be recorded as failed")
                .isFalse();
    }

    @Test
    void aWakeUpRunsWhenDueAndOneNameHoldsOneWakeUp() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        scheduler.wakeUp("plan-reset-M-1", Instant.now().plusSeconds(3600), runs::incrementAndGet);
        scheduler.wakeUp("plan-reset-M-1", Instant.now().plusMillis(100), runs::incrementAndGet);
        assertThat(scheduler.wakeUps()).containsExactly("plan-reset-M-1");
        // Counted as about to run: a quiet check waits for it, then for the task it became.
        assertThat(scheduler.awaitQuiet(Duration.ofSeconds(5), n -> true)).isEmpty();
        assertThat(runs).as("the replaced wake-up never runs").hasValue(1);
        assertThat(scheduler.wakeUps()).isEmpty();
        scheduler.stop(null);
    }

    @Test
    void aWakeUpNotYetDueIsDroppedAtShutdown() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        scheduler.wakeUp("plan-reset-M-2", Instant.now().plusMillis(300), () -> ran.set(true));
        scheduler.stop(null);
        assertThat(scheduler.wakeUps()).isEmpty();
        sleep(600);
        assertThat(ran).as("the next start's recovery schedules it again").isFalse();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
