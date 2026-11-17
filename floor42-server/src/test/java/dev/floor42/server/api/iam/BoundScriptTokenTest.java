package dev.floor42.server.api.iam;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import dev.floor42.domain.Actor;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.server.api.ApiTestBase;
import dev.floor42.server.api.mission.LiveMissionTest;
import dev.floor42.server.app.core.OfficeService;
import dev.floor42.server.iam.IamOnProfile;
import dev.floor42.server.iam.KeycloakClient;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.iam.ScriptTokenBindings;
import dev.floor42.server.iam.SecretVault;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A script token bound to a Keycloak session may steer the routes an unbound one is refused on in IAM mode (ADR-0083),
 * except approving a plan. The binding is made in Java here (the step-up itself is {@code BoundTokenIssuanceTest});
 * Keycloak is the fake. Past the guard an unknown id answers 404 or 400: only the refusal codes are under test.
 */
@QuarkusTest
@TestProfile(IamOnProfile.class)
class BoundScriptTokenTest extends ApiTestBase {

    private static final List<String[]> STEERED = List.of(
            new String[] {"POST", "/api/missions/M-999/tickets/approve"},
            new String[] {"POST", "/api/missions/M-999/tickets/approve/"},
            new String[] {"POST", "/api/missions/M-999/nudge"},
            new String[] {"POST", "/api/missions/M-999/workshop/directive"},
            new String[] {"POST", "/api/questions/Q-999/answer"},
            new String[] {"POST", "/api/missions/M-999/resume"},
            new String[] {"POST", "/api/agents/chris/resume"});

    private static final List<String[]> STILL_REFUSED = List.of(
            new String[] {"POST", "/api/terminals"},
            new String[] {"POST", "/api/terminals/T-1/attach"},
            new String[] {"POST", "/api/missions/M-999/workshop/chris/reject"},
            new String[] {"POST", "/api/questions/Q-999/ask-back"},
            new String[] {"POST", "/api/resume/session"},
            new String[] {"POST", "/api/missions/M-999/committees/1/send-back"},
            new String[] {"POST", "/api/missions/M-999/graph/extend"},
            // A bound token cannot mint another token, bound or not.
            new String[] {"POST", "/api/auth/tokens"},
            new String[] {"POST", "/api/auth/tokens/bound"},
            new String[] {"GET", "/api/preflight?project=x"},
            new String[] {"PUT", "/api/iam/settings"},
            new String[] {"PUT", "/api/iam/secrets/admin"});

    @Inject
    ScriptTokenBindings bindings;

    @Inject
    SecretVault vault;

    @Inject
    OfficeService office;

    @Inject
    KeycloakClient keycloak;

    String id;
    String secret;
    String offlineToken;

    @BeforeEach
    void aBoundToken() {
        var issued = api().body("{\"scope\":\"all\",\"days\":1,\"label\":\"bound\"}")
                .post("/api/auth/tokens")
                .then()
                .statusCode(201)
                .extract();
        id = issued.path("session.id");
        secret = issued.path("token");
        offlineToken = IamOnProfile.fake.refreshTokenFor("director-1", "floor42-desk");
        bindings.bind(id, new Tokens(null, offlineToken, null, Instant.now()), "director-1");
    }

    @AfterEach
    void cleanUp() {
        bindings.useClock(Clock.systemUTC());
        IamOnProfile.fake.down(false);
        api().delete("/api/auth/sessions/" + id);
    }

    private static RequestSpecification as(String bearer) {
        return given().header("Authorization", "Bearer " + bearer)
                .contentType("application/json")
                .body("{}");
    }

    private void minutesLater() {
        bindings.useClock(Clock.offset(Clock.systemUTC(), Duration.ofSeconds(61)));
    }

    private long refreshes() {
        return IamOnProfile.fake.requests().stream()
                .filter(r -> "refresh_token".equals(r.get("grant_type")))
                .count();
    }

    @Test
    void aBoundTokenSteersEveryRouteAnUnboundOneIsRefusedOn() {
        for (String[] r : STEERED) {
            int status = as(secret).request(r[0], r[1]).statusCode();
            assertThat(status).as(r[1]).isNotIn(401, 403, 503);
        }
    }

    @Test
    void aBoundTokenOfScopeMissionsWriteKeepsItsScope() {
        var issued = api().body("{\"scope\":\"missions:write\",\"days\":1}")
                .post("/api/auth/tokens")
                .then()
                .statusCode(201)
                .extract();
        String narrow = issued.path("token");
        String narrowId = issued.path("session.id");
        bindings.bind(
                narrowId,
                new Tokens(null, IamOnProfile.fake.refreshTokenFor("director-1", "floor42-desk"), null, Instant.now()),
                "director-1");
        try {
            as(narrow).post("/api/missions/M-999/resume").then().statusCode(403).body("code", equalTo("SCOPE"));
            assertThat(as(narrow).post("/api/missions/M-999/nudge").statusCode())
                    .isNotIn(401, 403, 503);
        } finally {
            api().delete("/api/auth/sessions/" + narrowId);
        }
    }

    @Test
    void aBoundTokenStillCannotApproveAPlan() {
        for (String path : List.of("/api/missions/M-999/approve", "/api/missions/M-999/approve/")) {
            as(secret).post(path).then().statusCode(403).body("code", equalTo("HUMAN_ONLY"));
        }
    }

