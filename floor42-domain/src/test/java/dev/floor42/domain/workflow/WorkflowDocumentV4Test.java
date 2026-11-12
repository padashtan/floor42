package dev.floor42.domain.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Schema version 4 (ADR-0062): the graph mode, its reader and writer, and what a rollback reads. */
class WorkflowDocumentV4Test {

    static final Workflow GRAPH = Workflow.BUILT_IN.asGraph();

    @Test
    void aGraphWorkflowIsWrittenInVersionFourAndReadsBackIdentical() {
        assertThat(WorkflowDocument.versionOf(GRAPH)).isEqualTo(4);
        String document = WorkflowDocument.write(GRAPH);
        assertThat(document).startsWith("{\"name\":\"built-in\",\"mode\":\"graph\",\"steps\":[{\"step\":\"plan\"}");
        assertThat(WorkflowDocument.read(document, 4)).isEqualTo(new WorkflowDocument.Reading.Read(GRAPH));
        WorkflowDocument.Written w = WorkflowDocument.forJournal(GRAPH);
        assertThat(w.schemaVersion()).isEqualTo(4);
        assertThat(w.document()).isEqualTo(document);
    }

    @Test
    void aLoopWorkflowIsStillWrittenInTheLowestVersionByteForByte() {
        assertThat(WorkflowDocument.versionOf(Workflow.BUILT_IN)).isEqualTo(1);
        assertThat(WorkflowDocument.write(Workflow.BUILT_IN)).doesNotContain("mode");
        assertThat(WorkflowDocument.versionOf(WorkflowDocumentV3Test.TASKED)).isEqualTo(3);
        assertThat(WorkflowDocument.write(WorkflowDocumentV3Test.TASKED)).doesNotContain("mode");
    }

    @Test
    void aGraphWorkflowKeepsItsTasksAndChecks() {
        Workflow tasked = new Workflow("Tasked graph", WorkflowDocumentV3Test.TASKED.steps(), Workflow.Mode.GRAPH);
        assertThat(WorkflowDocument.read(WorkflowDocument.write(tasked), 4))
                .isEqualTo(new WorkflowDocument.Reading.Read(tasked));
    }

    @Test
    void theModeIsRequiredAndOnlyGraphInVersionFour() {
        String steps = "\"steps\":[{\"step\":\"plan\"},{\"step\":\"approval\"},{\"step\":\"produce\"},"
                + "{\"step\":\"review\"},{\"step\":\"accept\"}]";
        assertThat(WorkflowDocument.read("{\"name\":\"x\"," + steps + "}", 4))
                .isInstanceOf(WorkflowDocument.Reading.Refused.class);
        assertThat(WorkflowDocument.read("{\"name\":\"x\",\"mode\":\"loop\"," + steps + "}", 4))
                .isInstanceOf(WorkflowDocument.Reading.Refused.class);
        assertThat(WorkflowDocument.read("{\"name\":\"x\",\"mode\":\"graph\"," + steps + "}", 4))
                .isInstanceOf(WorkflowDocument.Reading.Read.class);
    }

    @Test
    void earlierReadersRefuseTheModeKey() {
        String document = WorkflowDocument.write(GRAPH);
        for (int v = 1; v <= 3; v++) {
            assertThat(WorkflowDocument.read(document, v))
                    .isInstanceOfSatisfying(
                            WorkflowDocument.Reading.Refused.class,
                            r -> assertThat(r.reason()).contains("unknown key \"mode\""));
        }
    }

    @Test
    void theGraphModeNeedsThePlanStep() {
        Workflow noPlan = WorkflowDocumentV3Test.RESEARCH;
        assertThatThrownBy(() -> new Workflow(noPlan.name(), noPlan.steps(), Workflow.Mode.GRAPH))
                .hasMessageContaining("plan");
    }

    @Test
    void theLibraryNeverHoldsTheMode() {
        assertThat(WorkflowDocument.LIBRARY).isEqualTo(3);
        assertThatThrownBy(() -> WorkflowDocument.readCurrent(WorkflowDocument.write(GRAPH)))
                .hasMessageContaining("mode");
    }

    @Test
    void aGraphNodeGetsOneReworkInAllWhileTheLoopCountsEachApart() {
        assertThat(List.of(Workflow.BUILT_IN.reviewRejectionCap(), Workflow.BUILT_IN.checkRejectionCap()))
                .containsExactly(2, 3);
        assertThat(List.of(GRAPH.reviewRejectionCap(), GRAPH.checkRejectionCap()))
                .containsExactly(2, 2);
        assertThat(GRAPH.reworksTogether()).isTrue();
        assertThat(Workflow.BUILT_IN.reworksTogether()).isFalse();
    }

    /** The rollback of ADR-0062: 1.15.x reads a version 4 pin as Unrunnable, and still runs versions 1 to 3. */
    @Test
    void release115ReadsAVersionFourPinAsUnrunnableAndKeepsRunningTheOthers() {
        assertThat(Reader115.pin(WorkflowDocument.write(GRAPH), 4))
                .isInstanceOfSatisfying(MissionWorkflow.Unrunnable.class, u -> {
                    assertThat(u.schemaVersion()).isEqualTo(4);
                    assertThat(u.reason()).contains("newer Floor42");
                });
        assertThat(Reader115.pin(WorkflowDocument.write(WorkflowDocumentV3Test.TASKED), 3))
                .isEqualTo(new MissionWorkflow.Pinned(WorkflowDocumentV3Test.TASKED));
        assertThat(MissionWorkflow.of(WorkflowDocument.write(GRAPH), 4)).isEqualTo(new MissionWorkflow.Pinned(GRAPH));
    }
}
