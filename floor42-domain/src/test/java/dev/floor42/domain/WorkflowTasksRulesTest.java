package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.WorkflowEvent.WorkflowStepTicketOpened;
import dev.floor42.domain.workflow.CheckProgress;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a replay refuses of a link (ADR-0061), and the one walk over a ticket's verdicts (ADR-0052). Not a golden fixture. */
class WorkflowTasksRulesTest {

    static final Step.Task AUDIT = new Step.Task(
            "audit", "Audit", "Look", "Why", "Findings", Step.Task.Slot.BEFORE_ACCEPTANCE, WorkRole.REVIEWER, true);
    static final Workflow TASKED = new Workflow(
            "Tasked", List.of(new Step.Approval(), new Step.Produce(), new Step.Review(), AUDIT, new Step.Accept()));

    /** Not golden-recorded: its fixtures are never left for the next class's golden to pick up. */
    @org.junit.jupiter.api.AfterEach
    void forget() {
        Fixture.takeMade();
    }

    EventEnvelope link(Fixture f, Origin origin, MissionId mission, TicketId ticket) {
        return new EventEnvelope(
                "e-" + ticket,
                java.time.Instant.parse("2026-11-04T12:00:00Z"),
                origin,
                Actor.director(),
                mission,
                null,
                null,
                new WorkflowStepTicketOpened(mission, ticket, "audit"));
    }

    @Test
    void aLinkOnAMissionThatIsNotRealStopsTheReplay() {
        Fixture f = new Fixture(Origin.SIMULATED);
        MissionId m = f.mission();
        TicketId t = f.ticket(m, "Work");
        assertThatThrownBy(() -> f.company.apply(link(f, Origin.SIMULATED, m, t)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated");
    }

    @Test
    void aLinkToATicketOfAnotherMissionStopsTheReplay() {
        Fixture f = new Fixture(Origin.REAL);
        List<EventEnvelope> created = f.record(f.company.missions().create(f.director(), "A", "o", "C:/repo", TASKED));
        MissionId a = ((MissionCreated) created.getFirst().event()).mission();
        MissionId b = ((MissionCreated)
                        f.record(f.company.missions().create(f.director(), "B", "o", "C:/repo", Workflow.BUILT_IN))
                                .getFirst()
                                .event())
                .mission();
        TicketId inB = f.ticket(b, "Work of B");
        assertThatThrownBy(() -> f.company.apply(link(f, Origin.REAL, a, inB)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a ticket of");
    }

    /** Each review's position comes from the guard's own walk: a rejection restarts the submission. */
    @Test
    void thePositionsOfTheVerdictsAreTheGuardsOwnWalk() {
        Step.Check security = new Step.Check("sec", "Security", "Look", "Why", "Findings", true);
        Step.Check wording = new Step.Check("wording", "Wording", "Read", "Why", "Passages", false);
        Workflow w = new Workflow(
                "Checked",
                List.of(
                        new Step.Approval(),
                        new Step.Produce(),
                        new Step.Review(),
                        security,
                        wording,
                        new Step.Accept()));
        Fixture f = new Fixture(Origin.REAL);
        MissionId m = ((MissionCreated) f.record(f.company.missions().create(f.director(), "C", "o", "C:/repo", w))
                        .getFirst()
                        .event())
                .mission();
        TicketId t = f.ticket(m, "Work");
        f.record(f.company.tickets().assign(f.director(), t, Fixture.CHRIS));
        f.record(f.company.missions().submitPlan(f.agent(Fixture.GALEN), m));
        f.record(f.company.verification().setBudget(f.director(), m, null));
        f.record(f.company.missions().approvePlan(f.director(), m));
        f.record(f.company.tickets().start(f.agent(Fixture.CHRIS), t));
        var interpreter = new dev.floor42.domain.workflow.MissionInterpreter();
        ReviewVerdict[] verdicts = {
            ReviewVerdict.VALIDATED,
            ReviewVerdict.REJECTED, // review, then the security check rejects
            ReviewVerdict.VALIDATED,
            ReviewVerdict.VALIDATED,
            ReviewVerdict.VALIDATED_WITH_RESERVATIONS
        };
        f.record(f.company.tickets().submitForReview(f.agent(Fixture.CHRIS), t, "patch"));
        for (int i = 0; i < verdicts.length; i++) {
            if (i == 2) f.record(f.company.tickets().submitForReview(f.agent(Fixture.CHRIS), t, "patch 2"));
            var at = interpreter
                    .progress(f.company, f.company.tickets().find(t).orElseThrow())
                    .orElseThrow()
                    .at();
            f.record(f.company.tickets().review(f.agent(Fixture.DARIUS), t, verdicts[i], "f", List.of(), at));
        }
        Ticket done = f.company.tickets().find(t).orElseThrow();
        assertThat(done.status()).isEqualTo(TicketStatus.DONE);
        assertThat(CheckProgress.positions(
                        w, done.reviews(), f.company.tickets().reviewRounds(t)))
                .containsExactly(0, 1, 0, 1, 2);
    }
}
