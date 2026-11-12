package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.Actor;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.RiskLevel;
import dev.floor42.domain.RiskReason;
import dev.floor42.domain.event.MissionEvent.MissionSuspended;
import dev.floor42.domain.event.SessionEvent.MissionQuarantined;
import dev.floor42.server.iam.Delegations;
import dev.floor42.server.iam.IamOnProfile;
import dev.floor42.server.iam.IamSessionProfile;
import dev.floor42.server.iam.IamSessionTestBase;
import dev.floor42.server.iam.KeycloakClient;
import dev.floor42.server.runtime.RunningSessions;
import dev.floor42.server.runtime.RunningSessionsProbe;
import dev.floor42.server.security.LocalAccessGuard;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The director's quarantine: revoke, suspend, stop, once (ADR-0066). No model call: the process is a sleeper. */
@QuarkusTest
@TestProfile(IamSessionProfile.class)
class QuarantineApiTest extends IamSessionTestBase {

    private static final long ME = ProcessHandle.current().pid();

    @Inject
    SessionToken token;

    @Inject
    dev.floor42.server.security.SessionRegistry browsers;

    @io.quarkus.test.common.http.TestHTTPResource("/")
    java.net.URI base;

    @Inject
    Delegations delegations;

    @Inject
    KeycloakClient keycloak;

    @Inject
    RunningSessions running;

    private RequestSpecification api() {
        return given().header("Authorization", "Bearer " + token.value()).contentType("application/json");
    }

    private String risk(Setup s, RiskLevel level) {
        var ctx = office.context(Actor.system(), Origin.REAL);
        String id = "risk-" + UUID.randomUUID();
        office.execute(c -> c.sessions().start(ctx, id, s.ticket(), CHRIS, "claude-sonnet-5-5", "2.1.304"));
        office.execute(c -> c.sessions()
                .riskRaised(ctx, id, level, List.of(RiskReason.OUTSIDE_WORKSPACE), Instant.now(), List.of()));
        return id;
    }

    private io.restassured.response.ValidatableResponse quarantine(Setup s) {
        return api().body("{\"agent\":\"chris\"}")
                .post("/api/missions/" + s.mission().value() + "/quarantine")
                .then();
    }

