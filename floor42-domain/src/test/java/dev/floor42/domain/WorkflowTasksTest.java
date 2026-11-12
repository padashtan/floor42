package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.event.WorkflowEvent.WorkflowStepTicketOpened;
import dev.floor42.domain.workflow.MissionInterpreter;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import dev.floor42.domain.workflow.WorkflowInterpreter;
import dev.floor42.domain.workflow.WorkflowInterpreter.Opening;
import dev.floor42.domain.workflow.WorkflowInterpreter.Preparation;
import dev.floor42.domain.workflow.WorkflowInterpreter.TicketStep;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The director's tasks (ADR-0061): a ticket opened by the plan's approval, once, linked to its step by the one new
 * event; the holds of the two slots; the report that completes an unreviewed task; the reopening when work changes
 * after a done task; the contradictions a replay refuses. A golden fixture: it records the link event.
 */
@ExtendWith(GoldenFixtureExtension.class)
class WorkflowTasksTest {

    static final Step.Task DESIGN = task("design-note", Step.Task.Slot.BEFORE_PRODUCTION, WorkRole.RESEARCHER, false);
    static final Step.Task AUDIT = task("audit", Step.Task.Slot.BEFORE_ACCEPTANCE, WorkRole.REVIEWER, true);
    static final Workflow TASKED = new Workflow(
            "Tasked",
            List.of(
                    new Step.Plan(3),
                    new Step.Approval(),
                    DESIGN,
                    new Step.Produce(),
                    new Step.Review(),
                    AUDIT,
                    new Step.Accept()));
    static final Workflow RESEARCH = new Workflow("Research", List.of(new Step.Approval(), AUDIT, new Step.Accept()));

    final Fixture f = new Fixture(Origin.REAL);
    final MissionInterpreter interpreter = new MissionInterpreter();

    static Step.Task task(String key, Step.Task.Slot slot, WorkRole by, boolean reviewed) {
        return new Step.Task(key, "Title " + key, "Does " + key, "Why " + key, "Hands over " + key, slot, by, reviewed);
    }

    MissionId created(Workflow w) {
        List<EventEnvelope> events =
                f.record(f.company.missions().create(f.director(), "Tasks", "Do it", "C:/repo", w));
        return ((MissionCreated) events.getFirst().event()).mission();
    }

    List<EventEnvelope> approve(MissionId m) {
        f.record(f.company.verification().setBudget(f.director(), m, null));
        return f.record(f.company.missions().approvePlan(f.director(), m));
    }

    Ticket linked(MissionId m, String key) {
        return f.company
                .tickets()
                .find(f.company.workflows().linked(m).get(key))
                .orElseThrow();
    }

    @Test
    void theApprovalOpensEachTaskOnceWithItsLinkAndNothingBefore() {
        MissionId m = created(TASKED);
        TicketId plan = f.ticket(m, "Work");
        f.record(f.company.missions().submitPlan(f.agent(Fixture.GEOFFREY), m));
        assertThat(f.company.workflows().linked(m)).isEmpty();
        List<String> names = approve(m).stream()
                .map(e -> e.event().getClass().getSimpleName())
                .toList();
        assertThat(names)
                .containsExactly(
                        "MissionPlanApproved",
                        "TicketCreated",
                        "TicketApproved",
                        "WorkflowStepTicketOpened",
                        "TicketCreated",
                        "TicketApproved",
                        "WorkflowStepTicketOpened");
        assertThat(linked(m, "audit").dependsOn())
                .contains(plan, linked(m, "design-note").id());
        assertThat(linked(m, "design-note").dependsOn()).isEmpty();
        assertThat(linked(m, "audit").acceptanceCriteria()).containsExactly("Hands over audit");
    }

    @Test
    void aRejectedPlanAndItsResubmissionOpenNothing() {
        MissionId m = created(TASKED);
        f.ticket(m, "Work");
        f.record(f.company.missions().submitPlan(f.agent(Fixture.GEOFFREY), m));
        f.record(f.company.missions().rejectPlan(f.director(), m, "again"));
        f.record(f.company.missions().submitPlan(f.agent(Fixture.GEOFFREY), m));
        assertThat(f.company.workflows().linked(m)).isEmpty();
        approve(m);
        assertThat(f.company.workflows().linked(m)).hasSize(2);
    }

    @Test
    void aWorkflowOfTasksOnlyOpensOnTheApprovalWithNoTicketAndCannotBeSentBack() {
        assertThat(new WorkflowInterpreter(RESEARCH).opening(null, "architecture", Preparation.BY_PLANNER))
                .isInstanceOf(Opening.AwaitStepApproval.class);
        MissionId m = created(RESEARCH);
        f.record(f.company.missions().submitPlan(f.system(), m));
        assertThat(f.company.tickets().of(m)).isEmpty();
        assertThatThrownBy(() -> f.company.missions().rejectPlan(f.director(), m, "no"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("NOTHING_TO_REPLAN"));
        assertThatThrownBy(() -> f.company.missions().approvePlan(f.director(), m))
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("CAP_NEEDED"));
        approve(m);
        assertThat(f.company.workflows().linked(m)).containsOnlyKeys("audit");
        assertThat(interpreter.step(f.company, linked(m, "audit"), null).name()).isEqualTo("TASK");
    }

