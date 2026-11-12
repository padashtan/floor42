package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.AgentSession;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.RiskLevel;
import dev.floor42.domain.event.MissionEvent.MissionSuspended;
import dev.floor42.domain.event.SessionEvent.AgentSessionStarted;
import dev.floor42.domain.event.SessionEvent.MissionQuarantined;
import dev.floor42.domain.event.SessionEvent.SessionRiskRaised;
import dev.floor42.server.journal.Journal;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A real fake-Claude session reads three files outside its workspace through the hook: the risk reaches ALERT then
 * QUARANTINE (the burst), the director's click suspends the mission and nothing starts for it afterwards (ADR-0066).
 * No model call: the fake Claude Code plays the script.
 */
@QuarkusTest
class SessionRiskLiveTest extends DispatchFixture {

    @Inject
    Journal journal;

    private <T> List<T> events(MissionId m, Class<T> type) {
        return journal.readAll().stream()
                .filter(e -> m.equals(e.mission()))
                .map(e -> e.event())
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private List<AgentSession> sessions(MissionId m) {
        return office.query(c -> List.copyOf(c.sessions().of(m)));
    }

    @Test
    void aProbingSessionIsProposedForQuarantineAndTheDirectorApplies() throws Exception {
        // The unverifiable review keeps the mission in progress once the session ends.
        MissionId m = mission("Write notes [fake:probe] [fake:unverifiable]");
        approve(m);
        await("the risk reaches QUARANTINE", Duration.ofSeconds(90), () -> {
            allowActions(m);
            return events(m, SessionRiskRaised.class).stream()
                    .anyMatch(e -> e.level().equals("QUARANTINE"));
        });
        List<SessionRiskRaised> raised = events(m, SessionRiskRaised.class);
        assertThat(raised).extracting(SessionRiskRaised::level).containsExactly("ALERT", "QUARANTINE");
        assertThat(raised.getLast().reasons()).last().isEqualTo("BURST");

        api().get("/api/progress")
                .then()
                .statusCode(200)
                .body(
                        "find { it.mission == '" + m.value()
                                + "' }.stuck.flatten().findAll { it.kind == 'SESSION_RISK' }.size()",
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1));

        AgentSession s = sessions(m).stream()
                .filter(x ->
                        x.risk().filter(r -> r.level() == RiskLevel.QUARANTINE).isPresent())
                .findFirst()
                .orElseThrow();
        long started = events(m, AgentSessionStarted.class).size();
        api().body("{\"agent\":\"" + s.agent().value() + "\"}")
                .post("/api/missions/" + m.value() + "/quarantine")
                .then()
                .statusCode(200)
                .body("suspended", org.hamcrest.Matchers.equalTo(true));

        MissionStatus status =
                office.query(c -> c.missions().find(m).orElseThrow().status());
        assertThat(status).isEqualTo(MissionStatus.SUSPENDED);
        assertThat(events(m, MissionQuarantined.class)).hasSize(1);
        assertThat(events(m, MissionSuspended.class)).hasSize(1);
        Thread.sleep(5_000);
        assertThat(events(m, AgentSessionStarted.class))
                .as("no session starts once the mission is suspended")
                .hasSize((int) started);
        cancel(m);
    }
}
