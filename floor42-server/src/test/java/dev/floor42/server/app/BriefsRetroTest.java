package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Lesson;
import dev.floor42.domain.WorkRole;
import org.junit.jupiter.api.Test;

/** Reading a retrospective's closing lines (ADR-0020): what is not stated is not invented. */
class BriefsRetroTest {

    @Test
    void aParticipantsLinesAreReadAsWritten() {
        String text = """
                Some analysis first.
                **WENT WELL**: Reviews were quick
                WENT BADLY: Two sessions failed
                - WENT BADLY: The shell waited for approvals
                """;
        assertThat(Briefs.retroLines(text, "WENT WELL")).containsExactly("Reviews were quick");
        assertThat(Briefs.retroLines(text, "WENT BADLY"))
                .containsExactly("Two sessions failed", "The shell waited for approvals");
        assertThat(Briefs.retroLines("nothing marked", "WENT WELL")).isEmpty();
    }

    @Test
    void aLessonIsForTheRoleItNamesElseForEveryone() {
        Briefs.RetroConclusion out = Briefs.retroConclusion("""
                CONCLUSION: Keep tickets small
                LESSON: reviewer: Run the checks before the verdict
                LESSON: all: Say what was not verified
                LESSON: Note: keep reports short
                """);
        assertThat(out.conclusions()).containsExactly("Keep tickets small");
        assertThat(out.lessons())
                .containsExactly(
                        new Lesson.Proposal(WorkRole.REVIEWER, "Run the checks before the verdict"),
                        new Lesson.Proposal(null, "Say what was not verified"),
                        // "Note" is no role: the whole line is the lesson, for everyone.
                        new Lesson.Proposal(null, "Note: keep reports short"));
    }
}
