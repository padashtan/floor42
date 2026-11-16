package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DEHREN;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A step the environment prevents stops the review loop: the director gets one question that names what is missing. */
@ExtendWith(GoldenFixtureExtension.class)
class EnvironmentBlockerTest {

    static final String REPORT = "Wrote tools/render.cmd and the two sources.\n"
            + "PARTIAL: the PDFs are not rendered.\n"
            + "**BLOCKED BY ENVIRONMENT:** a desktop browser; step: render report.pdf with tools/render.cmd\n";

    /** A reviewer that confirms the blocker is the only issue left, its full reason longer than any old cut. */
    static final String ONLY = "PDFs missing: the browser cannot start in the sandbox. " + "x".repeat(700)
            + "\nENVIRONMENT ONLY: yes\nVERDICT: REJECTED";

    final Fixture f = new Fixture();

    @Test
    void theMarkerLineNamesWhatIsMissingAndTheStep() {
        assertThat(EnvironmentBlocker.in(REPORT))
                .contains(new EnvironmentBlocker("a desktop browser", "render report.pdf with tools/render.cmd"));
        assertThat(EnvironmentBlocker.in("BLOCKED BY ENVIRONMENT: the network"))
                .contains(new EnvironmentBlocker("the network", null));
        assertThat(EnvironmentBlocker.in("All done.")).isEmpty();
        assertThat(EnvironmentBlocker.in("BLOCKED BY ENVIRONMENT:   ")).isEmpty();
        assertThat(EnvironmentBlocker.in(null)).isEmpty();
        // Only a line that opens with the marker counts, and the brief's template line names nothing.
        assertThat(EnvironmentBlocker.in("I could write BLOCKED BY ENVIRONMENT: a browser here"))
                .isEmpty();
        assertThat(EnvironmentBlocker.in("> BLOCKED BY ENVIRONMENT: a browser")).isEmpty();
        assertThat(EnvironmentBlocker.in("BLOCKED BY ENVIRONMENT: <what is missing>; step: <the step it prevents>"))
                .isEmpty();
    }

    @Test
    void aNegatedWhatAndAStepEndingInAPeriodReadCleanlyInTheQuestionTemplate() {
        assertThat(EnvironmentBlocker.in("BLOCKED BY ENVIRONMENT: no network, so Maven cannot fetch ...; step: the"
                        + " ./mvnw -q test step in PROVE.md."))
                .contains(new EnvironmentBlocker(
                        "network, so Maven cannot fetch ...", "the ./mvnw -q test step in PROVE.md"));
        assertThat(EnvironmentBlocker.in("BLOCKED BY ENVIRONMENT: Without a display; step: render."))
                .contains(new EnvironmentBlocker("a display", "render"));
    }

    @Test
    void onlyTheReviewersOwnLineSaysTheBlockerIsAllThatIsLeft() {
        assertThat(EnvironmentBlocker.onlyIssue(ONLY)).isTrue();
        assertThat(EnvironmentBlocker.onlyIssue("**ENVIRONMENT ONLY:** yes")).isTrue();
        assertThat(EnvironmentBlocker.onlyIssue("ENVIRONMENT ONLY: no")).isFalse();
        assertThat(EnvironmentBlocker.onlyIssue("PDFs missing")).isFalse();
        assertThat(EnvironmentBlocker.onlyIssue(null)).isFalse();
    }

    @Test
    void aRejectionOfAMarkedReportForARealDefectSendsItBackAsUsual() {
        TicketId t = submitted(REPORT + "ENVIRONMENT ONLY: yes\n");

        f.record(f.company
                .tickets()
                .review(
                        f.agent(DEHREN),
                        t,
                        ReviewVerdict.REJECTED,
                        "render.cmd has a syntax error\nVERDICT: REJECTED"));

        assertThat(status(t)).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(f.company.desk().open()).isEmpty();
    }

    @Test
    void aFailedReviewSessionOfAMarkedReportKeepsRequestAnotherReview() {
        TicketId t = submitted(REPORT);

        f.record(f.company
                .tickets()
                .review(f.agent(DEHREN), t, ReviewVerdict.UNVERIFIABLE, "The review session failed: exit code 3"));

        Question q = f.company.desk().open().getFirst();
        assertThat(q.phrase().code()).isEqualTo("reviewUnverifiable");
        assertThat(q.options()).contains("Request another review");
    }

    @Test
    void aRejectionOfABlockedReportAsksTheDirectorInsteadOfReopening() {
        TicketId t = submitted(REPORT);

        f.record(f.company.tickets().review(f.agent(DEHREN), t, ReviewVerdict.REJECTED, ONLY));

        assertThat(status(t)).isEqualTo(TicketStatus.IN_REVIEW);
        Question q = f.company.desk().open().getFirst();
        assertThat(q.kind()).isEqualTo(QuestionKind.REVIEW_ESCALATION);
        assertThat(q.blocking()).isTrue();
        assertThat(q.subject()).contains("a desktop browser");
        assertThat(q.phrase().code()).isEqualTo("environmentBlocked");
        assertThat(q.phrase().params())
                .containsEntry("missing", "a desktop browser")
                .containsEntry("step", "render report.pdf with tools/render.cmd")
                .containsEntry("verdict", "REJECTED")
                .containsEntry("findings", ONLY);
        assertThat(q.context()).contains(ONLY);
        assertThat(q.options())
                .containsExactly(
                        "I do the step myself",
                        "I allow an exception",
                        "I install what is missing",
                        "Accept as is",
                        "Send back to work");
    }

