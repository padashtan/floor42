package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.Committee;
import dev.floor42.domain.ConsultationKind;
import dev.floor42.domain.Lesson;
import dev.floor42.domain.StaffingReview;
import dev.floor42.domain.WorkRole;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each parser of an agent's answer, on its edge cases; the golden master covers the texts of the journals. */
class BriefParsersTest {

    @Test
    void aSummaryReadsTheLastDecoratedLinesAndNoneAsNothing() {
        Briefs.Summary s = BriefParsers.summary(
                "PROPOSAL: first\n- **Proposal**: a gateway\nCRITICAL OBJECTION: N/A\nQUESTIONS: who pays? ||  || when?");
        assertThat(s.proposal()).isEqualTo("a gateway");
        assertThat(s.criticalObjection()).isNull();
        assertThat(s.questions()).containsExactly("who pays?", "when?");
        assertThat(BriefParsers.summary("QUESTIONS: none.").questions()).isEmpty();
    }

    @Test
    void theToolsEachTicketNeedsLineUpWithThePlansTickets() {
        String plan = """
                NEEDS: orphan
                TICKET: Describe the project
                DONE WHEN: summary written
                NEEDS: none
                TICKET: **Render the PDF**
                NEEDS: chromium
                - **NEEDS**: headless Chrome, pdftotext (poppler); python3, Chrome, a careful review
                TICKET: List the risks
                """;
        assertThat(BriefParsers.plan(plan)).hasSize(3);
        assertThat(BriefParsers.toolNeeds(plan))
                .as("one entry per ticket of plan(); the last NEEDS line wins, aliases fold, repeats and sentences go")
                .containsExactly(List.of(), List.of("chromium", "pdftotext", "python3"), List.of());
    }

    @Test
    void clarificationsStartAtEachClarifyAndIgnoreWhatComesBefore() {
        List<Committee.Clarification> asked = BriefParsers.clarifications("""
                CONTEXT: orphan
                CLARIFY: Who are the users?
                CONTEXT: the gateway depends on it
                OPTIONS: staff | customers |
                RECOMMENDED: none
                CLARIFY: Budget?
                OPTIONS: -
                RECOMMENDED: 10k
                """);
        assertThat(asked)
                .containsExactly(
                        new Committee.Clarification(
                                "Who are the users?", "the gateway depends on it", List.of("staff", "customers"), null),
                        new Committee.Clarification("Budget?", null, List.of(), "10k"));
        assertThat(BriefParsers.clarifications(null)).isEmpty();
    }

    @Test
    void aPlanKeepsOnlyEarlierDependenciesAndTheSizeInTheDescription() {
        var plan = BriefParsers.plan("""
                WHY: before any ticket
                TICKET: First
                WHY: lay the ground
                SIZE: S
                TICKET: Second
                DEPENDS ON: 1, 1, 2, 7, 0
                DONE WHEN: works; "a; b" holds; (c; d)
                TICKET:
                """);
        assertThat(plan).hasSize(2);
        assertThat(plan.get(0).description()).isEqualTo("lay the ground (size S)");
        assertThat(plan.get(1).dependsOn()).containsExactly(0);
        assertThat(plan.get(1).acceptanceCriteria()).containsExactly("works", "\"a; b\" holds", "(c; d)");
    }

    @Test
    void helpIsTheLastUsableLineAndAConsultationNeedsARole() {
        assertThat(BriefParsers.help("CONSULT: architect: is it safe?\nCONSULT: no role"))
                .contains(new Briefs.Help(ConsultationKind.CONSULT, "architect", "is it safe?"));
        assertThat(BriefParsers.help("RESEARCH: the RFC?\nCONSULT: Security : the token?"))
                .contains(new Briefs.Help(ConsultationKind.CONSULT, "security", "the token?"));
        assertThat(BriefParsers.help("CONSULT: architect:")).isEmpty();
    }

    @Test
    void aLessonIsForTheRoleItNamesOrForEveryone() {
        Briefs.RetroConclusion r = BriefParsers.retroConclusion(
                "CONCLUSION: keep going\nLESSON: reviewer: check twice\nLESSON: all: be short\nLESSON: note: this\n"
                        + "LESSON: developer:\nWENT WELL: ignored here");
        assertThat(r.conclusions()).containsExactly("keep going");
        assertThat(r.lessons())
                .containsExactly(
                        new Lesson.Proposal(WorkRole.REVIEWER, "check twice"),
                        new Lesson.Proposal(null, "be short"),
                        new Lesson.Proposal(null, "note: this"));
        assertThat(BriefParsers.retroLines("went well: **fast**\nWENT WELL:", "WENT WELL"))
                .containsExactly("fast");
    }

    @Test
    void staffingAdviceClampsTheParallelSessionsAndCapsTheHires() {
        Briefs.StaffingAdvice a = BriefParsers.staffingAdvice(
                "VERDICT: NO\nVERDICT: hire them\nHIRE: Ada | Backend developer\nHIRE: Bob\nHIRE: | nobody\nHIRE: Cy\n"
                        + "HIRE: Dee\nPARALLEL: 9\nPARALLEL: none");
        assertThat(a.worthIt()).isTrue();
        assertThat(a.hires())
                .hasSize(StaffingReview.MAX_HIRES)
                .first()
                .isEqualTo(new StaffingReview.Hire("Ada", "Backend developer"));
        assertThat(a.hires().get(1).title()).isEqualTo("Developer");
        assertThat(a.parallel()).isEqualTo(4);
        assertThat(BriefParsers.staffingAdvice(null)).isEqualTo(new Briefs.StaffingAdvice(false, List.of(), null));
    }

    @Test
    void aTemplateRefusesAMissingOrAnUnusedValueAndNeverReadsAValueAsAPlaceholder() {
        String researched = BriefTemplate.named("researcher")
                .with("name", "Ada")
                .with("role", "{{role}}")
                .with("question", "$1?")
                .render();
        assertThat(researched)
                .startsWith("You are Ada ({{role}}), an agent of Floor42")
                .contains("$1?");
        assertThatThrownBy(() -> BriefTemplate.named("researcher")
                        .with("name", "Ada")
                        .with("role", "r")
                        .render())
                .hasMessageContaining("no value for {{question}}");
        assertThatThrownBy(() -> BriefTemplate.named("researcher")
                        .with("name", "a")
                        .with("role", "r")
                        .with("question", "q")
                        .with("extra", "x")
                        .render())
                .hasMessageContaining("unused values [extra]");
    }
}
