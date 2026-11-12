package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The step-up proof: auth_time (whole seconds) against the redirect time (milliseconds). */
class StepUpFreshLoginTest {

    private static final Instant REDIRECT = Instant.parse("2026-11-04T10:00:05.900Z");

    private static boolean fresh(long authTime) {
        return StepUpApprovals.freshLogin(new ObjectMapper().createObjectNode().put("auth_time", authTime), REDIRECT);
    }

    @Test
    void aLoginInTheSecondOfTheRedirectCounts() {
        assertThat(fresh(REDIRECT.getEpochSecond())).isTrue();
        assertThat(fresh(REDIRECT.getEpochSecond() + 3)).isTrue();
    }

    @Test
    void aLoginBeforeThatSecondDoesNot() {
        assertThat(fresh(REDIRECT.getEpochSecond() - 1)).isFalse();
        assertThat(StepUpApprovals.freshLogin(new ObjectMapper().createObjectNode(), REDIRECT))
                .isFalse();
    }
}
