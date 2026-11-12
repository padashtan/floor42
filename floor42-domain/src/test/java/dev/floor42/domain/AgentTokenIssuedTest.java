package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.SessionEvent.AgentTokenIssued;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentTokenIssuedTest {

    final Fixture f = new Fixture();
    final Instant expires = Instant.parse("2026-10-20T16:00:00Z");

    @Test
    void tokenIssuedNeedsAnOpenSessionAndKeepsTheJti() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        assertThatThrownBy(() -> f.company.sessions().tokenIssued(f.system(), "s1", "urn:x", expires, "jti-1"))
                .extracting("code")
                .isEqualTo("UNKNOWN_SESSION");

        f.record(f.company.sessions().start(f.system(), "s1", t, CHRIS, "claude-sonnet-5-5", null));
        assertThatThrownBy(() -> f.company.sessions().tokenIssued(f.agent(CHRIS), "s1", "urn:x", expires, "jti-1"))
                .extracting("code")
                .isEqualTo("FORBIDDEN");
        f.record(f.company.sessions().tokenIssued(f.system(), "s1", "urn:x", expires, "jti-1"));

        AgentTokenIssued e = (AgentTokenIssued) f.journal.getLast().event();
        assertThat(e).isEqualTo(new AgentTokenIssued("s1", CHRIS, m, t, "urn:x", "2026-10-20T16:00:00Z", "jti-1"));
        AgentSession s = f.company.sessions().find("s1").orElseThrow();
        assertThat(s.lastTokenId()).isEqualTo("jti-1");
        assertThat(s.tokenIssuer()).isEqualTo("urn:x");
        assertThat(s.tokenExpiresAt()).isEqualTo(expires);

        f.record(f.company.sessions().end(f.system(), "s1", false, "report", 1, 1L, 1L, 0.01));
        assertThatThrownBy(() -> f.company.sessions().tokenIssued(f.system(), "s1", "urn:x", expires, "jti-2"))
                .extracting("code")
                .isEqualTo("SESSION_CLOSED");
    }
}
