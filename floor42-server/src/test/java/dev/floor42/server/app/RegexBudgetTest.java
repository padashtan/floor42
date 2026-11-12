package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.floor42.domain.ReviewClaim;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Text an agent wrote (a review, a remote URL) is read in linear time: no pattern backtracks on a hostile line. */
class RegexBudgetTest {

    private static final int BIG = 50_000;
    // The bugs this guards took tens of seconds; the gate runs under load.
    private static final Duration BUDGET = Duration.ofSeconds(10);

    private static <T> T within(Supplier<T> call) {
        return assertTimeoutPreemptively(BUDGET, call::get);
    }

    @Test
    void aReservationLineWithAHugeBlankRunIsReadFast() {
        assertThat(within(() -> ReviewClaims.of("RESERVATION" + " ".repeat(BIG) + "x")))
                .hasSize(1);
        within(() -> ReviewClaims.of("RESERVATION" + "\t".repeat(BIG)));
        assertThat(within(() -> ReviewClaims.of("RESERVATION" + " ".repeat(BIG) + "\t-x")))
                .hasSize(1);
        assertThat(within(() -> ReviewClaims.of("NOTE: a" + " ".repeat(BIG) + "b")))
                .containsExactly(ReviewClaim.note("a" + " ".repeat(BIG) + "b"));
    }

    @Test
    void theStoredTextKeepsItsTabsAndBlankRuns() {
        assertThat(ReviewClaims.of("NOTE: a\tb")).containsExactly(ReviewClaim.note("a\tb"));
        assertThat(ReviewClaims.of("RESERVATION: gap\twith tab | CHANGE: fix\tit"))
                .containsExactly(ReviewClaim.reservation(null, "gap\twith tab", "fix\tit"));
        String wide = "x" + " ".repeat(40) + "y";
        assertThat(ReviewClaims.of("RESERVATION C2: " + wide + " | CHANGE: " + wide))
                .containsExactly(ReviewClaim.reservation("C2", wide, wide));
    }

    @Test
    void aChangeKeyAfterAHugeRunOfStarsOrBarsIsReadFast() {
        assertThat(within(() -> ReviewClaims.of("RESERVATION: gap " + "*".repeat(BIG) + "x")))
                .hasSize(1);
        assertThat(within(() -> ReviewClaims.of("RESERVATION: gap " + "|".repeat(BIG) + " **CHANGE**: fix")))
                .hasSize(1);
        assertThat(ReviewClaims.of("RESERVATION: C2: the gap | CHANGE: add the step"))
                .containsExactly(ReviewClaim.reservation("C2", "the gap", "add the step"));
        assertThat(ReviewClaims.of("RESERVATION: the gap  ** CHANGE** : add"))
                .containsExactly(ReviewClaim.reservation(null, "the gap", "add"));
    }

    @Test
    void aRemoteUrlOfAnyShapeIsParsedFast() {
        List<String> urls = List.of(
                "https://" + "a".repeat(BIG) + "/b/c/",
                "https://a/b/" + ".git".repeat(BIG / 4) + "x",
                "https://" + "a@".repeat(BIG / 2),
                "a".repeat(BIG) + ":b/c",
                "a:b/" + ".git".repeat(BIG / 4) + "x",
                "a:".repeat(BIG / 2),
                "a:b/" + "c".repeat(BIG) + "/x");
        for (String url : urls) within(() -> PullRequestGit.repositoryOf(url));
        assertThat(PullRequestGit.repositoryOf("git@github.com:o/r.git")).contains("github.com/o/r");
        assertThat(PullRequestGit.repositoryOf("https://github.com/o/r/")).contains("github.com/o/r");
    }
}
