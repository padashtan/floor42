package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.GoldenJournals;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import dev.floor42.server.app.WorkflowEquivalence.Expectation;
import dev.floor42.server.app.WorkflowEquivalence.Pins;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The workflow interpreter decides as c62612d did (ADR-0046), and a pinned workflow is the one followed (ADR-0050):
 * every decision after every event of every golden journal of the domain (the scripted day and every fixture), as
 * journaled, with every REAL mission pinned to the built-in workflow, and under mutant pins. The real journal goes
 * through the same bench in {@code RealJournalReplayTest}.
 */
class WorkflowEquivalenceTest {

    private static final Workflow NO_RETRO = new Workflow(
            "no retro",
            Workflow.BUILT_IN.steps().stream()
                    .filter(s -> !(s instanceof Step.Retro))
                    .toList());

    /** Floor42's own workflow with one blocking check after the review (ADR-0052, schema version 2). */
    private static final Workflow ONE_CHECK = new Workflow(
            "one check",
            Workflow.BUILT_IN.steps().stream()
                    .<Step>mapMulti((s, out) -> {
                        out.accept(s);
                        if (s instanceof Step.Review)
                            out.accept(new Step.Check("security", "Security", "Look", "Contract", "Findings", true));
                    })
                    .toList());

    @Test
    void everyDecisionAtEveryEventOfEveryGoldenJournalIsTheFormerOne() {
        Totals t = run(null, Expectation.FORMER, WorkflowDecider.daemon());
        System.out.printf(
                "Workflow equivalence over the golden journals: %d events, %d decisions compared%n",
                t.events, t.comparisons);
        assertThat(t.disagreements).isEmpty();
        assertThat(t.events).isGreaterThan(1_000);
        assertThat(t.comparisons).isGreaterThan(t.events);
    }

    /** The built-in workflow pinned to every REAL mission decides exactly as no pin at all. */
    @Test
    void aMissionPinnedToTheBuiltInWorkflowDecidesAsAnUnpinnedOne() {
        Totals t = run(Pins.of(Workflow.BUILT_IN), Expectation.FORMER, WorkflowDecider.daemon());
        System.out.printf(
                "Built-in pinned over the golden journals: %d REAL missions, %d decisions compared%n",
                t.real, t.comparisons);
        assertThat(t.disagreements).isEmpty();
        assertThat(t.real).isGreaterThan(10);
    }

    /** A schema this build cannot read: every decision of the mission is a refusal, and nothing runs. */
    @Test
    void aPinOfANewerSchemaRefusesEveryDecisionOfItsMission() {
        Pins newer = new Pins("{\"steps\":\"of a later Floor42\"}", 2);
        assertThat(run(newer, Expectation.REFUSED, WorkflowDecider.daemon()).disagreements)
                .isEmpty();
        // The bench bites: an interpreter that ignores the pin (falls back to the built-in workflow) fails it.
        assertThat(run(newer, Expectation.REFUSED, WorkflowDecider.ignoringPins()).disagreements)
                .isNotEmpty();
    }

    /** A workflow without the retrospective differs from c62612d on the retrospective decisions and nowhere else. */
    @Test
    void aPinWithoutTheRetrospectiveDiffersOnTheRetrospectiveOnly() {
        assertThat(run(Pins.of(NO_RETRO), Expectation.NO_RETRO, WorkflowDecider.daemon()).disagreements)
                .isEmpty();
        List<String> asBefore = run(Pins.of(NO_RETRO), Expectation.FORMER, WorkflowDecider.daemon()).disagreements;
        assertThat(asBefore).isNotEmpty().allMatch(d -> d.contains("retrospective follows"));
        assertThat(run(Pins.of(NO_RETRO), Expectation.NO_RETRO, WorkflowDecider.ignoringPins()).disagreements)
                .isNotEmpty();
    }

