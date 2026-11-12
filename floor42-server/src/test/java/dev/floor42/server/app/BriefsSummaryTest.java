package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Floor42 reads a committee contribution's closing lines back; what is not stated stays null, never guessed. */
class BriefsSummaryTest {

    @Test
    void closingLinesAreReadEvenWithMarkdown() {
        Briefs.Summary s = Briefs.summary("""
                Long analysis...
                **PROPOSAL:** Keep the monolith, split the reporting module
                - POSITION: ADOPTED geoffrey
                CRITICAL OBJECTION: none.
                """);

        assertThat(s.proposal()).isEqualTo("Keep the monolith, split the reporting module");
        assertThat(s.position()).isEqualTo("ADOPTED geoffrey");
        assertThat(s.criticalObjection()).isNull();
    }

    @Test
    void theLastOccurrenceWinsAndAnObjectionIsKept() {
        Briefs.Summary s = Briefs.summary("""
                PROPOSAL: first idea
                ... after the debate ...
                PROPOSAL: revised idea
                CRITICAL OBJECTION: secrets would be logged
                RECOMMENDATION: revised idea
                """);

        assertThat(s.proposal()).isEqualTo("revised idea");
        assertThat(s.criticalObjection()).isEqualTo("secrets would be logged");
        assertThat(s.recommendation()).isEqualTo("revised idea");
    }

    @Test
    void nothingStatedMeansNothingRecorded() {
        Briefs.Summary s = Briefs.summary("A contribution without closing lines.");

        assertThat(s.proposal()).isNull();
        assertThat(s.position()).isNull();
        assertThat(s.criticalObjection()).isNull();
    }
}
