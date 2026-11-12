package dev.floor42.domain.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.event.CommitteeEvent.CommitteeArbitrated;
import dev.floor42.domain.workflow.Workflow.Step;
import dev.floor42.domain.workflow.WorkflowInterpreter.AfterEnd;
import dev.floor42.domain.workflow.WorkflowInterpreter.AfterRuling;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every subset of the optional steps (plan, committee, produce, review, retro) around the director's approval and
 * acceptance (ADR-0050): which ones a workflow may hold, and that what each allowed one answers has a taker. Produced
 * work always has a review; a workflow without production holds a committee and never hands a decision to production.
 */
class WorkflowSubsetsTest {

    private static final MissionId M = new MissionId("M-1");

    @Test
    void everyAllowedSubsetLeavesNoStepWithoutATaker() {
        List<Workflow> allowed = new ArrayList<>();
        for (int mask = 0; mask < 32; mask++) {
            List<Step> steps = steps(mask);
            try {
                allowed.add(new Workflow("subset " + mask, steps));
            } catch (IllegalArgumentException e) {
                // Handled: refused subsets are counted below, by what they lack.
                assertThat(refusable(steps))
                        .as("subset %d refused: %s", mask, e.getMessage())
                        .isTrue();
            }
        }
        // 8 produce and review (plan, committee, retro free), 4 decide with a committee only (plan, retro free)
        assertThat(allowed).hasSize(12);
        for (Workflow w : allowed) {
            assertThat(w.has(Step.Produce.class)).isEqualTo(w.has(Step.Review.class));
            assertThat(w.has(Step.Produce.class) || w.has(Step.Committee.class)).isTrue();
            WorkflowInterpreter i = new WorkflowInterpreter(w);
            boolean implement =
                    i.afterRuling(new CommitteeArbitrated(M, true, true, false, 1)) instanceof AfterRuling.Implement;
            assertThat(implement).as(w.name()).isEqualTo(w.producesAfterCommittees());
            assertThat(implement).isEqualTo(w.has(Step.Produce.class) && w.has(Step.Committee.class));
            assertThat(i.afterEnd(M) instanceof AfterEnd.HoldRetro).isEqualTo(w.has(Step.Retro.class));
        }
    }

    private static List<Step> steps(int mask) {
        List<Step> steps = new ArrayList<>();
        if ((mask & 1) != 0) steps.add(new Step.Plan());
        steps.add(new Step.Approval());
        if ((mask & 2) != 0) steps.add(new Step.Committee());
        if ((mask & 4) != 0) steps.add(new Step.Produce());
        if ((mask & 8) != 0) steps.add(new Step.Review());
        steps.add(new Step.Accept());
        if ((mask & 16) != 0) steps.add(new Step.Retro());
        return steps;
    }

    /** What the invariants refuse: production without review, review without production, nothing produced nor decided. */
    private static boolean refusable(List<Step> steps) {
        boolean produce = steps.stream().anyMatch(Step.Produce.class::isInstance);
        boolean review = steps.stream().anyMatch(Step.Review.class::isInstance);
        boolean committee = steps.stream().anyMatch(Step.Committee.class::isInstance);
        return produce != review || (!produce && !committee);
    }
}
