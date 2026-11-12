package dev.floor42.server.app;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.function.Supplier;

/**
 * The one lock under which tasks are chosen and agents reserved, for every mission: two threads never start the same
 * ticket, and an agent never runs two sessions, whichever missions ask.
 *
 * <p>Order: this lock first, then the {@link OfficeService} monitor (a query inside is fine). Never take this lock while
 * holding the office monitor: event subscribers run under it, so they fork through {@link MissionScheduler} instead.
 * Git and journal writes stay outside: the lock is held only to choose and reserve.
 */
@ApplicationScoped
public class DispatchLock {

    private final Object monitor = new Object();

    public <T> T holding(Supplier<T> work) {
        synchronized (monitor) {
            return work.get();
        }
    }
}
