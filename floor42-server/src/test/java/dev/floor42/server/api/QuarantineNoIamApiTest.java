package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.Actor;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.RiskLevel;
import dev.floor42.domain.RiskReason;
import dev.floor42.server.iam.IamSessionTestBase;
import dev.floor42.server.runtime.RunningSessions;
import dev.floor42.server.runtime.RunningSessionsProbe;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Quarantine with IAM mode off: nothing to revoke, the mission is suspended and the session stopped. */
@QuarkusTest
@TestProfile(QuarantineNoIamApiTest.IamOff.class)
class QuarantineNoIamApiTest extends IamSessionTestBase {

    /** The profile of the IAM tests (fake issuer included), except for the setting. */
    public static class IamOff extends dev.floor42.server.iam.IamSessionProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> m = new HashMap<>(super.getConfigOverrides());
            m.put("floor42.iam.enabled", "false");
            return m;
        }
    }

    @Inject
    SessionToken token;

    @Inject
    RunningSessions running;

    @Test
    void withoutIamItSuspendsAndStops() throws Exception {
        Setup s = realMissionInProgress();
        var ctx = office.context(Actor.system(), Origin.REAL);
        office.execute(c -> c.sessions().start(ctx, "noiam-1", s.ticket(), CHRIS, "claude-sonnet-5-5", "2.1.304"));
        office.execute(c -> c.sessions()
                .riskRaised(
                        ctx,
                        "noiam-1",
                        RiskLevel.ALERT,
                        List.of(RiskReason.OUTSIDE_WORKSPACE),
                        Instant.now(),
                        List.of()));
        Process p = RunningSessionsProbe.sleeper(running, CHRIS, s.mission());
        try {
            given().header("Authorization", "Bearer " + token.value())
                    .contentType("application/json")
                    .body("{\"agent\":\"chris\"}")
                    .post("/api/missions/" + s.mission().value() + "/quarantine")
                    .then()
                    .statusCode(200)
                    .body("revoked", equalTo(false))
                    .body("suspended", equalTo(true))
                    .body("stopped", equalTo(true));
            MissionStatus status = office.query(
                    c -> c.missions().find(s.mission()).orElseThrow().status());
            assertThat(status).isEqualTo(MissionStatus.SUSPENDED);
        } finally {
            p.destroyForcibly();
        }
    }
}
