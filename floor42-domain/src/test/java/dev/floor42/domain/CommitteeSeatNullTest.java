package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** A null in the list of committees is a refusal the director can read, not a crash. */
class CommitteeSeatNullTest {

    @Test
    void aNullPanelIsRefusedNotAServerError() {
        var f = new Fixture();
        assertThatThrownBy(() -> f.company
                        .committees()
                        .defineSeat(
                                f.director(),
                                "rosa",
                                "Rosa",
                                "GDPR expert",
                                "claude-sonnet-5-5",
                                "medium",
                                "Personal data and consent.",
                                Arrays.asList((String) null)))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("INVALID_ARGUMENT");
    }
}
