package dev.floor42.domain.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.workflow.Workflow.Step;
import dev.floor42.domain.workflow.WorkflowDocument.Reading;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The workflow document and its schema-1 reader (ADR-0050): canonical, bounded, never throwing. */
class WorkflowDocumentTest {

    private static final String BUILT_IN_DOC =
            "{\"name\":\"built-in\",\"steps\":[{\"step\":\"plan\"},{\"step\":\"approval\"},"
                    + "{\"step\":\"committee\"},{\"step\":\"produce\"},{\"step\":\"review\"},{\"step\":\"accept\"},"
                    + "{\"step\":\"retro\"}]}";

    @Test
    void theBuiltInWorkflowWrittenAndReadBackIsTheBuiltInWorkflow() {
        assertThat(WorkflowDocument.write(Workflow.BUILT_IN)).isEqualTo(BUILT_IN_DOC);
        assertThat(WorkflowDocument.read(BUILT_IN_DOC, 1)).isEqualTo(new Reading.Read(Workflow.BUILT_IN));
    }

    @Test
    void aSettingAtItsDefaultIsLeftOutAndAnyOtherIsWritten() {
        Workflow light = new Workflow(
                "Light",
                List.of(
                        new Step.Plan(3),
                        new Step.Approval(),
                        new Step.Produce(),
                        new Step.Review(),
                        new Step.Accept()));
        String doc = WorkflowDocument.write(light);

        assertThat(doc).contains("{\"step\":\"plan\",\"maxTickets\":3}");
        assertThat(WorkflowDocument.readCurrent(doc)).isEqualTo(light);
        assertThat(WorkflowDocument.readCurrent(
                                " {\n \"steps\" : [{\"maxTickets\":8,\"step\":\"plan\"},{\"step\":\"approval\"},{\"step\":\"committee\"},{\"step\":\"accept\"}"
                                        + "],\"name\":\"x\"}")
                        .name())
                .isEqualTo("x");
    }

    @Test
    void aNameWithQuotesAndEscapesRoundTrips() {
        Workflow w = new Workflow("Say \"hi\"\\ \u00e9t\u00e9", Workflow.BUILT_IN.steps());
        assertThat(WorkflowDocument.readCurrent(WorkflowDocument.write(w))).isEqualTo(w);
        assertThat(WorkflowDocument.readCurrent("{\"name\":\"\\u0041b\",\"steps\":" + steps() + "}")
                        .name())
                .isEqualTo("Ab");
    }

