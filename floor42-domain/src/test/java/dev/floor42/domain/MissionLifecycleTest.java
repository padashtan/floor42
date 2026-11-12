package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.GALEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TicketEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(GoldenFixtureExtension.class)
class MissionLifecycleTest {

    final Fixture f = new Fixture();

    @Test
    void directorCreatesMissionInPlanningWithTheModeOfTheCommand() {
        MissionId m = f.mission();

        Mission mission = f.company.missions().find(m).orElseThrow();
        assertThat(mission.status()).isEqualTo(MissionStatus.PLANNING);
        assertThat(mission.origin()).isEqualTo(Origin.SIMULATED);
        assertThat(mission.id().value()).isEqualTo("M-1");
    }

    @Test
    void agentsCannotCreateMissions() {
        assertThatThrownBy(() -> f.company.missions().create(f.agent(GALEN), "x", "y"))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");
    }

    @Test
    void aPlanNeedsAtLeastOneTicket() {
        MissionId m = f.mission();

        assertThatThrownBy(() -> f.company.missions().submitPlan(f.agent(GALEN), m))
                .extracting("code")
                .isEqualTo("EMPTY_PLAN");
    }

    @Test
    void directorApprovesOrRejectsThePlan() {
        MissionId m = f.mission();
        f.ticket(m, "Analyse the flows");
        f.record(f.company.missions().submitPlan(f.agent(GALEN), m));

        f.record(f.company.missions().rejectPlan(f.director(), m, "Compare at least two gateways"));
        assertThat(f.company.missions().find(m).orElseThrow().status()).isEqualTo(MissionStatus.PLANNING);

        f.record(f.company.missions().submitPlan(f.agent(GALEN), m));
        f.record(f.company.missions().approvePlan(f.director(), m));
        assertThat(f.company.missions().find(m).orElseThrow().status()).isEqualTo(MissionStatus.IN_PROGRESS);
    }

    @Test
    void agentsCannotApproveTheirOwnPlan() {
        MissionId m = f.mission();
        f.ticket(m, "Analyse the flows");
        f.record(f.company.missions().submitPlan(f.agent(GALEN), m));

        assertThatThrownBy(() -> f.company.missions().approvePlan(f.agent(GALEN), m))
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");
    }

    @Test
    void missionCannotCompleteWhileTicketsAreOpen() {
        MissionId m = f.runningMission();

        assertThatThrownBy(() -> f.company.missions().complete(f.director(), m, "done"))
                .extracting("code")
                .isEqualTo("MISSION_NOT_FINISHED");
    }

