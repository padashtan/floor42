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
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.WorkflowEvent.WorkflowStepTicketOpened;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
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
 * Two approvals of one plan racing (ADR-0061): the tasks are opened by the approval, a move a mission makes once, inside
 * one {@link OfficeService} command, so the office's lock orders the two. The first approves and opens each task once;
 * the second, which waited for the lock, is refused and opens nothing: one link per task in the journal.
 */
class WorkflowTaskRaceTest {

    static final AgentId GALEN = new AgentId("galen");
    static final AgentId CHRIS = new AgentId("chris");
    static final Step.Task AUDIT = new Step.Task(
            "audit",
            "Audit",
            "Read it",
            "A second look",
            "Findings",
            Step.Task.Slot.BEFORE_ACCEPTANCE,
            WorkRole.REVIEWER,
            true);
    static final Workflow TASKED = new Workflow(
            "Tasked",
            List.of(
                    new Step.Plan(),
                    new Step.Approval(),
                    new Step.Produce(),
                    new Step.Review(),
                    AUDIT,
                    new Step.Accept()));

    final OfficeServiceTest.MemoryJournal journal = new OfficeServiceTest.MemoryJournal();
    final OfficeService office =
            new OfficeService(journal, Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC));

    CommandContext director() {
        return office.context(Actor.director(), Origin.REAL);
    }

    MissionId planned() {
        for (AgentId a : List.of(GALEN, CHRIS)) {
            office.execute(c -> c.staff()
                    .hire(director(), a, a.value(), "role", "anthropic", "claude-sonnet-5-5", "medium", List.of()));
        }
        MissionId m = ((MissionCreated)
                        office.execute(c -> c.missions().create(director(), "Race", "o", "C:/repo", TASKED))
                                .getFirst()
                                .event())
                .mission();
        office.execute(c -> c.tickets()
                .create(
                        office.context(Actor.agent(GALEN), Origin.REAL),
                        m,
                        "Work",
                        "",
                        Priority.NORMAL,
                        List.of(),
                        List.of("done")));
        office.execute(c -> c.missions().submitPlan(office.context(Actor.agent(GALEN), Origin.REAL), m));
        office.execute(c -> c.verification().setBudget(director(), m, null));
        return m;
    }

    @Test
    void twoApprovalsRacingOpenEachTaskOnce() throws Exception {
        MissionId m = planned();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Function<Company, List<EventEnvelope>> approve = c -> c.missions().approvePlan(director(), m);
        CompletableFuture<List<EventEnvelope>> one = CompletableFuture.supplyAsync(() -> office.execute(c -> {
            inside.countDown();
            try {
                assertThat(release.await(10, TimeUnit.SECONDS)).as("released").isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return approve.apply(c);
        }));
        assertThat(inside.await(10, TimeUnit.SECONDS))
                .as("the first holds the lock")
                .isTrue();
        AtomicReference<String> met = new AtomicReference<>();
        Thread two = new Thread(() -> {
            try {
                met.set(office.execute(approve).toString());
            } catch (DomainException e) {
                met.set(e.code());
            }
        });
        two.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (two.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.onSpinWait();
        assertThat(two.getState()).as("the second waits for the lock").isEqualTo(Thread.State.BLOCKED);
        release.countDown();
        one.get(10, TimeUnit.SECONDS);
        two.join(10_000);

        assertThat(met.get()).as("the second approval is refused").isEqualTo("INVALID_TRANSITION");
        List<String> links = journal.readAll().stream()
                .map(EventEnvelope::event)
                .filter(WorkflowStepTicketOpened.class::isInstance)
                .map(e -> ((WorkflowStepTicketOpened) e).step())
                .toList();
        assertThat(links).containsExactly("audit");
        TicketId opened = office.query(c -> c.workflows().linked(m).get("audit"));
        assertThat(opened).isNotNull();
        // Replaying the journal rebuilds the same link.
        Company replayed = Company.replay(journal.readAll());
        assertThat(replayed.workflows().linked(m)).containsEntry("audit", opened);
    }
}
