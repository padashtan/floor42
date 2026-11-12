package dev.floor42.domain.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.WorkRole;
import dev.floor42.domain.workflow.Workflow.Step;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Schema version 3 (ADR-0061, increment 4 of ADR-0052): the director's tasks, its reader and what a rollback reads. */
class WorkflowDocumentV3Test {

    static final Step.Task DESIGN = new Step.Task(
            "design-note",
            "Design note",
            "Read the objective and write the design note.",
            "The team agrees before it writes",
            "A note of one page",
            Step.Task.Slot.BEFORE_PRODUCTION,
            WorkRole.RESEARCHER,
            false);
    static final Step.Task AUDIT = new Step.Task(
            "audit",
            "Audit of the delivery",
            "Read what was delivered",
            "A second pair of eyes",
            "The findings",
            Step.Task.Slot.BEFORE_ACCEPTANCE,
            WorkRole.REVIEWER,
            true);
    static final Workflow TASKED = new Workflow(
            "Release with tasks",
            List.of(
                    new Step.Plan(3),
                    new Step.Approval(),
                    DESIGN,
                    new Step.Produce(),
                    new Step.Review(),
                    AUDIT,
                    new Step.Accept(),
                    new Step.Retro()));
    static final Workflow RESEARCH = new Workflow("Research", List.of(new Step.Approval(), AUDIT, new Step.Accept()));

    @Test
    void aWorkflowWithTasksIsWrittenInVersionThreeCanonicallyAndReadsBackIdentical() {
        assertThat(WorkflowDocument.versionOf(TASKED)).isEqualTo(3);
        String document = WorkflowDocument.write(TASKED);
        assertThat(document)
                .contains("{\"step\":\"task\",\"key\":\"design-note\",\"title\":\"Design note\",\"does\":")
                .contains("\"slot\":\"beforeProduction\",\"runBy\":\"researcher\",\"reviewed\":false}")
                .contains("\"slot\":\"beforeAcceptance\",\"runBy\":\"reviewer\",\"reviewed\":true}");
        assertThat(WorkflowDocument.read(document, 3)).isEqualTo(new WorkflowDocument.Reading.Read(TASKED));
        assertThat(WorkflowDocument.write(WorkflowDocument.readCurrent(document)))
                .isEqualTo(document);
    }

    @Test
    void theVersionTwoReaderRefusesATaskAndTheKeyReviewed() {
        String document = WorkflowDocument.write(TASKED);
        assertThat(WorkflowDocument.read(document, 2))
                .isInstanceOfSatisfying(
                        WorkflowDocument.Reading.Refused.class,
                        r -> assertThat(r.reason()).contains("unknown key \"reviewed\""));
        assertThat(WorkflowDocument.read(document, 1)).isInstanceOf(WorkflowDocument.Reading.Refused.class);
    }

    /** The rollback of ADR-0061: 1.14.x reads a version 3 pin as Unrunnable, and still runs versions 1 and 2. */
    @Test
    void release114ReadsAVersionThreePinAsUnrunnableAndKeepsRunningTheOthers() {
        assertThat(Reader114.pin(WorkflowDocument.write(TASKED), 3))
                .isInstanceOfSatisfying(MissionWorkflow.Unrunnable.class, u -> {
                    assertThat(u.schemaVersion()).isEqualTo(3);
                    assertThat(u.reason()).contains("newer Floor42");
                });
        assertThat(Reader114.pin(WorkflowDocument.write(WorkflowDocumentV2Test.CHECKED), 2))
                .isEqualTo(new MissionWorkflow.Pinned(WorkflowDocumentV2Test.CHECKED));
        assertThat(Reader114.pin(WorkflowDocument.write(Workflow.BUILT_IN), 1))
                .isEqualTo(new MissionWorkflow.Pinned(Workflow.BUILT_IN));
        assertThat(MissionWorkflow.of(WorkflowDocument.write(TASKED), 3)).isEqualTo(new MissionWorkflow.Pinned(TASKED));
    }

    @Test
    void aWorkflowWithoutProductionHoldsACommitteeOrATask() {
        assertThat(RESEARCH.opensOnStepApproval()).isTrue();
        assertThat(WorkflowDocument.read(WorkflowDocument.write(RESEARCH), 3))
                .isEqualTo(new WorkflowDocument.Reading.Read(RESEARCH));
        assertThatThrownBy(() -> new Workflow("Nothing", List.of(new Step.Approval(), new Step.Accept())))
                .hasMessageContaining("produces nothing");
        // The earlier readers keep their rule: a document without a task and a committee is refused there too.
        String bare = "{\"name\":\"x\",\"steps\":[{\"step\":\"approval\"},{\"step\":\"accept\"}]}";
        assertThat(WorkflowDocument.read(bare, 1)).isInstanceOf(WorkflowDocument.Reading.Refused.class);
        assertThat(WorkflowDocument.read(bare, 3)).isInstanceOf(WorkflowDocument.Reading.Refused.class);
    }

    @Test
    void theRulesOfATaskAreRefusedWithTheirCode() {
        assertThat(code(() -> task("a", Step.Task.Slot.BEFORE_ACCEPTANCE, WorkRole.DEVELOPER, false)))
                .isEqualTo("WRITING_STEP_UNREVIEWED");
        assertThat(code(() -> task("a", Step.Task.Slot.BEFORE_ACCEPTANCE, WorkRole.PLANNER, true)))
                .isEqualTo("STEP_RUNNER_NOT_ALLOWED");
        assertThat(code(() -> task("A b", Step.Task.Slot.BEFORE_ACCEPTANCE, WorkRole.REVIEWER, true)))
                .isEqualTo("STEP_KEY_INVALID");
        assertThat(code(() -> new Workflow(
                        "x",
                        List.of(
                                new Step.Approval(),
                                new Step.Committee(),
                                task("a", Step.Task.Slot.BEFORE_PRODUCTION, WorkRole.REVIEWER, true),
                                new Step.Accept()))))
                .isEqualTo("STEP_NEEDS_PRODUCTION");
        assertThat(code(() -> new Workflow(
                        "x",
                        List.of(
                                new Step.Approval(),
                                task("a", Step.Task.Slot.BEFORE_ACCEPTANCE, WorkRole.REVIEWER, true),
                                task("a", Step.Task.Slot.BEFORE_ACCEPTANCE, WorkRole.REVIEWER, true),
                                new Step.Accept()))))
                .isEqualTo("STEP_KEY_TAKEN");
        List<Step> five = new java.util.ArrayList<>(List.of(new Step.Approval()));
        for (int i = 0; i < 5; i++) five.add(task("t" + i, Step.Task.Slot.BEFORE_ACCEPTANCE, WorkRole.REVIEWER, true));
        five.add(new Step.Accept());
        assertThat(code(() -> new Workflow("x", five))).isEqualTo("TOO_MANY_STEPS");
    }

    @Test
    void aTaskTextCannotHideACharacter() {
        assertThat(code(() -> new Step.Task(
                        "a",
                        "Title",
                        "Do​it",
                        "why",
                        "out",
                        Step.Task.Slot.BEFORE_ACCEPTANCE,
                        WorkRole.REVIEWER,
                        true)))
                .isEqualTo("STEP_TEXT_HIDDEN_CHARACTER");
    }

    private static Step.Task task(String key, Step.Task.Slot slot, WorkRole by, boolean reviewed) {
        return new Step.Task(key, "Title", "Does", "Why", "Hands over", slot, by, reviewed);
    }

    private static String code(Runnable r) {
        try {
            r.run();
        } catch (StepRefused e) {
            return e.code();
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        return "accepted";
    }
}
