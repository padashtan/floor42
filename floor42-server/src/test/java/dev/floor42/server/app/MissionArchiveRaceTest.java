package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Company;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionArchived;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The archive's check-then-act (ADR-0054) is one command, so {@link OfficeService}'s lock (its monitor: {@code execute}
 * is {@code synchronized}) protects it: a command that starts a session or convenes a retrospective, holding that lock,
 * keeps the archive waiting, and the archive then sees what it did; an archive holding it keeps them waiting, and they
 * then see the mission archived. Each race is forced with latches: the first command holds the lock until the second
 * thread is seen blocked on it.
 */
class MissionArchiveRaceTest {

    static final AgentId GALEN = new AgentId("galen");
    static final AgentId CHRIS = new AgentId("chris");

    final OfficeServiceTest.MemoryJournal journal = new OfficeServiceTest.MemoryJournal();
    final OfficeService office =
            new OfficeService(journal, Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC));

    CommandContext director() {
        return office.context(Actor.director(), Origin.SIMULATED);
    }

    CommandContext system() {
        return office.context(Actor.system(), Origin.SIMULATED);
    }

    CommandContext agent(AgentId a) {
        return office.context(Actor.agent(a), Origin.SIMULATED);
    }

    /** A mission approved, its one ticket started by Chris. */
    MissionId running() {
        for (AgentId a : List.of(GALEN, CHRIS)) {
            if (office.query(c -> c.staff().find(a).isPresent())) continue;
            office.execute(c -> c.staff()
                    .hire(director(), a, a.value(), "role", "anthropic", "claude-sonnet-5-5", "medium", List.of()));
        }
        MissionId m =
                (MissionId) ((MissionCreated) office.execute(c -> c.missions().create(director(), "Race", "o"))
                                .getFirst()
                                .event())
                        .mission();
        TicketId t = ((TicketCreated) office.execute(c -> c.tickets()
                                .create(agent(GALEN), m, "Work", "", Priority.NORMAL, List.of(), List.of("done")))
                        .getFirst()
                        .event())
                .ticket();
        office.execute(c -> c.tickets().assign(director(), t, CHRIS));
        office.execute(c -> c.missions().submitPlan(agent(GALEN), m));
        office.execute(c -> c.missions().approvePlan(director(), m));
        office.execute(c -> c.tickets().start(agent(CHRIS), t));
        return m;
    }

    boolean archived(MissionId m) {
        return office.query(c -> c.missions().find(m).orElseThrow().archived());
    }

    TicketId ticket(MissionId m) {
        return office.query(c -> c.tickets().of(m).getFirst().id());
    }

    /** Ended: Chris worked on it, then the director closed it (a cancelled mission holds no retrospective). */
    MissionId ended() {
        MissionId m = running();
        String s = "s-" + m.value();
        office.execute(c -> c.sessions().start(system(), s, ticket(m), CHRIS, "claude-sonnet-5-5", "2.1"));
        office.execute(c -> c.sessions().end(system(), s, false, "completed", 3, 10L, 10L, 0.02));
        office.execute(c -> c.tickets().cancel(director(), ticket(m), "not needed"));
        office.execute(c -> c.missions().complete(director(), m, "over"));
        return m;
    }

    /**
     * Runs {@code first} on a thread that holds the office's lock until {@code second}, on another thread, is seen
     * blocked on that lock; then lets {@code first} finish. Returns what {@code second} met: its events, or its
     * refusal code.
     */
    String race(Function<Company, List<EventEnvelope>> first, Function<Company, List<EventEnvelope>> second)
            throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<List<EventEnvelope>> one = CompletableFuture.supplyAsync(() -> office.execute(c -> {
            inside.countDown();
            try {
                assertThat(release.await(10, TimeUnit.SECONDS)).as("released").isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return first.apply(c);
        }));
        assertThat(inside.await(10, TimeUnit.SECONDS))
                .as("the first command holds the lock")
                .isTrue();
        AtomicReference<String> met = new AtomicReference<>();
        Thread two = new Thread(() -> {
            try {
                met.set(office.execute(second).stream()
                        .map(e -> e.event().getClass().getSimpleName())
                        .toList()
                        .toString());
            } catch (DomainException e) {
                met.set(e.code());
            }
        });
        two.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (two.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.onSpinWait();
        assertThat(two.getState())
                .as("the second command waits for the office's lock")
                .isEqualTo(Thread.State.BLOCKED);
        assertThat(met.get()).as("nothing done while the lock is held").isNull();
        release.countDown();
        one.get(10, TimeUnit.SECONDS);
        two.join(10_000);
        return met.get();
    }

    @Test
    void anArchiveHoldingTheLockLeavesNoRetrospectiveToConvene() throws Exception {
        MissionId m = ended();

        String retro = race(
                c -> c.missions().archive(director(), m),
                c -> c.retros().open(system(), m, CHRIS, List.of(CHRIS), "facts"));

        assertThat(retro).isEqualTo("MISSION_ARCHIVED");
        boolean convened = office.query(c -> c.retros().find(m).isPresent());
        boolean archived = archived(m);
        assertThat(convened).isFalse();
        assertThat(archived).isTrue();
    }

    @Test
    void aRetrospectiveConvenedFirstHoldsTheArchiveBack() throws Exception {
        MissionId m = ended();

        String archive = race(
                c -> c.retros().open(system(), m, CHRIS, List.of(CHRIS), "facts"),
                c -> c.missions().archive(director(), m));

        assertThat(archive).isEqualTo("RETRO_UNDER_WAY");
        boolean archived = archived(m);
        assertThat(archived).isFalse();
    }

    @Test
    void aSessionStartedBeforeTheCancelHoldsTheArchiveUntilItsEndIsRecorded() throws Exception {
        MissionId m = running();
        TicketId t = ticket(m);
        // The dispatcher's start holds the lock while the director cancels: the cancel comes after, the session runs.
        String cancel = race(
                c -> c.sessions().start(system(), "s-race", t, CHRIS, "claude-sonnet-5-5", "2.1"),
                c -> c.missions().cancel(director(), m, "stop"));
        assertThat(cancel).isEqualTo("[TicketCancelled, MissionCancelled]");
        String refused = null;
        try {
            office.execute(c -> c.missions().archive(director(), m));
        } catch (DomainException e) {
            refused = e.code();
        }
        assertThat(refused).as("the session the cancel stops is still running").isEqualTo("SESSION_RUNNING");

        // Its end, recorded while the director archives: the archive waits for it, then sees the session over.
        String archive = race(
                c -> c.sessions().end(system(), "s-race", true, "interrupted", null, null, null, null),
                c -> c.missions().archive(director(), m));

        assertThat(archive).isEqualTo("[MissionArchived]");
        assertThat(journal.stored.stream().filter(e -> e.event() instanceof MissionArchived))
                .hasSize(1);
    }
}
