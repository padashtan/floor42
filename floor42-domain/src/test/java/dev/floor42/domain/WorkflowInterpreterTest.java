package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.CommitteeEvent.CommitteeArbitrated;
import dev.floor42.domain.golden.GoldenFile;
import dev.floor42.domain.golden.WorkflowDecisions;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import dev.floor42.domain.workflow.WorkflowInterpreter;
import dev.floor42.domain.workflow.WorkflowInterpreter.AfterEnd;
import dev.floor42.domain.workflow.WorkflowInterpreter.AfterRuling;
import dev.floor42.domain.workflow.WorkflowInterpreter.Opening;
import dev.floor42.domain.workflow.WorkflowInterpreter.Preparation;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The workflow interpreter (ADR-0046). Over the built-in workflow it decides what the daemon decided before it existed:
 * checked at every event of the scripted day, against the former rules copied as they were, and recorded as a golden
 * master of its decisions.
 */
class WorkflowInterpreterTest {

    private static final MissionId M = new MissionId("M-1");
    private final WorkflowInterpreter builtIn = new WorkflowInterpreter(Workflow.BUILT_IN);

    @Test
    void overTheScriptedDayTheBuiltInWorkflowDecidesAsRecorded() {
        GoldenScenarioTest day = new GoldenScenarioTest();
        day.play();

        String decisions = WorkflowDecisions.of(day.s.journal);

        assertThat(GoldenFile.check("workflow/scenario-decisions.txt", decisions))
                .isNull();
        assertThat(decisions).contains("Deliberating", "WorkingTickets", "Ended", ":REVIEW", "Implement");
    }

    @Test
    void aMissionOpensAsTheDirectorAskedIt() {
        assertThat(builtIn.opening(List.of("architect", "security"), "architecture", Preparation.ONE_TICKET))
                .isEqualTo(new Opening.ConveneCommittee("architecture"));
        assertThat(builtIn.opening(List.of(), "architecture", Preparation.BY_PLANNER))
                .isEqualTo(new Opening.PlanWithPlanner());
        assertThat(builtIn.opening(null, "architecture", Preparation.ONE_TICKET))
                .isEqualTo(new Opening.DeliverInOneTicket());
    }

    @Test
    void onlyAnAdoptionForImplementationLeadsToProducing() {
        assertThat(builtIn.afterRuling(new CommitteeArbitrated(M, true, true, false, 2)))
                .isEqualTo(new AfterRuling.Implement(new CommitteeRef(M, 2)));
        assertThat(builtIn.afterRuling(new CommitteeArbitrated(M, true, false, false, 1)))
                .isEqualTo(new AfterRuling.Decided(new CommitteeRef(M, 1)));
        assertThat(builtIn.afterRuling(new CommitteeArbitrated(M, false, false, false, null)))
                .isEqualTo(new AfterRuling.Decided(new CommitteeRef(M, 1)));
        assertThat(builtIn.afterEnd(M)).isEqualTo(new AfterEnd.HoldRetro(M));
    }

    /** The decisions come from the workflow value, not from code paths: a workflow without a step never takes it. */
    @Test
    void theInterpreterReadsItsWorkflow() {
        WorkflowInterpreter decideOnly = new WorkflowInterpreter(
                new Workflow("decide only", List.of(new Step.Approval(), new Step.Committee(), new Step.Accept())));

        assertThat(decideOnly.afterRuling(new CommitteeArbitrated(M, true, true, false, 1)))
                .isInstanceOf(AfterRuling.Decided.class);
        assertThat(decideOnly.afterEnd(M)).isEqualTo(new AfterEnd.NoRetro(M));
        assertThat(decideOnly.opening(List.of(), "architecture", Preparation.BY_PLANNER))
                .isEqualTo(new Opening.DeliverInOneTicket());
    }

    @Test
    void producedWorkIsReviewedAndTheAcceptanceInItsPlaceIsRefusedUntilBuilt() {
        assertThatThrownBy(() -> workflow(new Step.Approval(), new Step.Produce(), new Step.Accept()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Produced work is reviewed");
        assertThat(workflow(new Step.Approval(), new Step.Produce(), new Step.Review(), new Step.Accept())
                        .has(Step.Review.class))
                .isTrue();
    }

    @Test
    void theDirectorApprovesAndAcceptsInEveryWorkflow() {
        assertThatThrownBy(() -> workflow(new Step.Committee(), new Step.Accept()))
                .hasMessageContaining("director's approval");
        assertThatThrownBy(() -> workflow(new Step.Approval(), new Step.Committee()))
                .hasMessageContaining("director's acceptance");
        assertThatThrownBy(() -> new Workflow("none", List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    /** The order is Floor42's: [Plan, Accept, Produce] was accepted by the first version, and is refused now. */
    @Test
    void stepsComeOnceEachInFloor42sOrder() {
        assertThatThrownBy(() -> workflow(new Step.Plan(), new Step.Approval(), new Step.Accept(), new Step.Produce()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Accept cannot come before Produce");
        assertThatThrownBy(() -> workflow(new Step.Approval(), new Step.Plan(), new Step.Accept()))
                .hasMessageContaining("Approval cannot come before Plan");
        assertThatThrownBy(() -> workflow(new Step.Approval(), new Step.Retro(), new Step.Accept(), new Step.Retro()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> workflow(new Step.Approval(), new Step.Accept(), new Step.Accept()))
                .hasMessageContaining("Accept cannot come before Accept");
    }

    private static Workflow workflow(Step... steps) {
        return new Workflow("written", List.of(steps));
    }

    @Test
    void theBuiltInWorkflowIsTheOrderFloor42AlwaysFollowed() {
        assertThat(Workflow.BUILT_IN.steps().stream().map(s -> s.getClass().getSimpleName()))
                .containsExactly("Plan", "Approval", "Committee", "Produce", "Review", "Accept", "Retro");
        assertThat(Workflow.BUILT_IN.follows(Step.Produce.class, Step.Committee.class))
                .isTrue();
        assertThat(Workflow.BUILT_IN.follows(Step.Committee.class, Step.Produce.class))
                .isFalse();
    }
}
