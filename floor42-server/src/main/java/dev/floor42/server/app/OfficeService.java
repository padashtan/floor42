package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Company;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.journal.JournalException;
import dev.floor42.server.journal.Unreadable;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The single entry point to the company. Commands are serialized; each one is checked by the domain, applied, then
 * stored atomically. If storing fails, the in-memory company is rebuilt from the journal so it never shows a state that
 * was not persisted.
 *
 * <p>Entities returned by the domain are live objects: read them only inside {@link #query}, and map them to views
 * there.
 *
 * <p>Subscribers receive each command's stored events, in journal order, while the lock is held. They must therefore
 * never block and never call back into this service synchronously.
 */
@Startup
@ApplicationScoped
public class OfficeService {

    private static final Logger LOG = Logger.getLogger(OfficeService.class);

    private final Journal journal;
    private final Clock clock;
    private final List<Consumer<List<EventEnvelope>>> subscribers = new CopyOnWriteArrayList<>();
    private Company company;
    /** Set when memory may hold events the journal lacks and the rebuild failed: nothing runs until a rebuild works. */
    private RuntimeException outOfSync;

    private volatile List<Unreadable> unreadable = List.of();
    /** The questions an agent's session waits on this moment (ADR-0059): only these can be asked back. */
    private final java.util.Set<dev.floor42.domain.QuestionId> waitedOn =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** In tests, a command that applied events and then failed throws an {@link AssertionError} after the rollback. */
    @Inject
    @ConfigProperty(name = "floor42.office.fail-on-rollback", defaultValue = "false")
    boolean failOnRollback;

    @Inject
    public OfficeService(Journal journal, Clock clock) {
        this.journal = journal;
        this.clock = clock;
        restore();
        LOG.infof(
                "Company restored from journal: %d events, %d missions",
                company.version(), company.missions().all().size());
        if (!unreadable.isEmpty()) {
            LOG.warnf(
                    "%d events of the journal could not be read and were left out: the state may be incomplete"
                            + " (the director sees the list)",
                    unreadable.size());
            for (Unreadable u : unreadable) {
                // Never the payload: an event may quote a command, a file or a token.
                LOG.warnf("  left out: #%d %s (%s) %s", u.sequence(), u.type(), u.reason(), u.detail());
            }
        }
    }

    /** Rebuilds the company from what is stored, leaving out what cannot be read (ADR-0042). */
    private void restore() {
        JournalReplay.Result r = JournalReplay.run(journal.replay());
        company = r.company();
        unreadable = r.unreadable();
    }

    /** The journal rows replay left out, by sequence: empty when the state is complete. */
    /** Marks the question as waited on by a live session until the returned handle is closed. */
    public Runnable waitOn(dev.floor42.domain.QuestionId question) {
        waitedOn.add(question);
        return () -> waitedOn.remove(question);
    }

    /** Whether a live session waits for the answer to this question. */
    public boolean isWaitedOn(dev.floor42.domain.QuestionId question) {
        return waitedOn.contains(question);
    }

    public List<Unreadable> unreadable() {
        return unreadable;
    }

    public CommandContext context(Actor actor, Origin origin) {
        return new CommandContext(actor, origin, clock.instant(), null);
    }

    public CommandContext context(Actor actor, Origin origin, String causationId) {
        return new CommandContext(actor, origin, clock.instant(), causationId);
    }

    public synchronized <T> T query(Function<Company, T> query) {
        return query.apply(company);
    }

    public synchronized List<EventEnvelope> execute(Function<Company, List<EventEnvelope>> command) {
        if (outOfSync != null) {
            try {
                restore();
                outOfSync = null;
            } catch (RuntimeException e) {
                // Handled: still unreadable, so the command is refused with the first failure kept
                e.addSuppressed(outOfSync);
                throw new JournalException("The company is out of sync with its journal: no command until it reads", e);
            }
        }
        long before = company.version();
        List<EventEnvelope> events;
        try {
            events = command.apply(company);
        } catch (RuntimeException e) {
            if (company.version() != before) rollBack(e);
            throw e;
        }
        try {
            journal.append(events);
        } catch (JournalException e) {
            LOG.error("Journal write failed; rebuilding the company from what is stored", e);
            rebuild(e);
            throw e;
        }
        if (!events.isEmpty()) publish(events);
        return events;
    }

    /**
     * A command failed after some of its events were applied (emitting applies at once): those events were never
     * journaled, so the company is rebuilt from what is stored. A command must check everything before it emits; this
     * net only keeps memory and journal equal when one does not, and fails loudly in tests so it never hides the bug.
     */
    private void rollBack(RuntimeException cause) {
        LOG.error(
                "A command failed after applying events it never stored; rebuilding the company from the journal",
                cause);
        rebuild(cause);
        if (failOnRollback) {
            throw new AssertionError(
                    "A command applied events then failed (" + cause + "): check before emitting", cause);
        }
    }

    /** Rebuilds after a failure; if that fails too, the cause is kept and every command is refused until it works. */
    private void rebuild(RuntimeException cause) {
        try {
            restore();
        } catch (RuntimeException rebuild) {
            // Handled: logged and attached to the cause, which is thrown by the caller
            LOG.error("Rebuilding the company from the journal failed too", rebuild);
            cause.addSuppressed(rebuild);
            outOfSync = cause;
        }
    }

    /** Returns a handle that unsubscribes when closed. */
    public AutoCloseable subscribe(Consumer<List<EventEnvelope>> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    private void publish(List<EventEnvelope> events) {
        for (Consumer<List<EventEnvelope>> s : subscribers) {
            try {
                s.accept(events);
            } catch (RuntimeException e) {
                // Handled: A failing subscriber (a closed socket) must not undo or block a stored command.
                LOG.warn("Event subscriber failed", e);
            }
        }
    }
}