    @Test
    void anotherSchemaVersionIsReadAsARefusalNeverAsAWorkflow() {
        assertThat(WorkflowDocument.read("anything at all", 5))
                .isEqualTo(new Reading.Refused(
                        "Written by a newer Floor42 (workflow schema 5; this build reads 4): upgrade to run it"));
        assertThat(reason("{}", 0)).contains("schema version 0 is invalid");
        assertThat(reason("{}", -1)).contains("invalid").doesNotContain("newer");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "   ",
                "nul",
                "{",
                "{\"name\":\"x\"",
                "{\"name\":\"x\",}",
                "{\"name\" \"x\"}",
                "[1,2",
                "{\"name\":\"x\\q\"}",
                "{\"name\":\"x\\u12G4\"}",
                "{\"name\":\"x\\u12\"}",
                "{\"name\":\"x\u0001\"}",
                "{\"name\":\"x\"} trailing",
                "{\"name\":-1}",
                "{\"name\":1.5}",
                "{\"name\":123}",
                "{\"name\":99999999999999999999}",
                "\"unclosed",
                "\"\\"
            })
    void malformedTextIsARefusalAndNoJdkExceptionLeaves(String text) {
        assertThat(WorkflowDocument.read(text, 1)).isInstanceOf(Reading.Refused.class);
    }

    @Test
    void aKeyGivenTwiceIsRefused() {
        assertThat(reason("{\"name\":\"a\",\"name\":\"b\",\"steps\":[]}", 1)).contains("given twice");
    }

    @Test
    void unknownKeysStepsAndSettingsAreRefusedByName() {
        assertThat(reason("{\"name\":\"a\",\"steps\":" + steps() + ",\"colour\":\"red\"}", 1))
                .contains("unknown key \"colour\"");
        assertThat(reason("{\"name\":\"a\",\"steps\":[{\"step\":\"deploy\"}]}", 1))
                .contains("Unknown step \"deploy\"");
        assertThat(reason("{\"name\":\"a\",\"steps\":[{\"step\":\"review\",\"maxTickets\":2}]}", 1))
                .contains("Only the plan step has maxTickets");
        assertThat(reason("{\"name\":\"a\",\"steps\":[{\"step\":\"accept\",\"autoAccept\":{}}]}", 1))
                .contains("Auto-acceptance is not built yet");
        assertThat(reason("{\"name\":\"a\",\"steps\":[{\"step\":\"plan\",\"maxTickets\":9}]}", 1))
                .contains("from 1 to 8");
        assertThat(reason("{\"name\":\"a\",\"steps\":[{\"step\":\"plan\",\"maxTickets\":0}]}", 1))
                .contains("from 1 to 8");
        assertThat(reason("{\"name\":\"a\",\"steps\":[{\"step\":\"plan\",\"maxTickets\":\"3\"}]}", 1))
                .contains("from 1 to 8");
        assertThat(reason("{\"name\":\"a\",\"steps\":[{}]}", 1)).contains("no kind");
        assertThat(reason("{\"name\":\"a\",\"steps\":{}}", 1)).contains("no steps");
        assertThat(reason("{\"steps\":[]}", 1)).contains("no name");
        assertThat(reason("[]", 1)).contains("not an object");
    }

    @Test
    void theWorkflowsInvariantsAreCheckedAgainWhenADocumentIsRead() {
        assertThat(reason(doc("approval", "produce", "accept"), 1)).contains("Produced work is reviewed");
        assertThat(reason(doc("approval", "review", "accept"), 1)).contains("A review reads produced work");
        assertThat(reason(doc("plan", "approval", "accept"), 1)).contains("produces nothing holds a committee");
        assertThat(reason(doc("approval", "plan", "committee", "accept"), 1)).contains("cannot come before");
        assertThat(reason(doc("approval", "committee", "committee", "accept"), 1))
                .contains("cannot come before");
        assertThat(reason(doc("plan", "committee", "produce", "review", "accept"), 1))
                .contains("director's approval");
        assertThat(reason("{\"name\":\" \",\"steps\":" + steps() + "}", 1)).contains("has a name");
        assertThat(WorkflowDocument.read(doc("approval", "committee", "accept"), 1))
                .isInstanceOf(Reading.Read.class);
    }

    /** Counted before descending: a document nested deeper than four levels never reaches the stack's end. */
    @Test
    void deepNestingAndLongDocumentsAreRefusedBeforeTheyAreRead() {
        assertThat(reason("[[[[1]]]]", 1)).doesNotContain("nests");
        assertThat(reason("[[[[[1]]]]]", 1)).contains("nests more than 4 levels");
        assertThat(reason("[".repeat(100_000), 1)).contains("longer than 8192");
        assertThat(reason("[".repeat(5_000), 1)).contains("nests more than 4 levels");
        assertThat(reason("{\"name\":\"" + "a".repeat(9_000) + "\"}", 1)).contains("longer than 8192");
    }

    @Test
    void readCurrentThrowsTheReasonForTheEditor() {
        assertThatThrownBy(() -> WorkflowDocument.readCurrent(doc("approval", "produce", "accept")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Produced work is reviewed");
    }

    @Test
    void aPlansLimitIsOneToEight() {
        assertThat(new Step.Plan().maxTickets()).isEqualTo(8);
        assertThatThrownBy(() -> new Step.Plan(9)).hasMessageContaining("1 to 8");
        assertThatThrownBy(() -> new Step.Plan(0)).hasMessageContaining("1 to 8");
        assertThat(new Workflow("d", List.of(new Step.Approval(), new Step.Committee(), new Step.Accept()))
                        .maxTickets())
                .isEqualTo(8);
    }

    private static String reason(String text, int version) {
        Reading r = WorkflowDocument.read(text, version);
        assertThat(r).isInstanceOf(Reading.Refused.class);
        return ((Reading.Refused) r).reason();
    }

    private static String steps() {
        return "[{\"step\":\"approval\"},{\"step\":\"committee\"},{\"step\":\"accept\"}]";
    }

    private static String doc(String... kinds) {
        StringBuilder s = new StringBuilder("{\"name\":\"t\",\"steps\":[");
        for (int i = 0; i < kinds.length; i++)
            s.append(i == 0 ? "" : ",").append("{\"step\":\"").append(kinds[i]).append("\"}");
        return s.append("]}").toString();
    }
}