    @Test
    void missionCompletesOnceEveryTicketIsValidated() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "AudienceFilter + tests"));
        f.record(f.company
                .tickets()
                .review(f.agent(Fixture.DARIUS), t, ReviewVerdict.VALIDATED, "48 tests green, reproduced"));

        f.record(f.company.missions().complete(f.director(), m, "POC delivered"));

        Mission mission = f.company.missions().find(m).orElseThrow();
        assertThat(mission.status()).isEqualTo(MissionStatus.COMPLETED);
        assertThat(mission.outcome()).isEqualTo("POC delivered");
    }

    @Test
    void aTicketKeepsWhenItWasCreatedStartedAndDone() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        Ticket started = f.company.tickets().find(t).orElseThrow();
        assertThat(started.createdAt()).isNotNull();
        assertThat(started.startedAt()).isAfter(started.createdAt());
        assertThat(started.endedAt()).isNull();

        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "report"));
        f.record(f.company.tickets().review(f.agent(Fixture.DARIUS), t, ReviewVerdict.VALIDATED, "ok"));

        Ticket done = f.company.tickets().find(t).orElseThrow();
        assertThat(done.status()).isEqualTo(TicketStatus.DONE);
        assertThat(done.endedAt()).isAfter(done.startedAt());
        assertThat(done.startedAt()).as("the first start stays").isEqualTo(started.startedAt());
    }

    @Test
    void cancellingCancelsEveryOpenTicketInTheSameCommand() {
        MissionId m = f.runningMission();
        TicketId todo = f.ticket(m, "A ticket waiting in the queue");
        TicketId already = f.ticket(m, "A ticket already cancelled");
        f.record(f.company.tickets().cancel(f.director(), already, "not needed"));
        f.record(f.company.missions().enableTicketValidation(f.director(), m));
        TicketId proposed = f.ticket(m, "A ticket awaiting the director");
        assertThat(f.company.tickets().find(proposed).orElseThrow().status()).isEqualTo(TicketStatus.PROPOSED);

        List<EventEnvelope> batch = f.record(f.company.missions().cancel(f.director(), m, "client changed scope"));

        assertThat(f.company.tickets().of(m)).allMatch(t -> t.status().isTerminal());
        assertThat(f.company.tickets().find(proposed).orElseThrow().status()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(f.company.tickets().find(todo).orElseThrow().status()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(batch.stream().map(e -> e.event().getClass().getSimpleName()))
                .containsExactly("TicketCancelled", "TicketCancelled", "TicketCancelled", "MissionCancelled");
        assertThat(batch)
                .filteredOn(e -> e.event() instanceof TicketEvent.TicketCancelled)
                .allMatch(
                        e -> ((TicketEvent.TicketCancelled) e.event()).reason().contains("mission cancelled"));
    }

    @Test
    void anOlderJournalWithOpenTicketsAfterTheCancellationReadsThemCancelled() {
        MissionId m = f.runningMission();
        f.record(f.company.missions().enableTicketValidation(f.director(), m));
        TicketId proposed = f.ticket(m, "Awaiting the director");
        f.record(f.company.missions().cancel(f.director(), m, "stop"));

        // Before the rule the cancellation alone was written: the tickets stayed open in the journal.
        Company older = Company.replay(f.journal.stream()
                .filter(e -> !(e.event() instanceof TicketEvent.TicketCancelled))
                .toList());

        assertThat(older.tickets().of(m)).allMatch(t -> t.status() == TicketStatus.CANCELLED);
        assertThat(older.tickets().find(proposed).orElseThrow().status()).isEqualTo(TicketStatus.CANCELLED);
    }

    @Test
    void cancellingWithdrawsOpenQuestionsAndDeniesPendingActions() {
        MissionId m = f.runningMission();
        f.record(f.company
                .missions()
                .addPermission(f.director(), m, new PermissionRule(null, ActionCategory.EXECUTE, null, Decision.ASK)));
        f.record(f.company
                .actions()
                .request(
                        f.agent(CHRIS),
                        new ActionId("a1"),
                        m,
                        f.firstTicket(m),
                        CHRIS,
                        ActionCategory.EXECUTE,
                        "Bash",
                        "docker restart gateway"));

        f.record(f.company.missions().cancel(f.director(), m, "client changed scope"));

        assertThat(f.company.desk().of(m)).allMatch(q -> q.status() == QuestionStatus.WITHDRAWN);
        Action a = f.company.actions().find(new ActionId("a1")).orElseThrow();
        assertThat(a.stage()).isEqualTo(ActionStage.DENIED);
        assertThat(a.denialReason()).isEqualTo(DenialReason.MISSION_NOT_ACTIVE);
    }

    @Test
    void aSessionThatOutlivesTheCancellationChangesNothing() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "report"));
        f.record(f.company.missions().cancel(f.director(), m, "client changed scope"));

        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> late = List.of(
                () -> f.company.tickets().review(f.agent(Fixture.DARIUS), t, ReviewVerdict.VALIDATED, "ok"),
                () -> f.company
                        .tickets()
                        .requestConsultation(f.agent(Fixture.DARIUS), t, ConsultationKind.RESEARCH, null, "Is it so?"));
        for (var call : late) {
            assertThatThrownBy(call).extracting("code").isEqualTo("MISSION_CLOSED");
        }
        Ticket after = f.company.tickets().find(t).orElseThrow();
        assertThat(after.status()).as("closed with the mission").isEqualTo(TicketStatus.CANCELLED);
        assertThat(f.company.missions().find(m).orElseThrow().status()).isEqualTo(MissionStatus.CANCELLED);
    }

    /**
     * A journal written before the refusal holds a review recorded after the cancellation, as a real run did: it
     * replays with the meaning it always had, the ticket done and the mission still cancelled.
     */
    @Test
    void anOlderJournalWithAReviewAfterTheCancellationReplaysAsBefore() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "report"));
        f.record(f.company.missions().cancel(f.director(), m, "client changed scope"));
        List<dev.floor42.domain.event.EventEnvelope> journal = new java.util.ArrayList<>(f.journal);
        Instant later = journal.getLast().occurredAt().plusSeconds(2);
        for (dev.floor42.domain.event.DomainEvent late : List.of(
                new dev.floor42.domain.event.TicketEvent.TicketReviewed(
                        t, Fixture.DARIUS, ReviewVerdict.VALIDATED, "ok"),
                new dev.floor42.domain.event.TicketEvent.TicketCompleted(t))) {
            journal.add(new dev.floor42.domain.event.EventEnvelope(
                    "late-" + journal.size(),
                    later,
                    Origin.SIMULATED,
                    Actor.agent(Fixture.DARIUS),
                    m,
                    null,
                    null,
                    late));
        }

        Company replayed = Company.replay(journal);

        assertThat(replayed.tickets().find(t).orElseThrow().status()).isEqualTo(TicketStatus.DONE);
        assertThat(replayed.missions().find(m).orElseThrow().status()).isEqualTo(MissionStatus.CANCELLED);
    }

    @Test
    void simulatedAndRealEventsNeverMixInOneMission() {
        MissionId m = f.mission();
        CommandContext real =
                new CommandContext(Actor.agent(GALEN), Origin.REAL, Instant.parse("2026-10-20T08:00:00Z"), null);

        assertThatThrownBy(() -> f.company.tickets().create(real, m, "x", "", Priority.LOW, List.of(), List.of()))
                .extracting("code")
                .isEqualTo("ORIGIN_MISMATCH");
    }

    @Test
    void aRefusedCommandRecordsNothing() {
        MissionId m = f.mission();
        long before = f.company.version();

        assertThatThrownBy(() -> f.company.missions().submitPlan(f.agent(GALEN), m))
                .isInstanceOf(DomainException.class);

        assertThat(f.company.version()).isEqualTo(before);
        assertThat(f.company.missions().find(m).orElseThrow().status()).isEqualTo(MissionStatus.PLANNING);
    }
}
