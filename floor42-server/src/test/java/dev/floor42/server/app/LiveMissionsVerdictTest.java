package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.ReviewVerdict;
import org.junit.jupiter.api.Test;

class LiveMissionsVerdictTest {

    @Test
    void aReviewWithoutAVerdictLineCannotBeTrusted() {
        assertThat(ReviewRunner.verdictOf("Looks fine to me.")).isEqualTo(ReviewVerdict.UNVERIFIABLE);
    }

    @Test
    void theLastVerdictLineWins() {
        assertThat(ReviewRunner.verdictOf("first\nVERDICT: REJECTED\nafter a second look\nVERDICT: VALIDATED"))
                .isEqualTo(ReviewVerdict.VALIDATED);
        assertThat(ReviewRunner.verdictOf("VERDICT: VALIDATED_WITH_RESERVATIONS"))
                .isEqualTo(ReviewVerdict.VALIDATED_WITH_RESERVATIONS);
    }
}
