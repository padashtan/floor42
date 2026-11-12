package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The approval context Floor42 writes is read back as a code and values, the recorded sentence unchanged. */
class ApprovalScopeTest {

    @Test
    void theFourSentencesAreReadBackAsTheirCodes() {
        assertThat(ApprovalScope.read(ApprovalScope.context(
                        "git status", ApprovalScope.COMMAND + "dehren" + ApprovalScope.FOR_THE_MISSION)))
                .contains(new Phrase(ApprovalScope.CODE_COMMAND, Map.of("summary", "git status", "agent", "dehren")));
        assertThat(
                        ApprovalScope.read(
                                "src/App.java\nYour answer applies to every Write request from chris for the rest of this mission."))
                .contains(new Phrase(
                        ApprovalScope.CODE_TOOL, Map.of("summary", "src/App.java", "tool", "Write", "agent", "chris")));
        assertThat(ApprovalScope.read(
                        ApprovalScope.context("src/App.java", ApprovalScope.editedByDirector("src/App.java"))))
                .contains(new Phrase(
                        ApprovalScope.CODE_EDITED, Map.of("summary", "src/App.java", "path", "src/App.java")));
        assertThat(ApprovalScope.read(
                        ApprovalScope.context("ls\nYour answer applies to x.", ApprovalScope.askAlways())))
                .contains(
                        new Phrase(ApprovalScope.CODE_ASK_ALWAYS, Map.of("summary", "ls\nYour answer applies to x.")));
    }

    @Test
    void anyOtherTextHasNoCode() {
        assertThat(ApprovalScope.read(null)).isEmpty();
        assertThat(ApprovalScope.read("an agent wrote this.")).isEmpty();
        assertThat(ApprovalScope.read("ls\nYour answer applies to this exact command from dehren."))
                .isEmpty();
        assertThat(ApprovalScope.read("ls\nYour answer applies to everything for the rest of this mission."))
                .isEmpty();
    }
}
