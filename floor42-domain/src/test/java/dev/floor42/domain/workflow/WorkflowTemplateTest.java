package dev.floor42.domain.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.workflow.Workflow.Step;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Floor42's templates (ADR-0052): constants of the code that version 1 can express, never journaled. */
class WorkflowTemplateTest {

    @Test
    void theLibraryOffersTheTemplatesOfTheseIncrementsUnderStableKeys() {
        assertThat(WorkflowTemplate.ALL)
                .extracting(WorkflowTemplate::key)
                .containsExactly(
                        "code-change", "light-change", "decision", "document-production", "research", "review-only");
        assertThat(WorkflowTemplate.ALL).extracting(WorkflowTemplate::revision).containsOnly(1);
    }

    @Test
    void codeChangeIsWhatFloor42AlwaysDid() {
        assertThat(WorkflowTemplate.CODE_CHANGE.workflow().steps()).isEqualTo(Workflow.BUILT_IN.steps());
    }

    @Test
    void lightChangeIsASmallPlanWithNoCommitteeAndNoRetrospective() {
        Workflow w = WorkflowTemplate.LIGHT_CHANGE.workflow();
        assertThat(w.maxTickets()).isEqualTo(3);
        assertThat(w.has(Step.Committee.class)).isFalse();
        assertThat(w.has(Step.Retro.class)).isFalse();
        assertThat(w.has(Step.Produce.class)).isTrue();
        assertThat(w.has(Step.Review.class)).isTrue();
    }

    @Test
    void decisionIsApprovalCommitteeAcceptance() {
        assertThat(WorkflowTemplate.DECISION.workflow().steps())
                .isEqualTo(List.of(new Step.Approval(), new Step.Committee(), new Step.Accept()));
    }

    /** Each template in the lowest version that holds it: the three of increment 2 stay version 1. */
    @Test
    void everyTemplateWritesInItsLowestVersionAndReadsBackIdentical() {
        for (WorkflowTemplate t : WorkflowTemplate.ALL) {
            String document = WorkflowDocument.write(t.workflow());
            int version = WorkflowDocument.versionOf(t.workflow());
            assertThat(version)
                    .isEqualTo(
                            t == WorkflowTemplate.DOCUMENT_PRODUCTION
                                    ? 2
                                    : t.workflow().tasks().isEmpty() ? 1 : 3);
            assertThat(WorkflowDocument.read(document, version))
                    .isEqualTo(new WorkflowDocument.Reading.Read(t.workflow()));
            assertThat(CompanyWorkflow.nameRefusal(t.workflow().name())).isEmpty();
        }
    }

    /** The two templates of tasks: unreviewed research by a researcher, reviewed reading by a reviewer. */
    @Test
    void researchAndReviewOnlyAreTasksOfTheSlotBeforeAcceptance() {
        Step.Task research = WorkflowTemplate.RESEARCH.workflow().tasks().getFirst();
        assertThat(research.runBy()).isEqualTo(dev.floor42.domain.WorkRole.RESEARCHER);
        assertThat(research.reviewed()).isFalse();
        Step.Task review = WorkflowTemplate.REVIEW_ONLY.workflow().tasks().getFirst();
        assertThat(review.runBy()).isEqualTo(dev.floor42.domain.WorkRole.REVIEWER);
        assertThat(review.reviewed()).isTrue();
        assertThat(WorkflowTemplate.RESEARCH.workflow().opensOnStepApproval()).isTrue();
    }

    @Test
    void documentProductionEndsWithAnAdvisoryProofreadingCheckRunByAReviewer() {
        Workflow w = WorkflowTemplate.DOCUMENT_PRODUCTION.workflow();
        assertThat(w.steps())
                .extracting(WorkflowDocument::stepKind)
                .containsExactly("plan", "approval", "produce", "review", "check", "accept");
        Step.Check proofreading = w.checks().getFirst();
        assertThat(proofreading.key()).isEqualTo("proofreading");
        assertThat(proofreading.blocks()).isFalse();
        assertThat(proofreading.runBy()).isEqualTo(dev.floor42.domain.WorkRole.REVIEWER);
    }
}