    @Test
    void dockerBlockedSaysTheSandboxCannotReachTheDaemonAndOffersNoInstall() {
        TicketId t = submitted("Wrote the Dockerfile.\nBLOCKED BY ENVIRONMENT: Docker; step: docker build -t app .\n");

        f.record(f.company.tickets().review(f.agent(DEHREN), t, ReviewVerdict.REJECTED, ONLY));

        Question q = f.company.desk().open().getFirst();
        assertThat(q.phrase().code()).isEqualTo("environmentBlockedDocker");
        assertThat(q.phrase().params())
                .containsEntry("missing", "Docker")
                .containsEntry("step", "docker build -t app .");
        assertThat(q.context()).contains("cannot reach the Docker daemon", "installing Docker will not help");
        assertThat(q.options()).containsExactly("I do the step myself", "Accept as is", "Send back to work");
        assertThat(EnvironmentBlocker.in("BLOCKED BY ENVIRONMENT: docker daemon")
                        .orElseThrow()
                        .docker())
                .isTrue();
        assertThat(EnvironmentBlocker.in("BLOCKED BY ENVIRONMENT: dockerfile-lint")
                        .orElseThrow()
                        .docker())
                .isFalse();
    }

    @Test
    void theDirectorsDockerStepForAnotherTicketLeavesThisTicketsReviewAsUsual() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        f.record(f.company
                .desk()
                .ask(
                        f.system(),
                        m,
                        "The project needs Docker",
                        "",
                        java.util.List.of("I do the step myself", "Go on without Docker"),
                        null,
                        Criticality.HIGH,
                        true,
                        null,
                        Phrase.of("dockerUnreachable", "tools", "docker", "tickets", "T-99")));
        Question docker = f.company.desk().open().getFirst();
        f.record(f.company.desk().answer(f.director(), docker.id(), "I do the step myself"));
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, "Wrote the notes."));

        f.record(f.company.tickets().review(f.agent(DEHREN), t, ReviewVerdict.REJECTED, "notes.md is empty"));

        assertThat(status(t)).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(f.company.desk().open()).isEmpty();
        assertThat(EnvironmentBlocker.missionStep(f.company.desk().of(m))).isEmpty();
    }

    @Test
    void anUnverifiableReviewOfABlockedReportAsksTheSameQuestion() {
        TicketId t = submitted(REPORT);

        f.record(f.company
                .tickets()
                .review(f.agent(DEHREN), t, ReviewVerdict.UNVERIFIABLE, "cannot render\nENVIRONMENT ONLY: yes"));

        Question q = f.company.desk().open().getFirst();
        assertThat(q.phrase().code()).isEqualTo("environmentBlocked");
        assertThat(q.phrase().params()).containsEntry("verdict", "UNVERIFIABLE");
    }

    @Test
    void eachAnswerCompletesOrReopensWithoutGrantingAnything() {
        assertThat(answered("I do the step myself")).isEqualTo(TicketStatus.DONE);
        assertThat(answered("Accept as is")).isEqualTo(TicketStatus.DONE);
        assertThat(answered("I allow an exception")).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(answered("I install what is missing")).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(answered("Send back to work")).isEqualTo(TicketStatus.IN_PROGRESS);
    }

    @Test
    void aReportWithoutTheMarkerIsStillSentBack() {
        TicketId t = submitted("PARTIAL: the PDFs are not rendered.");

        f.record(f.company.tickets().review(f.agent(DEHREN), t, ReviewVerdict.REJECTED, "PDFs missing"));

        assertThat(status(t)).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(f.company.desk().open()).isEmpty();
    }

    private TicketStatus answered(String answer) {
        Fixture g = new Fixture();
        MissionId m = g.runningMission();
        TicketId t = g.firstTicket(m);
        g.record(g.company.tickets().submitForReview(g.agent(CHRIS), t, REPORT));
        g.record(g.company.tickets().review(g.agent(DEHREN), t, ReviewVerdict.REJECTED, ONLY));
        Question q = g.company.desk().open().getFirst();
        var rules = java.util.List.copyOf(
                g.company.missions().find(m).orElseThrow().policy().rules());
        g.record(g.company.desk().answer(g.director(), q.id(), answer));
        assertThat(g.company.missions().find(m).orElseThrow().policy().rules()).isEqualTo(rules);
        return g.company.tickets().find(t).orElseThrow().status();
    }

    private TicketId submitted(String report) {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        f.record(f.company.tickets().submitForReview(f.agent(CHRIS), t, report));
        return t;
    }

    private TicketStatus status(TicketId t) {
        return f.company.tickets().find(t).orElseThrow().status();
    }
}