    /** A step-up sign-in as the director's browser would do it: the offline tokens a delegation is made of. */
    private KeycloakClient.Tokens offlineTokens() throws Exception {
        String verifier = "v".repeat(43);
        String challenge = dev.floor42.server.iam.Jwts.base64url(java.security.MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        String redirect = "http://127.0.0.1:7420/auth/callback";
        String query = "response_type=code&client_id=floor42-desk&redirect_uri="
                + java.net.URLEncoder.encode(redirect, java.nio.charset.StandardCharsets.UTF_8)
                + "&scope=openid+offline_access&state=s&nonce=n&code_challenge=" + challenge
                + "&code_challenge_method=S256&prompt=login&max_age=0";
        var r = java.net.http.HttpClient.newHttpClient()
                .send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                        IamOnProfile.fake.issuer() + "/protocol/openid-connect/auth?" + query))
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
        String location = r.headers().firstValue("Location").orElseThrow();
        String code = location.replaceAll(".*[?&]code=([^&]+).*", "$1");
        return keycloak.redeemCode(code, verifier, java.net.URI.create(redirect));
    }

    private List<MissionQuarantined> quarantined(Setup s) {
        return journal.readAll().stream()
                .map(e -> e.event())
                .filter(MissionQuarantined.class::isInstance)
                .map(MissionQuarantined.class::cast)
                .filter(e -> e.mission().equals(s.mission()))
                .toList();
    }

    private void assertQuarantined(Setup s, String session, boolean revoked, boolean suspended, boolean stopped) {
        assertThat(quarantined(s)).singleElement().satisfies(e -> {
            assertThat(e.session()).isEqualTo(session);
            assertThat(e.agent()).isEqualTo(CHRIS);
            assertThat(e.reasons()).containsExactly("OUTSIDE_WORKSPACE");
            assertThat(e.revoked()).isEqualTo(revoked);
            assertThat(e.suspended()).isEqualTo(suspended);
            assertThat(e.stopped()).isEqualTo(stopped);
        });
    }

    private MissionStatus statusOf(Setup s) {
        return office.query(c -> c.missions().find(s.mission()).orElseThrow().status());
    }

    @Test
    void theDirectorsClickRevokesSuspendsAndStops() throws Exception {
        Setup s = realMissionInProgress();
        keycloak.ensureAgentClient(CHRIS, 28800);
        delegations.grant(s.mission(), offlineTokens(), List.of());
        String session = risk(s, RiskLevel.QUARANTINE);
        String hookToken = liveSession(s, ME);
        Process p = RunningSessionsProbe.sleeper(running, CHRIS, s.mission());
        try {
            quarantine(s)
                    .statusCode(200)
                    .body("revoked", equalTo(true))
                    .body("suspended", equalTo(true))
                    .body("stopped", equalTo(true))
                    .body("session", equalTo(session));
            assertThat(delegations.find(s.mission()).orElseThrow().revoked()).isTrue();
            assertThat(statusOf(s)).isEqualTo(MissionStatus.SUSPENDED);
            String reason = journal.readAll().stream()
                    .map(e -> e.event())
                    .filter(MissionSuspended.class::isInstance)
                    .map(MissionSuspended.class::cast)
                    .filter(e -> e.mission().equals(s.mission()))
                    .findFirst()
                    .orElseThrow()
                    .reason();
            assertThat(reason).isEqualTo("quarantine of session " + session + ": OUTSIDE_WORKSPACE");
            assertThat(journalText()).doesNotContain("eyJ");
            // The next hook call of that session is refused at once.
            given().contentType("application/json")
                    .header("Authorization", "Bearer " + hookToken)
                    .body(preToolUse("Read", "after-1"))
                    .post("/hooks/pre-tool-use")
                    .then()
                    .statusCode(401)
                    .body("reason", equalTo("REVOKED"));
            // A second click changes nothing: the risk is not newer than the suspension.
            quarantine(s).statusCode(409).body("code", equalTo("NO_RISK"));
            assertQuarantined(s, session, true, true, true);
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    void aMissionNotInProgressIsStillJournaledAsQuarantined() throws Exception {
        Setup s = realMissionInProgress();
        String id = risk(s, RiskLevel.ALERT);
        office.execute(c -> c.missions().cancel(director(), s.mission(), "the director gave up"));
        quarantine(s).statusCode(200).body("suspended", equalTo(false));
        assertQuarantined(s, id, false, false, false);
        assertThat(journal.readAll().stream()
                        .map(e -> e.event())
                        .filter(MissionSuspended.class::isInstance)
                        .map(MissionSuspended.class::cast)
                        .filter(e -> e.mission().equals(s.mission()))
                        .count())
                .isZero();
    }

    @Test
    void aProcessOfAnotherMissionIsNeverStopped() throws Exception {
        Setup quarantined = realMissionInProgress();
        Setup other = realMissionInProgress();
        risk(quarantined, RiskLevel.QUARANTINE);
        Process p = RunningSessionsProbe.sleeper(running, CHRIS, other.mission());
        try {
            quarantine(quarantined).statusCode(200).body("stopped", equalTo(false));
            assertThat(p.isAlive()).isTrue();
            assertThat(statusOf(other)).isEqualTo(MissionStatus.IN_PROGRESS);
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    void anOldRiskCannotSuspendAResumedMissionAgain() throws Exception {
        Setup s = realMissionInProgress();
        String first = risk(s, RiskLevel.QUARANTINE);
        quarantine(s).statusCode(200).body("suspended", equalTo(true));
        office.execute(c -> c.missions().resume(director(), s.mission()));
        quarantine(s).statusCode(409).body("code", equalTo("NO_RISK"));
        assertThat(statusOf(s)).isEqualTo(MissionStatus.IN_PROGRESS);
        var ctx = office.context(Actor.system(), Origin.REAL);
        office.execute(c -> c.sessions().end(ctx, first, false, "completed", 1, 1L, 1L, 0.0));
        Thread.sleep(5);
        risk(s, RiskLevel.ALERT);
        quarantine(s).statusCode(200).body("suspended", equalTo(true));
    }

    @Test
    void anOutageAtKeycloakStillSuspendsAndStops() throws Exception {
        Setup s = realMissionInProgress();
        keycloak.ensureAgentClient(CHRIS, 28800);
        delegations.grant(s.mission(), offlineTokens(), List.of());
        risk(s, RiskLevel.QUARANTINE);
        Process p = RunningSessionsProbe.sleeper(running, CHRIS, s.mission());
        IamOnProfile.fake.down(true);
        try {
            quarantine(s)
                    .statusCode(200)
                    .body("revoked", equalTo(true))
                    .body("suspended", equalTo(true))
                    .body("stopped", equalTo(true));
            assertThat(delegations.find(s.mission()).orElseThrow().revoked()).isTrue();
            assertThat(quarantined(s)).singleElement().satisfies(e -> {
                assertThat(e.revoked()).isTrue();
                assertThat(e.suspended()).isTrue();
                assertThat(e.stopped()).isTrue();
            });
        } finally {
            IamOnProfile.fake.down(false);
            p.destroyForcibly();
        }
    }

    @Test
    void aScriptTokenOfAnyNarrowScopeGets403() throws Exception {
        Setup s = realMissionInProgress();
        risk(s, RiskLevel.QUARANTINE);
        for (String scope : List.of("read", "missions:write")) {
            String narrow = api().body("{\"scope\":\"" + scope + "\",\"days\":1,\"label\":\"script\"}")
                    .post("/api/auth/tokens")
                    .then()
                    .statusCode(201)
                    .extract()
                    .path("token");
            given().header("Authorization", "Bearer " + narrow)
                    .contentType("application/json")
                    .body("{\"agent\":\"chris\"}")
                    .post("/api/missions/" + s.mission().value() + "/quarantine")
                    .then()
                    .statusCode(403);
        }
        assertThat(statusOf(s)).isEqualTo(MissionStatus.IN_PROGRESS);
    }

    @Test
    void aBrowserCookieNeedsItsHeaderAndItsOrigin() throws Exception {
        Setup s = realMissionInProgress();
        risk(s, RiskLevel.QUARANTINE);
        String cookie = browsers.openBrowser("quarantine test").secret();
        String own = "http://127.0.0.1:" + base.getPort();
        String url = "/api/missions/" + s.mission().value() + "/quarantine";
        given().cookie(LocalAccessGuard.COOKIE, cookie)
                .header("Origin", own)
                .contentType("application/json")
                .body("{\"agent\":\"chris\"}")
                .post(url)
                .then()
                .statusCode(403);
        given().cookie(LocalAccessGuard.COOKIE, cookie)
                .header("Origin", "http://127.0.0.1:" + (base.getPort() + 1))
                .header(LocalAccessGuard.REQUEST_HEADER, "1")
                .contentType("application/json")
                .body("{\"agent\":\"chris\"}")
                .post(url)
                .then()
                .statusCode(403);
        assertThat(statusOf(s)).isEqualTo(MissionStatus.IN_PROGRESS);
        given().cookie(LocalAccessGuard.COOKIE, cookie)
                .header("Origin", own)
                .header(LocalAccessGuard.REQUEST_HEADER, "1")
                .contentType("application/json")
                .body("{\"agent\":\"chris\"}")
                .post(url)
                .then()
                .statusCode(200);
    }

    @Test
    void noRiskNoQuarantine() throws Exception {
        Setup s = realMissionInProgress();
        quarantine(s).statusCode(409).body("code", equalTo("NO_RISK"));
        risk(s, RiskLevel.NOTICE);
        quarantine(s).statusCode(409).body("code", equalTo("NO_RISK"));
        assertThat(quarantined(s)).isEmpty();
        assertThat(statusOf(s)).isEqualTo(MissionStatus.IN_PROGRESS);
    }

    @Test
    void withoutADelegationItSuspendsAndStops() throws Exception {
        Setup s = realMissionInProgress();
        String session = risk(s, RiskLevel.ALERT);
        Process p = RunningSessionsProbe.sleeper(running, CHRIS, s.mission());
        try {
            quarantine(s)
                    .statusCode(200)
                    .body("revoked", equalTo(false))
                    .body("suspended", equalTo(true))
                    .body("stopped", equalTo(true));
            assertQuarantined(s, session, false, true, true);
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    void anUnknownMissionIsA404() {
        api().body("{\"agent\":\"chris\"}")
                .post("/api/missions/M-9999/quarantine")
                .then()
                .statusCode(404)
                .body("code", equalTo("NO_MISSION"));
    }

    @Test
    void anAgentTokenCannotQuarantine() throws Exception {
        Setup s = realMissionInProgress();
        risk(s, RiskLevel.QUARANTINE);
        String agentToken = liveSession(s, ME);
        given().header("Authorization", "Bearer " + agentToken)
                .contentType("application/json")
                .body("{\"agent\":\"chris\"}")
                .post("/api/missions/" + s.mission().value() + "/quarantine")
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(equalTo(401), equalTo(403)));
        assertThat(statusOf(s)).isEqualTo(MissionStatus.IN_PROGRESS);
    }
}
