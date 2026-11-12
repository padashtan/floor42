package dev.floor42.server.app;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import org.jboss.logging.Logger;

/**
 * Where the live missions' background work runs: always on a new virtual thread, never on the caller's. Event
 * reactions call it under the {@link OfficeService} monitor, and the work they start takes the dispatch lock: run on
 * the caller's thread, it would take the locks in the wrong order.
 *
 * <p>At shutdown nothing new starts, and the work under way is given a short grace to end before the journal closes.
 * It is not interrupted: a session cut here would be recorded as failed, while the next daemon records it as
 * interrupted and asks the director, as before.
 *
 * <p>Work due later (a mission resting until its plan's window resets) is a wake-up: one per name, kept by one timer
 * thread that only forks the work when it is due, so nothing sleeps on a thread a caller waits for. A wake-up is
 * dropped at shutdown: the journal still holds the answer that asked for it, and the next start's recovery schedules
 * it again.
 *
 * <p>This class is the one place that starts threads (ArchitectureTest): everything else forks through it.
 *
 * <p>Tests ask it whether the dispatcher is quiet ({@link #awaitQuiet}) instead of sleeping: a fork registers its task
 * before it returns, and a task registers the ones it starts before it ends, and a wake-up not yet due counts as busy,
 * so an empty set means that no task runs and none is about to start. The staffing review and its hires,
 * retrospectives, the demo and the practice mission fork through here too ({@code ArchitectureTest} refuses a raw
 * thread elsewhere); {@link #probeForks} shows which thread forked what.
 */
@ApplicationScoped
public class MissionScheduler {

    private static final Logger LOG = Logger.getLogger(MissionScheduler.class);

    /** How long shutdown waits for the work under way. */
    static final Duration GRACE = Duration.ofSeconds(5);

    private final Object guard = new Object();
    private final Set<Thread> running = ConcurrentHashMap.newKeySet();
    private final Map<String, ScheduledFuture<?>> wakeUps = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("floor42-wake-ups").factory());
    private boolean closed;
    private volatile BiConsumer<String, String> forkProbe;

    /** Starts the work on its own virtual thread; false (nothing started) once the daemon stops. */
    public boolean fork(String name, Runnable work) {
        BiConsumer<String, String> probe = forkProbe;
        if (probe != null) probe.accept(Thread.currentThread().getName(), name);
        synchronized (guard) {
            return forkLocked(name, work);
        }
    }

    private boolean forkLocked(String name, Runnable work) {
        if (closed) {
            LOG.debugf("Not started, the daemon is stopping: %s", name);
            return false;
        }
        Thread thread = Thread.ofVirtual()
                .name(name)
                .uncaughtExceptionHandler(MissionScheduler::failed)
                .unstarted(() -> runTracked(work));
        running.add(thread);
        thread.start();
        return true;
    }

    /**
     * Forks the work at {@code at} (now if already past), under this name. A wake-up of the same name not yet due is
     * replaced: one per mission, whoever asked last. Ignored once the daemon stops.
     */
    public void wakeUp(String name, Instant at, Runnable work) {
        synchronized (guard) {
            if (closed) {
                LOG.debugf("Not scheduled, the daemon is stopping: %s", name);
                return;
            }
            ScheduledFuture<?> previous = wakeUps.remove(name);
            if (previous != null) previous.cancel(false);
            long delay = Math.max(0, Duration.between(Instant.now(), at).toMillis());
            ScheduledFuture<?>[] self = new ScheduledFuture<?>[1];
            self[0] = timer.schedule(() -> due(name, self[0], work), delay, TimeUnit.MILLISECONDS);
            wakeUps.put(name, self[0]);
        }
    }

    /**
     * The wake-up is due: it becomes a running task in one step, so a quiet check never sees neither. {@link #wakeUp}
     * held the guard until the future was stored, so {@code self} is the stored one here.
     */
    private void due(String name, ScheduledFuture<?> self, Runnable work) {
        synchronized (guard) {
            if (!wakeUps.remove(name, self)) return; // replaced or dropped meanwhile
            forkLocked(name, work);
        }
    }

    /** The wake-ups not yet due, by name: what the dispatcher is about to start later. */
    Set<String> wakeUps() {
        return Set.copyOf(wakeUps.keySet());
    }

    private void runTracked(Runnable work) {
        try {
            work.run();
        } finally {
            synchronized (guard) {
                running.remove(Thread.currentThread());
                guard.notifyAll();
            }
        }
    }

    /**
     * Test hook: from now on every fork is shown to the probe, as (name of the calling thread, name of the task),
     * on the calling thread, before the task starts; {@code null} removes it. Off in production (no probe).
     */
    void probeForks(BiConsumer<String, String> probe) {
        forkProbe = probe;
    }

    /**
     * Test hook: waits until no task the filter counts is running, for at most {@code within}. Returns the names of
     * the counted tasks still running then: empty when the scheduler went quiet. A task left out by the filter (one
     * that rests until a plan reset, for instance) may still run and start others; those are counted if it matches.
     */
    List<String> awaitQuiet(Duration within, Predicate<String> counted) throws InterruptedException {
        Instant until = Instant.now().plus(within);
        synchronized (guard) {
            while (true) {
                List<String> busy = java.util.stream.Stream.concat(
                                running.stream().map(Thread::getName), wakeUps.keySet().stream())
                        .filter(counted)
                        .sorted()
                        .toList();
                Duration rest = Duration.between(Instant.now(), until);
                if (busy.isEmpty() || rest.isNegative() || rest.isZero()) return busy;
                guard.wait(Math.max(1, rest.toMillis()));
            }
        }
    }

    private static void failed(Thread thread, Throwable e) {
        LOG.errorf(e, "Background work %s failed", thread.getName());
    }

    void stop(@Observes ShutdownEvent ignored) throws InterruptedException {
        List<Thread> left;
        synchronized (guard) {
            closed = true;
            left = new ArrayList<>(running);
            wakeUps.values().forEach(f -> f.cancel(false));
            if (!wakeUps.isEmpty())
                LOG.infof("%d wake-ups dropped: the next start schedules them again", wakeUps.size());
            wakeUps.clear();
            guard.notifyAll();
        }
        timer.shutdownNow();
        Instant until = Instant.now().plus(GRACE);
        for (Thread t : left) {
            Duration rest = Duration.between(Instant.now(), until);
            if (!rest.isNegative()) t.join(rest);
        }
        long still = left.stream().filter(Thread::isAlive).count();
        if (still > 0) LOG.infof("%d background tasks still ran at shutdown: the next start resumes them", still);
    }
}
