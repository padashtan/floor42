package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.iam.fga.MockFga;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Countersigners as the daemon builds it (no hand wiring): its decision point is the OpenFGA client. */
@QuarkusTest
class CountersignersWiringTest {

    private static final Optional<Set<String>> REVIEWER = Optional.of(Set.of(Countersigners.REVIEWER_ROLE));

    @Inject
    Countersigners countersigners;

    @Inject
    MockFga fga;

    @AfterEach
    void reset() {
        fga.reset();
    }

    @Test
    void aReviewerWhoIsNotTheDirectorMayCountersign() {
        assertThat(countersigners.refusal(REVIEWER, "sub2", "sub1")).isNull();
    }

    @Test
    void aSignerWithoutTheReviewerRoleIsRefused() {
        assertThat(countersigners
                        .refusal(Optional.of(Set.of("other")), "sub2", "sub1")
                        .code())
                .isEqualTo("NOT_A_REVIEWER");
    }

    @Test
    void theDirectorWithTheReviewerRoleIsRefusedByTheModel() {
        assertThat(countersigners.refusal(REVIEWER, "sub1", "sub1").code()).isEqualTo("NOT_A_REVIEWER");
    }

    @Test
    void aDecisionPointThatCannotAnswerIs503NotAnAllow() {
        fga.unavailable(true);
        Countersigners.Refusal no = countersigners.refusal(REVIEWER, "sub2", "sub1");
        assertThat(no.status()).isEqualTo(503);
        assertThat(no.code()).isEqualTo("FGA_UNAVAILABLE");
    }
}
