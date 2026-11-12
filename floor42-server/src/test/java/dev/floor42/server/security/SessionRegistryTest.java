package dev.floor42.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SessionRegistryTest {

    Instant now = Instant.parse("2026-10-31T08:00:00Z");

    SessionRegistry registry() {
        SessionRegistry r = new SessionRegistry();
        r.idleHours = 8;
        r.absoluteHours = 24;
        r.clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        return r;
    }

    @Test
    void aBrowserSessionEndsAfterEightIdleHours() {
        SessionRegistry r = registry();
        String secret = r.openBrowser("ua").secret();
        now = now.plus(Duration.ofHours(7));
        assertThat(r.authenticate(secret)).isPresent();
        now = now.plus(Duration.ofHours(8));
        assertThat(r.authenticate(secret)).as("idle 8 h").isEmpty();
    }

    @Test
    void aBrowserSessionEndsTwentyFourHoursAfterSignInEvenWhenUsed() {
        SessionRegistry r = registry();
        String secret = r.openBrowser("ua").secret();
        for (int h = 0; h < 20; h += 4) {
            now = now.plus(Duration.ofHours(4));
            assertThat(r.authenticate(secret)).isPresent();
        }
        now = now.plus(Duration.ofHours(4));
        assertThat(r.authenticate(secret)).as("24 h absolute").isEmpty();
    }

    @Test
    void aScriptTokenLastsItsDaysAtMostThirty() {
        SessionRegistry r = registry();
        String secret =
                r.issueScript(SessionRegistry.Scope.READ, 2, "bench", null).secret();
        now = now.plus(Duration.ofHours(40));
        assertThat(r.authenticate(secret)).as("not subject to the idle limit").isPresent();
        now = now.plus(Duration.ofHours(9));
        assertThat(r.authenticate(secret)).isEmpty();
        assertThatThrownBy(() -> r.issueScript(SessionRegistry.Scope.ALL, 31, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyTheHashIsKept() {
        SessionRegistry r = registry();
        var issued = r.issueScript(SessionRegistry.Scope.ALL, 1, null, null);
        assertThat(issued.session().hash())
                .hasSize(64)
                .isEqualTo(SessionRegistry.sha256(issued.secret()))
                .isNotEqualTo(issued.secret());
        assertThat(issued.session().toString()).doesNotContain(issued.secret());
        assertThat(r.authenticate(issued.session().hash())).isEmpty();
    }
}
