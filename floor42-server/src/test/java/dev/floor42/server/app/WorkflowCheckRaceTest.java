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
import dev.floor42.domain.ReviewVerdict;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.event.TicketEvent.TicketReviewed;
import dev.floor42.domain.workflow.MissionInterpreter;
import dev.floor42.domain.workflow.StepAt;
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
 * The checks' guard and the rejection cap are one check-then-act each (ADR-0052): the review command reads the
 * ticket's derived {@code (submission, position)} and its rejections, then records, inside one {@link OfficeService}
 * command, so the office's lock (its monitor: {@code execute} is {@code synchronized}) is the journal's single writer
 * that orders them. Each race is forced with latches: the first command holds the lock until the second thread is seen
 * blocked on it, and the second then sees what the first did.
 */
class WorkflowCheckRaceTest {

    static final AgentId GALEN = new AgentId("galen");
    static final AgentId CHRIS = new AgentId("chris");
    static final AgentId DARIUS = new AgentId("darius");
    static final AgentId ADA = new AgentId("ada");
    static final Step.Check SECURITY =
            new Step.Check("security-check", "Security check", "Look for secrets", "Contract", "Findings", true);
    static final Workflow CHECKED = new Workflow(
            "Checked",
            List.of(new Step.Approval(), new Step.Produce(), new Step.Review(), SECURITY, new Step.Accept()));

    final OfficeServiceTest.MemoryJournal journal = new OfficeServiceTest.MemoryJournal();
    final OfficeService office =
            new OfficeService(journal, Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC));
    final MissionInterpreter interpreter = new MissionInterpreter();

    CommandContext director() {
        return office.context(Actor.director(), Origin.REAL);
    }

    CommandContext agent(AgentId a) {
        return office.context(Actor.agent(a), Origin.REAL);
    }

    /** A REAL mission pinned to {@link #CHECKED}, its one ticket produced by Chris and submitted. */
    TicketId submitted() {
        for (AgentId a : List.of(GALEN, CHRIS, DARIUS, ADA)) {
            office.execute(c -> c.staff()
                    .hire(director(), a, a.value(), "role", "anthropic", "claude-sonnet-5-5", "medium", List.of()));
        }
        MissionId m = ((MissionCreated)
                        office.execute(c -> c.missions().create(director(), "Race", "o", "C:/repo", CHECKED))
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
        office.execute(c -> c.verification().setBudget(director(), m, null));
        office.execute(c -> c.missions().approvePlan(director(), m));
        office.execute(c -> c.tickets().start(agent(CHRIS), t));
        office.execute(c -> c.tickets().submitForReview(agent(CHRIS), t, "patch"));
        return t;
    }

    StepAt at(TicketId t) {
        return office.query(c -> interpreter
                .progress(c, c.tickets().find(t).orElseThrow())
                .orElseThrow()
                .at());
    }

    Function<Company, List<EventEnvelope>> verdict(TicketId t, AgentId by, ReviewVerdict v, StepAt at) {
        return c -> c.tickets().review(agent(by), t, v, "findings", List.of(), at);
    }

    Ticket ticket(TicketId t) {
        return office.query(c -> c.tickets().find(t).orElseThrow());
    }

    /** Same shape as {@link MissionArchiveRaceTest#race}: the second command's events, or its refusal code. */
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
                        .filter(n -> !n.equals("ReviewItemsRecorded"))
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

    long reviewsJournaled() {
        return journal.stored.stream()
                .filter(e -> e.event() instanceof TicketReviewed)
                .count();
    }

    @Test
    void twoSessionsAnsweringTheSamePositionRecordOneVerdict() throws Exception {
        TicketId t = submitted();
        StepAt review = at(t);

        String second = race(
                verdict(t, DARIUS, ReviewVerdict.VALIDATED, review), verdict(t, ADA, ReviewVerdict.REJECTED, review));

        assertThat(second).isEqualTo("STEP_MOVED_ON");
        assertThat(reviewsJournaled()).isEqualTo(1);
        assertThat(at(t)).isEqualTo(new StepAt(1, 1));
        assertThat(ticket(t).status()).isEqualTo(TicketStatus.IN_REVIEW);
    }

    @Test
    void aDirectiveThatReopensTheTicketWhileACheckRunsLeavesItsVerdictUnrecorded() throws Exception {
        TicketId t = submitted();
        office.execute(verdict(t, DARIUS, ReviewVerdict.VALIDATED, at(t)));
        StepAt check = at(t);
        long before = reviewsJournaled();

        String verdict = race(
                c -> c.workshop().giveDirective(director(), t, "Rename the module first"),
                verdict(t, ADA, ReviewVerdict.VALIDATED, check));

        assertThat(verdict).isEqualTo("STEP_MOVED_ON");
        assertThat(reviewsJournaled()).isEqualTo(before);
        assertThat(ticket(t).status()).isNotEqualTo(TicketStatus.DONE);
    }

    @Test
    void theThirdRejectionByACheckAsksTheDirectorOnceWhenTwoVerdictsRaceOnIt() throws Exception {
        TicketId t = submitted();
        for (int i = 0; i < 2; i++) {
            office.execute(verdict(t, DARIUS, ReviewVerdict.VALIDATED, at(t)));
            office.execute(verdict(t, ADA, ReviewVerdict.REJECTED, at(t)));
            office.execute(c -> c.tickets().submitForReview(agent(CHRIS), t, "patch"));
        }
        office.execute(verdict(t, DARIUS, ReviewVerdict.VALIDATED, at(t)));
        StepAt check = at(t);

        String second =
                race(verdict(t, ADA, ReviewVerdict.REJECTED, check), verdict(t, DARIUS, ReviewVerdict.REJECTED, check));

        assertThat(second).isEqualTo("STEP_MOVED_ON");
        long questions = office.query(
                c -> c.desk().open().stream().filter(q -> t.equals(q.ticket())).count());
        assertThat(questions)
                .as("one blocking question, never a fourth reopening")
                .isEqualTo(1);
        assertThat(ticket(t).status()).isEqualTo(TicketStatus.IN_REVIEW);
    }
}
