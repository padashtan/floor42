package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Path text from a director or a branch is read in linear time. */
class LinearTimeSitesTest {

    // The bugs this guards took tens of seconds; the gate runs under load.
    private static final Duration BUDGET = Duration.ofSeconds(10);
    private static final int BIG = 20_000;

    @Test
    void trimSlashesCutsBothEndsOnlyAndIsFastOnAHostileRun() {
        assertThat(DirectorFiles.trimSlashes("//a/b//")).isEqualTo("a/b");
        assertThat(DirectorFiles.trimSlashes("///")).isEmpty();
        assertThat(DirectorFiles.trimSlashes("a")).isEqualTo("a");
        String hostile = "/".repeat(BIG) + "a" + "/".repeat(BIG) + "b";
        assertThat(assertTimeoutPreemptively(BUDGET, () -> DirectorFiles.trimSlashes(hostile)))
                .isEqualTo("a" + "/".repeat(BIG) + "b");
    }

    @Test
    void aDecisionRecordIsRecognisedAsBefore() {
        assertThat(PullRequestText.isDecisionRecord("docs/adr/0012-x.md")).isTrue();
        assertThat(PullRequestText.isDecisionRecord("ADRS/x.md")).isTrue();
        assertThat(PullRequestText.isDecisionRecord("decisions/x.md")).isTrue();
        assertThat(PullRequestText.isDecisionRecord("adr-0012-x.md")).isTrue();
        assertThat(PullRequestText.isDecisionRecord("docs/ADR_7.md")).isTrue();
        assertThat(PullRequestText.isDecisionRecord("docs/adr 7.md")).isTrue();
        assertThat(PullRequestText.isDecisionRecord("docs/adr7")).isTrue();
        assertThat(PullRequestText.isDecisionRecord("docs/adr.md")).isFalse();
        assertThat(PullRequestText.isDecisionRecord("docs/adr/")).isTrue();
        assertThat(PullRequestText.isDecisionRecord("docs/madr/x.md")).isFalse();
        assertThat(PullRequestText.isDecisionRecord("src/Main.java")).isFalse();
    }

    @Test
    void aHostilePathIsClassifiedFast() {
        String hostile = "adr".repeat(BIG / 3) + "/".repeat(BIG) + "adr-" + "x".repeat(BIG);
        assertThat(assertTimeoutPreemptively(BUDGET, () -> PullRequestText.isDecisionRecord(hostile)))
                .isFalse();
    }
}