    /**
     * A pin with one check differs from c62612d on the ticket decisions after a validating review and nowhere else
     * (ADR-0052): the journals complete the ticket right after its review and the review's sorted items, so between
     * them the check is the next step. Nothing else moves: no dispatch phase, ruling, implementation or retrospective
     * decision.
     */
    @Test
    void aPinWithOneCheckDiffersOnTheTicketDecisionsAfterAReviewOnly() {
        List<String> differ = run(Pins.of(ONE_CHECK), Expectation.FORMER, WorkflowDecider.daemon()).disagreements;
        assertThat(differ)
                .isNotEmpty()
                .allMatch(
                        d -> d.matches(
                                ".* #\\d+ (TicketReviewed|ReviewItemsRecorded) T-\\d+: expected REVIEW .* now CHECK"),
                        "after a review");
        // The bench bites: an interpreter that ignores the pin finds no difference.
        assertThat(run(Pins.of(ONE_CHECK), Expectation.FORMER, WorkflowDecider.ignoringPins()).disagreements)
                .isEmpty();
    }

    /** Floor42's own workflow with one reviewer's task before the acceptance (ADR-0061, schema version 3). */
    private static final Workflow ONE_TASK = new Workflow(
            "one task",
            Workflow.BUILT_IN.steps().stream()
                    .<Step>mapMulti((s, out) -> {
                        if (s instanceof Step.Accept)
                            out.accept(new Step.Task(
                                    "audit",
                                    "Audit",
                                    "Look",
                                    "Contract",
                                    "Findings",
                                    Step.Task.Slot.BEFORE_ACCEPTANCE,
                                    dev.floor42.domain.WorkRole.REVIEWER,
                                    true));
                        out.accept(s);
                    })
                    .toList());

    /**
     * A pin with a task moves nothing for a ticket no step links (ADR-0061): a ticket of the golden journals is none
     * of the tasks' tickets, which only the plan's approval opens, so every decision is the former one. What the task
     * changes is the opening of a workflow of tasks only (below) and its own tickets' steps ({@code
     * WorkflowTasksTest}).
     */
    @Test
    void aPinWithATaskDecidesAsBeforeForEveryTicketNoTaskOpened() {
        assertThat(run(Pins.of(ONE_TASK), Expectation.FORMER, WorkflowDecider.daemon()).disagreements)
                .isEmpty();
    }

    /** A workflow of tasks only opens on the approval; the others open as before (the bench below proves it). */
    @Test
    void aWorkflowOfATaskOnlyOpensOnTheApprovalWithNoTicket() {
        Workflow research = dev.floor42.domain.workflow.WorkflowTemplate.RESEARCH.workflow();
        assertThat(new dev.floor42.domain.workflow.WorkflowInterpreter(research)
                        .opening(
                                null,
                                "architecture",
                                dev.floor42.domain.workflow.WorkflowInterpreter.Preparation.BY_PLANNER))
                .isInstanceOf(dev.floor42.domain.workflow.WorkflowInterpreter.Opening.AwaitStepApproval.class);
    }

    @Test
    void everyWayOfOpeningAMissionOpensItAsBefore() {
        WorkflowEquivalence.Result r = WorkflowEquivalence.openings();

        assertThat(r.disagreements()).isEmpty();
        assertThat(r.comparisons()).isEqualTo(3 * 6 * 4 * 2);
    }

    private record Totals(int events, long comparisons, long real, List<String> disagreements) {}

    private static Totals run(Pins pins, Expectation expectation, WorkflowDecider decider) {
        int events = 0;
        long comparisons = 0;
        long real = 0;
        List<String> disagreements = new java.util.ArrayList<>();
        for (Map.Entry<String, List<EventEnvelope>> journal :
                GoldenJournals.all().entrySet()) {
            WorkflowEquivalence.Result r = WorkflowEquivalence.of(journal.getValue(), decider, pins, expectation);
            r.disagreements().forEach(d -> disagreements.add(journal.getKey() + " " + d));
            events += r.events();
            comparisons += r.comparisons();
            real += WorkflowEquivalence.realMissions(journal.getValue());
        }
        return new Totals(events, comparisons, real, disagreements);
    }
}
