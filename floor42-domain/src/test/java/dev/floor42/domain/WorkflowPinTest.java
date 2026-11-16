package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.WorkflowEvent.WorkflowPinned;
import dev.floor42.domain.workflow.MissionInterpreter;
import dev.floor42.domain.workflow.MissionWorkflow;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import dev.floor42.domain.workflow.WorkflowDocument;
import dev.floor42.domain.workflow.WorkflowInterpreter.AfterEnd;
import dev.floor42.domain.workflow.WorkflowInterpreter.Position;
import dev.floor42.domain.workflow.WorkflowInterpreter.TicketStep;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A mission pins its workflow at its creation, before any ticket, and follows it for good (ADR-0050). */
@ExtendWith(GoldenFixtureExtension.class)
class WorkflowPinTest {

    private static final Workflow LIGHT = new Workflow(
            "Light",
            List.of(new Step.Plan(3), new Step.Approval(), new Step.Produce(), new Step.Review(), new Step.Accept()));

    final Fixture f = new Fixture(Origin.REAL);

    private MissionId pinned(Workflow w) {
        List<EventEnvelope> events = f.record(f.company.missions().create(f.director(), "Pin", "Pin it", "C:/repo", w));
        assertThat(events)
                .extracting(e -> e.event().getClass().getSimpleName())
                .containsExactly("MissionCreated", "WorkflowPinned");
        return ((MissionCreated) events.getFirst().event()).mission();
    }

    @Test
    void theMissionAndItsPinAreOneCommandAndThePinIsTheWorkflowsDocument() {
        MissionId m = pinned(LIGHT);
        WorkflowPinned pin = (WorkflowPinned) f.journal.getLast().event();

        assertThat(pin.document()).isEqualTo(WorkflowDocument.write(LIGHT));
        assertThat(pin.schemaVersion()).isEqualTo(1);
        assertThat(f.company.workflows().of(m)).isEqualTo(new MissionWorkflow.Pinned(LIGHT));
        assertThat(f.journal.getLast().mission()).isEqualTo(m);
    }

    @Test
    void aMissionCreatedWithoutAWorkflowFollowsTheBuiltInOne() {
        MissionId m = unpinned();
        assertThat(f.company.workflows().of(m)).isEqualTo(new MissionWorkflow.Unpinned());
        assertThat(f.company.workflows().of(m).runnable()).contains(Workflow.BUILT_IN);
    }

    @Test
    void aSimulatedMissionIsNeverPinned() {
        Fixture sim = new Fixture(Origin.SIMULATED);
        assertThatThrownBy(() -> sim.company.missions().create(sim.director(), "t", "o", null, LIGHT))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Floor42's own workflow");
        MissionId m = sim.mission();
        assertThatThrownBy(() -> sim.company.apply(pinEnvelope(m, WorkflowDocument.write(LIGHT), 1, Origin.SIMULATED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated");
    }

    /** A pin twice, or after the first ticket, is a journal the code disagrees with: the replay stops. */
    @Test
    void aSecondPinOrAPinAfterTheFirstTicketStopsTheReplay() {
        MissionId m = pinned(LIGHT);
        assertThatThrownBy(() -> f.company.apply(pinEnvelope(m, WorkflowDocument.write(LIGHT), 1, Origin.REAL)))
                .hasMessageContaining("pinned twice");

        MissionId other = unpinned();
        f.ticket(other, "First");
        assertThatThrownBy(() -> f.company.apply(pinEnvelope(other, WorkflowDocument.write(LIGHT), 1, Origin.REAL)))
                .hasMessageContaining("after its first ticket");
        assertThatThrownBy(() -> f.company.apply(pinEnvelope(new MissionId("M-99"), "{}", 1, Origin.REAL)))
                .hasMessageContaining("unknown M-99");
    }

    /** A schema this build does not read keeps the mission readable and refuses to run it, with the reason. */
    @Test
    void aPinOfANewerSchemaIsReadButNeverRun() {
        MissionId m = unpinned();
        f.company.apply(pinEnvelope(m, "{\"of\":\"a later Floor42\"}", 7, Origin.REAL));
        f.ticket(m, "Work");
        f.record(f.company.missions().submitPlan(f.agent(Fixture.GEOFFREY), m));
        f.record(f.company.verification().setBudget(f.director(), m, 1.0));

        MissionWorkflow w = f.company.workflows().of(m);
        assertThat(w).isInstanceOf(MissionWorkflow.Unrunnable.class);
        assertThat(((MissionWorkflow.Unrunnable) w).reason()).contains("workflow schema 7", "upgrade");
        assertThatThrownBy(() -> f.company.missions().approvePlan(f.director(), m))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("WORKFLOW_UNREADABLE"))
                .hasMessageContaining("workflow schema 7");

        MissionInterpreter interpreter = new MissionInterpreter();
        assertThat(interpreter.position(f.company, m)).isInstanceOf(Position.Refused.class);
        assertThat(interpreter.step(f.company, f.company.tickets().of(m).getFirst(), WorkRole.DEVELOPER))
                .isEqualTo(TicketStep.NONE);
        assertThat(interpreter.afterEnd(f.company, m)).isEqualTo(new AfterEnd.NoRetro(m));
    }

    /**
     * No apply reads a pinned workflow beyond the pin's own checks: a mission that went on under 1.8.0 after a
     * rollback (more tickets than its plan allows) replays on 1.9.0 again.
     */
    @Test
    void eventsThatContradictAPinStillReplay() {
        MissionId m = pinned(LIGHT);
        for (int i = 1; i <= LIGHT.maxTickets() + 3; i++) f.ticket(m, "Ticket " + i);

        Company again = Company.replay(new ArrayList<>(f.journal));

        assertThat(again.tickets().of(m)).hasSize(6);
        assertThat(again.workflows().of(m)).isEqualTo(new MissionWorkflow.Pinned(LIGHT));
    }

    /** A real mission as journaled before 1.9.0: no pin. */
    private MissionId unpinned() {
        MissionId m = new MissionId("M-" + (f.company.missions().all().size() + 1));
        EventEnvelope created = new EventEnvelope(
                "old-" + m,
                f.director().now(),
                Origin.REAL,
                Actor.director(),
                m,
                null,
                null,
                new MissionCreated(m, "Old", "Before 1.9.0", "C:/repo"));
        f.company.apply(created);
        f.record(List.of(created));
        return m;
    }

    private static EventEnvelope pinEnvelope(MissionId m, String document, int version, Origin origin) {
        return new EventEnvelope(
                "pin-" + m + "-" + version,
                Instant.parse("2026-11-01T10:00:00Z"),
                origin,
                Actor.director(),
                m,
                null,
                null,
                new WorkflowPinned(m, document, version));
    }
}