    @Test
    void aTaskBeforeProductionHoldsTheOtherWorkAndTheOneBeforeAcceptanceWaitsForIt() {
        MissionId m = created(TASKED);
        TicketId work = f.ticket(m, "Work");
        f.record(f.company.missions().submitPlan(f.agent(Fixture.GEOFFREY), m));
        approve(m);
        Ticket design = linked(m, "design-note");
        Ticket audit = linked(m, "audit");
        assertThat(interpreter.held(f.company, f.company.tickets().find(work).orElseThrow()))
                .as("production waits for the design note")
                .isTrue();
        assertThat(interpreter.held(f.company, design)).isFalse();
        assertThat(interpreter.held(f.company, audit)).isTrue();
        f.record(f.company.tickets().assign(f.director(), design.id(), Fixture.CHRIS));
        f.record(f.company.tickets().start(f.agent(Fixture.CHRIS), design.id()));
        // An unreviewed task is done with its report.
        List<String> names =
                f.record(f.company.tickets().submitForReview(f.agent(Fixture.CHRIS), design.id(), "the note")).stream()
                        .map(e -> e.event().getClass().getSimpleName())
                        .toList();
        assertThat(names).containsExactly("TicketSubmittedForReview", "TicketCompleted");
        assertThat(f.company.tickets().find(design.id()).orElseThrow().status()).isEqualTo(TicketStatus.DONE);
        assertThat(interpreter.held(f.company, f.company.tickets().find(work).orElseThrow()))
                .isFalse();
        assertThat(interpreter.held(f.company, audit))
                .as("the work is not done")
                .isTrue();
    }

    @Test
    void aReviewedTaskIsReviewedAndWorkChangedAfterADoneTaskReopensIt() {
        MissionId m = created(TASKED);
        TicketId work = f.ticket(m, "Work");
        f.record(f.company.missions().submitPlan(f.agent(Fixture.GEOFFREY), m));
        approve(m);
        finish(m, "design-note", Fixture.CHRIS);
        f.record(f.company.tickets().assign(f.director(), work, Fixture.CHRIS));
        f.record(f.company.tickets().start(f.agent(Fixture.CHRIS), work));
        f.record(f.company.tickets().submitForReview(f.agent(Fixture.CHRIS), work, "patch"));
        f.record(f.company.tickets().review(f.agent(Fixture.DEHREN), work, ReviewVerdict.VALIDATED, "fine"));
        Ticket audit = linked(m, "audit");
        assertThat(interpreter.held(f.company, audit)).isFalse();
        f.record(f.company.tickets().assign(f.director(), audit.id(), Fixture.GEOFFREY));
        f.record(f.company.tickets().start(f.agent(Fixture.GEOFFREY), audit.id()));
        f.record(f.company.tickets().submitForReview(f.agent(Fixture.GEOFFREY), audit.id(), "findings"));
        assertThat(interpreter.step(f.company, linked(m, "audit"), WorkRole.REVIEWER))
                .isEqualTo(TicketStep.REVIEW);
        f.record(f.company.tickets().review(f.agent(Fixture.DEHREN), audit.id(), ReviewVerdict.VALIDATED, "ok"));
        assertThat(linked(m, "audit").status()).isEqualTo(TicketStatus.DONE);
        // The director sends the work back: the audit judged work that is not the work anymore.
        List<EventEnvelope> events = f.record(f.company.workshop().giveDirective(f.director(), work, "also the docs"));
        assertThat(events).anySatisfy(e -> assertThat(e.event().toString()).contains("work changed after the step"));
        assertThat(linked(m, "audit").status()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(interpreter.held(f.company, linked(m, "audit")))
                .as("it waits for the work")
                .isTrue();
    }

    private void finish(MissionId m, String key, AgentId by) {
        TicketId id = linked(m, key).id();
        f.record(f.company.tickets().assign(f.director(), id, by));
        f.record(f.company.tickets().start(f.agent(by), id));
        f.record(f.company.tickets().submitForReview(f.agent(by), id, "report"));
    }

    @Test
    void aReplayStopsOnALinkTheCodeDisagreesWith() {
        MissionId m = created(TASKED);
        f.ticket(m, "Work");
        f.record(f.company.missions().submitPlan(f.agent(Fixture.GEOFFREY), m));
        approve(m);
        EventEnvelope link = f.journal.stream()
                .filter(e -> e.event() instanceof WorkflowStepTicketOpened)
                .findFirst()
                .orElseThrow();
        List<EventEnvelope> twice = new java.util.ArrayList<>(f.journal);
        twice.add(link);
        assertThatThrownBy(() -> Company.replay(twice)).isInstanceOf(IllegalStateException.class);
        // The same journal replays to the same links.
        assertThat(Company.replay(f.journal).workflows().linked(m))
                .isEqualTo(f.company.workflows().linked(m));
        TicketCreated created = f.journal.stream()
                .map(EventEnvelope::event)
                .filter(TicketCreated.class::isInstance)
                .map(TicketCreated.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(created.kind()).isEqualTo(TicketKind.WORK);
    }
}
