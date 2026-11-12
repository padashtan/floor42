package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.ReviewCalibration;
import dev.floor42.domain.ReviewClaim;
import dev.floor42.domain.ReviewNote;
import dev.floor42.domain.ReviewVerdict;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The reviewer's RESERVATION and NOTE lines, read as written; the domain decides which stand (ADR-0053). */
class ReviewClaimsTest {

    @Test
    void readsReservationsWithTheirCriterionAndChangeAndNotes() {
        String text = """
                Checked the guide myself.
                - **RESERVATION C2**: the upgrade section skips the backup | CHANGE: add the backup step
                RESERVATION: C1: install misses Windows | CHANGE: add the winget line
                RESERVATION on style: headings vary | CHANGE: use sentence case
                reservation C3: no change said
                NOTE: could link the FAQ
                NOTES: not a marker line
                VERDICT: VALIDATED_WITH_RESERVATIONS
                """;
        assertThat(ReviewClaims.of(text))
                .containsExactly(
                        ReviewClaim.reservation("C2", "the upgrade section skips the backup", "add the backup step"),
                        ReviewClaim.reservation("C1", "install misses Windows", "add the winget line"),
                        ReviewClaim.reservation(null, "on style: headings vary", "use sentence case"),
                        ReviewClaim.reservation("C3", "no change said", null),
                        ReviewClaim.note("could link the FAQ"));
    }

    @Test
    void readsNumberedAndBulletedListsBareNumbersDashesAndAChangeOnTheNextLine() {
        String text = """
                1. RESERVATION C2: upgrade skips the backup | CHANGE: add the backup step
                10. RESERVATION C1: install misses Windows | CHANGE: add winget
                - RESERVATION 2: no rollback | CHANGE: add one
                * RESERVATION C1 - wrong path | CHANGE: fix it
                + RESERVATION C2 – typo | CHANGE: correct it
                > RESERVATION (C1) — stale link | CHANGE: update it
                RESERVATION criterion 2: vague step
                CHANGE: name the command
                RESERVATION C1 the screenshot is old
                RESERVATION: none
                """;
        assertThat(ReviewClaims.of(text))
                .containsExactly(
                        ReviewClaim.reservation("C2", "upgrade skips the backup", "add the backup step"),
                        ReviewClaim.reservation("C1", "install misses Windows", "add winget"),
                        ReviewClaim.reservation("C2", "no rollback", "add one"),
                        ReviewClaim.reservation("C1", "wrong path", "fix it"),
                        ReviewClaim.reservation("C2", "typo", "correct it"),
                        ReviewClaim.reservation("C1", "stale link", "update it"),
                        ReviewClaim.reservation("C2", "vague step", "name the command"),
                        ReviewClaim.reservation("C1", "the screenshot is old", null));
    }

    @Test
    void aChangeLineAttachesOnlyToTheReservationRightAboveIt() {
        String text = """
                RESERVATION C1: the install step is missing

                Some prose about the docs.
                CHANGE: rewrite the intro
                RESERVATION C2: no rollback

                CHANGE: add a rollback section
                RESERVATION C1: stale screenshot
                Change: none
                """;
        assertThat(ReviewClaims.of(text))
                .containsExactly(
                        ReviewClaim.reservation("C1", "the install step is missing", null),
                        ReviewClaim.reservation("C2", "no rollback", "add a rollback section"),
                        ReviewClaim.reservation("C1", "stale screenshot", null));
    }

    @Test
    void ignoresLinesInsideFencedCodeBlocks() {
        String text = """
                The brief's format, quoted:
                ```
                RESERVATION C<n>: <what falls short> | CHANGE: <what to change>
                NOTE: <remark>
                ```
                ~~~text
                RESERVATION C1: inside a tilde fence | CHANGE: x
                ~~~
                NOTE: outside
                """;
        assertThat(ReviewClaims.of(text)).containsExactly(ReviewClaim.note("outside"));
    }

    @Test
    void freeFormReservationsKeepTheDeclaredVerdictAndAreFlaggedUnparsed() {
        String text = """
                Reservations:
                - the docs miss the upgrade path
                - tests are thin
                VERDICT: VALIDATED_WITH_RESERVATIONS
                """;
        ReviewVerdict declared = ReviewRunner.verdictOf(text);
        ReviewCalibration.Calibrated sorted =
                ReviewCalibration.sort(List.of("tests pass"), ReviewClaims.of(text), declared);
        assertThat(sorted.notes()).extracting(ReviewNote::why).containsExactly(ReviewNote.Why.UNPARSED);
    }

    @Test
    void reservationsThatCiteNoCriterionAreNotes() {
        String text = """
                RESERVATION: the code could be shorter | CHANGE: inline the helper
                NOTE: nice tests
                VERDICT: VALIDATED_WITH_RESERVATIONS
                """;
        ReviewCalibration.Calibrated sorted = ReviewCalibration.classify(List.of("tests pass"), ReviewClaims.of(text));
        assertThat(sorted.reservations()).isEmpty();
        assertThat(sorted.notes())
                .extracting(ReviewNote::why)
                .containsExactly(ReviewNote.Why.NO_CRITERION, ReviewNote.Why.NOTE);
    }

    @Test
    void theBriefNumbersTheCriteriaTheReservationsCite() {
        assertThat(ReviewerBrief.numbered(List.of("covers install", "covers upgrade")))
                .isEqualTo("- C1: covers install\n- C2: covers upgrade");
        assertThat(ReviewerBrief.numbered(List.of())).contains("recorded as a note");
    }

    @Test
    void aDashSeparatorIsStrippedWhenTheValueHoldsAColon() {
        // Found by fuzzing: the colon later in the line was read as the separator and the dash stayed in the text.
        assertThat(ReviewClaims.of("RESERVATION - the install skips the backup: add it"))
                .containsExactly(ReviewClaim.reservation(null, "the install skips the backup: add it", null));
    }
}
