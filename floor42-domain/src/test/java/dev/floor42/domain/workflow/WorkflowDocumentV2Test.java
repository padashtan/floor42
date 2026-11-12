package dev.floor42.domain.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.workflow.Workflow.Step;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Schema version 2 (ADR-0052, increment 3): the director's checks, its reader, its writer and what a rollback reads. */
class WorkflowDocumentV2Test {

    static final Step.Check SECURITY = new Step.Check(
            "security-check",
            "Security check",
            "Look for secrets and unsafe deserialisation.\nName each file.",
            "Our customer contract requires it",
            "A verdict and the findings, file by file",
            true);
    static final Step.Check WORDING = new Step.Check(
            "wording-pass", "Wording pass", "Read the texts", "Clear texts", "The passages to reword", false);
    static final Workflow CHECKED = new Workflow(
            "Release with checks",
            List.of(
                    new Step.Plan(3),
                    new Step.Approval(),
                    new Step.Produce(),
                    new Step.Review(),
                    SECURITY,
                    WORDING,
                    new Step.Accept(),
                    new Step.Retro()));

    @Test
    void aWorkflowWithChecksIsWrittenInVersionTwoCanonicallyAndReadsBackIdentical() {
        assertThat(WorkflowDocument.versionOf(CHECKED)).isEqualTo(2);
        String document = WorkflowDocument.write(CHECKED);
        assertThat(document)
                .isEqualTo("{\"name\":\"Release with checks\",\"steps\":[{\"step\":\"plan\",\"maxTickets\":3},"
                        + "{\"step\":\"approval\"},{\"step\":\"produce\"},{\"step\":\"review\"},"
                        + "{\"step\":\"check\",\"key\":\"security-check\",\"title\":\"Security check\","
                        + "\"does\":\"Look for secrets and unsafe deserialisation.\\nName each file.\","
                        + "\"why\":\"Our customer contract requires it\","
                        + "\"handsOver\":\"A verdict and the findings, file by file\",\"slot\":\"checks\","
                        + "\"runBy\":\"reviewer\",\"blocks\":true},"
                        + "{\"step\":\"check\",\"key\":\"wording-pass\",\"title\":\"Wording pass\",\"does\":\"Read the"
                        + " texts\",\"why\":\"Clear texts\",\"handsOver\":\"The passages to reword\",\"slot\":\"checks\","
                        + "\"runBy\":\"reviewer\",\"blocks\":false},{\"step\":\"accept\"},{\"step\":\"retro\"}]}");
        assertThat(WorkflowDocument.read(document, 2)).isEqualTo(new WorkflowDocument.Reading.Read(CHECKED));
        assertThat(WorkflowDocument.write(WorkflowDocument.readCurrent(document)))
                .isEqualTo(document);
    }

    /** The writer writes the lowest version: Floor42's steps only stay version 1, byte for byte. */
    @Test
    void aWorkflowOfFloor42sStepsOnlyIsStillVersionOneAndTheVersionTwoReaderReadsItTheSame() {
        String v1 = WorkflowDocument.write(Workflow.BUILT_IN);
        assertThat(WorkflowDocument.versionOf(Workflow.BUILT_IN)).isEqualTo(1);
        assertThat(v1)
                .isEqualTo(
                        "{\"name\":\"built-in\",\"steps\":[{\"step\":\"plan\"},{\"step\":\"approval\"},{\"step\":\"committee\"},"
                                + "{\"step\":\"produce\"},{\"step\":\"review\"},{\"step\":\"accept\"},{\"step\":\"retro\"}]}");
        assertThat(WorkflowDocument.read(v1, 2)).isEqualTo(WorkflowDocument.read(v1, 1));
    }

    /**
     * ADR-0052, section 4: from increment 3 back to 1.13.x, a version 2 pin reads as Unrunnable (shown, never run),
     * with 1.13.0's own reader; a version 2 document mislabelled as version 1 is refused by the frozen v1 reader too.
     */
    @Test
    void aVersionTwoPinReadsAsUnrunnableOnTheReaderOf1130() {
        String document = WorkflowDocument.write(CHECKED);
        MissionWorkflow rolledBack = Reader113.pin(document, 2);
        assertThat(rolledBack).isInstanceOf(MissionWorkflow.Unrunnable.class);
        assertThat(rolledBack.runnable()).isEmpty();
        assertThat(((MissionWorkflow.Unrunnable) rolledBack).reason()).contains("workflow schema 2", "upgrade");
        assertThat(Reader113.pin(document, 1)).isInstanceOf(MissionWorkflow.Unrunnable.class);
        assertThat(WorkflowDocument.read(document, 1)).isInstanceOf(WorkflowDocument.Reading.Refused.class);
        // And every version 1 document, the built-in pin included, reads on 1.13.0 as it does here.
        for (WorkflowTemplate t : WorkflowTemplate.ALL) {
            String text = WorkflowDocument.write(t.workflow());
            int version = WorkflowDocument.versionOf(t.workflow());
            assertThat(Reader113.pin(text, version).runnable().isPresent()).isEqualTo(version == 1);
            assertThat(MissionWorkflow.of(text, version).runnable()).contains(t.workflow());
        }
    }