    @Test
    void aBoundTokenKeepsTheDefaultDenyElsewhere() {
        for (String[] r : STILL_REFUSED) {
            as(secret).request(r[0], r[1]).then().statusCode(403).body("code", equalTo("IAM_SCRIPT_TOKEN_REFUSED"));
        }
    }

    @Test
    void aBoundTokenAnswersAnAgentsQuestionThroughTheDirectorMcp() {
        JsonNode result = given().contentType("application/json")
                .header("Authorization", "Bearer " + secret)
                .body(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"answer_question\","
                                + "\"arguments\":{\"id\":\"Q-999\",\"answer\":\"x\"}}}")
                .post("/api/mcp")
                .then()
                .statusCode(200)
                .extract()
                .as(JsonNode.class)
                .path("result");
        assertThat(result.path("content").path(0).path("text").asText())
                .doesNotContain("IAM_SCRIPT_TOKEN_REFUSED")
                .contains("UNKNOWN_QUESTION");
    }

    @Test
    void aBoundTokenWhoseKeycloakSessionEndedIsRefusedOnEveryRoute() throws Exception {
        keycloak.revoke(offlineToken);
        minutesLater();
        as(secret).get("/api/missions").then().statusCode(401).body("code", equalTo("SCRIPT_TOKEN_UNBOUND"));
        as(secret)
                .post("/api/missions/M-999/nudge")
                .then()
                .statusCode(401)
                .body("code", equalTo("SCRIPT_TOKEN_UNBOUND"));
        long calls = refreshes();
        // Dead for good: no further call to Keycloak, whatever the route.
        as(secret).get("/api/missions").then().statusCode(401);
        assertThat(refreshes()).isEqualTo(calls);
    }

    @Test
    void keycloakUnreachableRefusesAndTheTokenLivesAgainWhenItAnswers() {
        minutesLater();
        IamOnProfile.fake.down(true);
        as(secret).get("/api/missions").then().statusCode(503).body("code", equalTo("IDP_UNAVAILABLE"));
        as(secret).post("/api/missions/M-999/nudge").then().statusCode(503).body("code", equalTo("IDP_UNAVAILABLE"));
        IamOnProfile.fake.down(false);
        // The failure is remembered for 5 s: the next use after that asks again.
        bindings.useClock(Clock.offset(Clock.systemUTC(), Duration.ofSeconds(70)));
        as(secret).get("/api/missions").then().statusCode(200);
    }

    @Test
    void aBoundTokenRefreshesAtMostOncePerMinute() {
        long before = refreshes();
        for (int i = 0; i < 3; i++) as(secret).get("/api/missions").then().statusCode(200);
        assertThat(refreshes()).isEqualTo(before);
        minutesLater();
        for (int i = 0; i < 3; i++) as(secret).get("/api/missions").then().statusCode(200);
        assertThat(refreshes()).isEqualTo(before + 1);
    }

    @Test
    void theOfflineTokenNeverLeavesTheVault() {
        String sessions = api().get("/api/auth/sessions").asString();
        assertThat(sessions).doesNotContain(offlineToken);
        assertThat(secret).isNotEqualTo(offlineToken);
        assertThat(vault.get(ScriptTokenBindings.entry(id))).hasValue(offlineToken.getBytes(StandardCharsets.UTF_8));
        String refused = as(secret).post("/api/terminals").asString();
        assertThat(refused).doesNotContain(offlineToken);
    }

    @Test
    void revokingTheTokenFromTheDeskEndsItsKeycloakSession() {
        api().delete("/api/auth/sessions/" + id).then().statusCode(204);
        assertThat(IamOnProfile.fake.requests().stream()
                        .anyMatch(r -> r.get("_path").endsWith("/revoke")))
                .isTrue();
        assertThat(vault.get(ScriptTokenBindings.entry(id))).isEmpty();
        as(secret).get("/api/missions").then().statusCode(401);
    }

    @Test
    void anUnboundTokenKeepsTheDefaultDeny() {
        String unbound = api().body("{\"scope\":\"all\",\"days\":1}")
                .post("/api/auth/tokens")
                .then()
                .statusCode(201)
                .extract()
                .path("token");
        for (String[] r : STEERED) {
            as(unbound).request(r[0], r[1]).then().statusCode(403).body("code", equalTo("IAM_SCRIPT_TOKEN_REFUSED"));
        }
    }

    /** A real mission with a question of Floor42's: a decision, so the desk's, whatever the token. */
    @Test
    void aBoundTokenDoesNotAnswerAFloor42Decision() throws Exception {
        String repository = LiveMissionTest.gitRepository().toString().replace("\\", "\\\\");
        MissionId m =
                new MissionId(api().body("{\"title\":\"Q\",\"objective\":\"o\",\"repository\":\"" + repository + "\"}")
                        .post("/api/missions")
                        .then()
                        .statusCode(201)
                        .extract()
                        .path("id"));
        office.execute(c -> c.desk()
                .ask(
                        office.context(Actor.system(), Origin.REAL),
                        m,
                        "Spend more?",
                        "The budget is reached",
                        java.util.List.of("yes", "no"),
                        null,
                        Criticality.LOW,
                        false,
                        null));
        String question = office.query(c -> c.desk().of(m).get(0).id().value());
        as(secret)
                .body("{\"answer\":\"yes\"}")
                .post("/api/questions/" + question + "/answer")
                .then()
                .statusCode(403)
                .body("code", equalTo("HUMAN_ONLY"));
    }
}
