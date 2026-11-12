package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.RiskLevel.ALERT;
import static dev.floor42.domain.RiskLevel.NOTICE;
import static dev.floor42.domain.RiskLevel.QUARANTINE;
import static dev.floor42.domain.RiskReason.BURST;
import static dev.floor42.domain.RiskReason.OUTSIDE_WORKSPACE;
import static dev.floor42.domain.RiskReason.POLICY_DENIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.SessionEvent.SessionRiskRaised;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(GoldenFixtureExtension.class)
class SessionRiskRaisedTest {

    static final Instant T0 = Instant.parse("2026-10-20T09:30:00Z");
    final Fixture f = new Fixture();

    @Test
    void aRaiseNeedsTheSystemAnOpenSessionAndAHigherLevel() {
        MissionId m = f.runningMission();
        TicketId t = f.firstTicket(m);
        f.record(f.company.sessions().start(f.system(), "s1", t, CHRIS, "claude-sonnet-5-5", null));
        var actions = List.of(new ActionId("M-1:t1"));

        assertThatThrownBy(() -> f.company
                        .sessions()
                        .riskRaised(f.director(), "s1", ALERT, List.of(OUTSIDE_WORKSPACE), T0, actions))
                .extracting("code")
                .isEqualTo("FORBIDDEN");
        assertThatThrownBy(() -> f.company.sessions().riskRaised(f.system(), "s1", ALERT, List.of(), T0, actions))
                .isInstanceOf(DomainException.class);
        f.record(f.company.sessions().riskRaised(f.system(), "s1", ALERT, List.of(OUTSIDE_WORKSPACE), T0, actions));

        assertThat(f.journal.getLast().event())
                .isEqualTo(new SessionRiskRaised(
                        "s1", CHRIS, m, t, "ALERT", List.of("OUTSIDE_WORKSPACE"), T0.toString(), List.of("M-1:t1")));
        var risk = f.company.sessions().find("s1").orElseThrow().risk().orElseThrow();
        assertThat(risk.level()).isEqualTo(ALERT);
        assertThat(risk.reasons()).containsExactly(OUTSIDE_WORKSPACE);
        assertThat(risk.at()).isEqualTo(f.journal.getLast().occurredAt());

        assertThatThrownBy(() -> f.company
                        .sessions()
                        .riskRaised(f.system(), "s1", NOTICE, List.of(POLICY_DENIED), T0, List.of()))
                .extracting("code")
                .isEqualTo("INVALID_TRANSITION");
        f.record(f.company
                .sessions()
                .riskRaised(f.system(), "s1", QUARANTINE, List.of(POLICY_DENIED, BURST), T0, List.of()));
        assertThat(f.company
                        .sessions()
                        .find("s1")
                        .orElseThrow()
                        .risk()
                        .orElseThrow()
                        .level())
                .isEqualTo(QUARANTINE);

        f.record(f.company.sessions().end(f.system(), "s1", false, "report", 1, 1L, 1L, 0.01));
        assertThatThrownBy(() ->
                        f.company.sessions().riskRaised(f.system(), "s1", QUARANTINE, List.of(BURST), T0, List.of()))
                .extracting("code")
                .isEqualTo("SESSION_CLOSED");
    }

    @Test
    void aSuspensionIsDated() {
        MissionId m = f.runningMission();
        assertThat(f.company.missions().find(m).orElseThrow().suspendedAt()).isNull();
        f.record(f.company.missions().suspend(f.director(), m, "x"));
        assertThat(f.company.missions().find(m).orElseThrow().suspendedAt())
                .isEqualTo(f.journal.getLast().occurredAt());
    }
}