    @Test
    void theVersionTwoReaderRefusesWhatOnlyVersionThreeWillHold() {
        String document = WorkflowDocument.write(CHECKED);
        assertThat(reason(document.replace("{\"step\":\"accept\"}", "{\"step\":\"task\"},{\"step\":\"accept\"}")))
                .contains("Unknown step \"task\"");
        assertThat(reason(document.replace(
                        "\"step\":\"check\",\"key\":\"wording-pass\"", "\"step\":\"task\",\"key\":\"w\"")))
                .contains("Only a check");
        assertThat(reason(document.replace("\"blocks\":false}", "\"blocks\":false,\"reviewed\":true}")))
                .contains("unknown key \"reviewed\"");
        assertThat(reason(document.replace(
                        "\"slot\":\"checks\",\"runBy\":\"reviewer\",\"blocks\":true",
                        "\"slot\":\"beforeProduction\",\"runBy\":\"reviewer\",\"blocks\":true")))
                .contains("checks slot");
        assertThat(reason(document.replace("{\"step\":\"accept\"}", "{\"step\":\"accept\",\"autoAccept\":true}")))
                .contains("Auto-acceptance");
        assertThat(reason(document.replace(",\"blocks\":false}", "}"))).contains("no \"blocks\"");
        assertThat(reason(document.replace("{\"step\":\"retro\"}", "{\"step\":\"retro\",\"title\":\"x\"}")))
                .contains("Only a check");
    }

    @Test
    void aCheckRunByAnyoneButAReviewerIsRefusedWithItsCode() {
        String document =
                WorkflowDocument.write(CHECKED).replaceFirst("\"runBy\":\"reviewer\"", "\"runBy\":\"developer\"");
        assertThat(reason(document)).contains("run by a reviewer");
        assertThatThrownBy(() -> WorkflowDocument.readCurrent(document))
                .isInstanceOf(StepRefused.class)
                .satisfies(e -> assertThat(((StepRefused) e).code()).isEqualTo("STEP_RUNNER_NOT_ALLOWED"));
    }

    @Test
    void theStepRulesAreTheConstructorsWithTheirCodes() {
        assertThat(code(() -> new Workflow(
                        "w", List.of(new Step.Approval(), new Step.Committee(), SECURITY, new Step.Accept()))))
                .isEqualTo("STEP_NEEDS_PRODUCTION");
        List<Step> five = new ArrayList<>(List.of(new Step.Approval(), new Step.Produce(), new Step.Review()));
        for (int i = 1; i <= 5; i++) five.add(new Step.Check("c" + i, "C" + i, "d", "w", "h", true));
        five.add(new Step.Accept());
        assertThat(code(() -> new Workflow("w", five))).isEqualTo("TOO_MANY_STEPS");
        assertThat(code(() -> new Workflow(
                        "w",
                        List.of(
                                new Step.Approval(),
                                new Step.Produce(),
                                new Step.Review(),
                                SECURITY,
                                SECURITY,
                                new Step.Accept()))))
                .isEqualTo("STEP_KEY_TAKEN");
        assertThat(code(() -> new Step.Check("Not A Slug", "t", "d", "w", "h", true)))
                .isEqualTo("STEP_KEY_INVALID");
        // A check before the review is out of Floor42's order.
        assertThatThrownBy(() -> new Workflow(
                        "w",
                        List.of(
                                new Step.Approval(),
                                new Step.Produce(),
                                SECURITY,
                                new Step.Review(),
                                new Step.Accept())))
                .hasMessageContaining("Floor42's order");
    }

    @Test
    void aVersionTwoDocumentMayBeFourTimesLongerButNoDeeper() {
        String longText = "x".repeat(1_990);
        List<Step> steps = new ArrayList<>(List.of(new Step.Approval(), new Step.Produce(), new Step.Review()));
        for (int i = 1; i <= 4; i++) steps.add(new Step.Check("c" + i, "C" + i, longText, "w", "h", true));
        steps.add(new Step.Accept());
        Workflow w = new Workflow("Long", steps);
        String document = WorkflowDocument.write(w);
        assertThat(document.length()).isGreaterThan(Json.MAX_LENGTH).isLessThan(WorkflowDocumentV2.MAX_LENGTH);
        assertThat(WorkflowDocument.read(document, 2)).isEqualTo(new WorkflowDocument.Reading.Read(w));
        assertThat(reason("{\"name\":\"" + "x".repeat(WorkflowDocumentV2.MAX_LENGTH) + "\",\"steps\":[]}"))
                .contains("longer than 32768");
        assertThat(reason("{\"name\":\"n\",\"steps\":[{\"step\":\"check\",\"key\":{\"a\":[{}]}}]}"))
                .contains("nests more than 4");
    }

    private static String reason(String document) {
        return switch (WorkflowDocument.read(document, 2)) {
            case WorkflowDocument.Reading.Refused r -> r.reason();
            case WorkflowDocument.Reading.Read r -> "read: " + r.workflow();
        };
    }

    private static String code(Runnable r) {
        try {
            r.run();
            return "accepted";
        } catch (StepRefused e) {
            // Handled: the code is what the test reads.
            return e.code();
        }
    }
}
