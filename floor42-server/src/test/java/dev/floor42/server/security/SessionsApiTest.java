package dev.floor42.server.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.QuestionId;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TerminalEvent.ApiTokenIssued;
import dev.floor42.domain.event.TerminalEvent.ApiTokenRevoked;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.journal.Journal;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/** ADR-0044: sessions and script tokens, in memory; the journal keeps a token's SHA-256 and never its value. */
@QuarkusTest
@TestProfile(SessionsApiTest.Own.class)
class SessionsApiTest {

    /** Its own daemon: signing out everywhere replaces the director's token, which other classes hold. */
    public static class Own implements QuarkusTestProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            return java.util.Map.of("floor42.live-missions.enabled", "false");
        }
    }

    @Inject
    SessionToken token;

    @Inject
    SessionRegistry sessions;

    @Inject
    Journal journal;

    @Inject
    OfficeService office;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    @TestHTTPResource("/")
    URI base;

    String own() {
        return "http://127.0.0.1:" + base.getPort();
    }

    Response issue(String scope, int days) {
        return given().header("Authorization", "Bearer " + token.value())
                .contentType("application/json")
                .body("{\"scope\":\"" + scope + "\",\"days\":" + days + ",\"label\":\"bench\"}")
                .post("/api/auth/tokens");
    }

    String issued(String scope) {
        Response r = issue(scope, 7);
        r.then().statusCode(201).body("session.scope", equalTo(scope)).body("session.kind", equalTo("SCRIPT"));
        return r.jsonPath().getString("token");
    }

    @Test
    void aReadTokenReadsAndGets403OnAnythingThatChanges() {
        String read = issued("read");
        given().header("Authorization", "Bearer " + read)
                .get("/api/missions")
                .then()
                .statusCode(200);
        given().header("Authorization", "Bearer " + read)
                .contentType("application/json")
                .body("{\"text\":\"x\"}")
                .post("/api/notes")
                .then()
                .statusCode(403)
                .body("code", equalTo("SCOPE"));
        // A read token cannot mint itself a stronger one.
        given().header("Authorization", "Bearer " + read)
                .contentType("application/json")
                .body("{\"scope\":\"all\",\"days\":1}")
                .post("/api/auth/tokens")
                .then()
                .statusCode(403);
    }

    /** The status a POST with this token gets: 403 {@code SCOPE} is the guard's refusal, anything else got past it. */
    int post(String bearer, String path) {
        return given().header("Authorization", "Bearer " + bearer)
                .contentType("application/json")
                .body("{}")
                .post(path)
                .statusCode();
    }

    @Test
    void aMissionsTokenCreatesApprovesAndDirectsMissions() {
        String missions = issued("missions:write");
        for (String path : List.of(
                "/api/missions",
                "/api/missions/M-999/approve",
                "/api/missions/M-999/reject",
                "/api/missions/M-999/tickets/approve",
                "/api/missions/M-999/workshop/directive",
                "/api/missions/M-999/nudge",
                "/api/questions/Q-999/answer")) {
            assertThat(post(missions, path)).as("past the guard: " + path).isNotIn(401, 403);
        }
        String all = issued("all");
        given().header("Authorization", "Bearer " + all)
                .contentType("application/json")
                .body("{\"text\":\"x\"}")
                .post("/api/notes")
                .then()
                .statusCode(200);
    }

    /**
     * Before 1.8.0 {@code missions:write} reached every route under {@code /api/missions} and {@code /api/questions}:
     * the director's files, the workshop's acceptance, pull requests, budgets, permissions. Each now needs {@code all}.
     */
    @Test
    void aMissionsTokenGets403OnFilesAcceptancePullRequestsAndPermissions() {
        String missions = issued("missions:write");
        String all = issued("all");
        for (String path : List.of(
                "/api/notes",
                "/api/missions/M-999/files",
                "/api/missions/M-999/files/commit",
                "/api/missions/M-999/files/update-agents",
                "/api/missions/M-999/workshop/chris/accept",
                "/api/missions/M-999/workshop/chris/reject",
                "/api/missions/M-999/workshop/chris/checks",
                "/api/missions/M-999/pull-requests",
                "/api/missions/M-999/pull-requests/1/checks",
                "/api/missions/M-999/resume",
                "/api/missions/M-999/complete",
                "/api/missions/M-999/objections",
                "/api/missions/M-999/cancel",
                "/api/missions/M-999/committee/contribute",
                "/api/missions/M-999/deliverables/open-folder",
                "/api/missions/M-999/replay-export",
                "/api/questions/Q-999/forget")) {
            assertThat(post(missions, path)).as("refused: " + path).isEqualTo(403);
            assertThat(post(all, path)).as("the director's scope: " + path).isNotIn(401, 403);
        }
        for (String path : List.of("/api/missions/M-999/budget", "/api/missions/M-999/tools/Bash")) {
            int status = given().header("Authorization", "Bearer " + missions)
                    .contentType("application/json")
                    .body("{}")
                    .put(path)
                    .statusCode();
            assertThat(status).as("refused: PUT " + path).isEqualTo(403);
        }
    }

    /**
     * A permission question grants an agent's tool call: the desk refuses it to {@code missions:write}, which may still
     * answer the mission's other questions. The guard sees only {@code /api/questions/{id}/answer}; the desk knows the
     * kind.
     */
    @Test
    void aMissionsTokenAnswersAMissionsQuestionButNotAPermission() throws Exception {
        String missions = issued("missions:write");
        MissionId m = new MissionId(given().header("Authorization", "Bearer " + token.value())
                .post("/api/demo/live?paceMs=0")
                .then()
                .statusCode(202)
                .extract()
                .path("mission"));
        dev.floor42.server.api.LiveTestSupport.await(
                "the demo's permission question", java.time.Duration.ofSeconds(10), () -> open(m, true) != null);
        String permission = open(m, true);
        given().header("Authorization", "Bearer " + missions)
                .contentType("application/json")
                .body("{\"answer\":\"ALLOW\"}")
                .post("/api/questions/" + permission + "/answer")
                .then()
                .statusCode(403)
                .body("code", equalTo("SCOPE"));
        QuestionStatus status = office.query(
                c -> c.desk().find(new QuestionId(permission)).orElseThrow().status());
        assertThat(status).as("still waiting for the director").isEqualTo(QuestionStatus.OPEN);

        office.execute(c -> c.desk()
                .ask(
                        office.context(Actor.system(), Origin.SIMULATED),
                        m,
                        "Which format?",
                        "The agents need a format for the notes",
                        List.of("Markdown", "Plain text"),
                        "Markdown",
                        Criticality.LOW,
                        false,
                        null));
        String own = open(m, false);
        given().header("Authorization", "Bearer " + missions)
                .contentType("application/json")
                .body("{\"answer\":\"Markdown\"}")
                .post("/api/questions/" + own + "/answer")
                .then()
                .statusCode(200);
        given().header("Authorization", "Bearer " + token.value())
                .contentType("application/json")
                .body("{\"answer\":\"DENY\"}")
                .post("/api/questions/" + permission + "/answer")
                .then()
                .statusCode(200);
    }

    /** The mission's first open question: a permission question, or one of any other kind. */
    String open(MissionId m, boolean permission) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q ->
                        q.status() == QuestionStatus.OPEN && (q.kind() == QuestionKind.ACTION_APPROVAL) == permission)
                .map(q -> q.id().value())
                .findFirst()
                .orElse(null));
    }

    @Test
    void theJournalKeepsTheHashNeverTheValueAndNothingAtRestHoldsIt() throws Exception {
        Response r = issue("read", 30);
        r.then().statusCode(201);
        String value = r.jsonPath().getString("token");
        String id = r.jsonPath().getString("session.id");
        ApiTokenIssued fact = journal.readAll().stream()
                .map(EventEnvelope.class::cast)
                .filter(e -> e.event() instanceof ApiTokenIssued t && t.token().equals(id))
                .map(e -> {
                    assertThat(e.origin()).isEqualTo(Origin.REAL);
                    assertThat(e.actor()).isEqualTo(Actor.director());
                    return (ApiTokenIssued) e.event();
                })
                .findFirst()
                .orElseThrow();
        assertThat(fact.sha256()).isEqualTo(SessionRegistry.sha256(value)).isNotEqualTo(value);
        assertThat(fact.scope()).isEqualTo("read");
        // No file of the data directory holds the value: no secret at rest.
        try (var files = Files.walk(dataDir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                assertThat(new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1))
                        .as(f.toString())
                        .doesNotContain(value);
            }
        }
        // The hash itself opens nothing.
        given().header("Authorization", "Bearer " + fact.sha256())
                .get("/api/missions")
                .then()
                .statusCode(401);
    }

    @Test
    void tokensAreBoundedAndListedWithoutTheirSecret() {
        issue("read", 31).then().statusCode(400);
        issue("read", 0).then().statusCode(400);
        issue("admin", 3).then().statusCode(400);
        String value = issued("read");
        var list = given().header("Authorization", "Bearer " + value).get("/api/auth/sessions");
        list.then().statusCode(200);
        assertThat(list.asString()).doesNotContain(value);
        List<Boolean> current = list.jsonPath().getList("current");
        assertThat(current).containsOnlyOnce(true);
    }

    @Test
    void aRevokedTokenStopsAtOnceAndItsEndIsJournaled() {
        Response r = issue("read", 2);
        String value = r.jsonPath().getString("token");
        String id = r.jsonPath().getString("session.id");
        given().header("Authorization", "Bearer " + token.value())
                .delete("/api/auth/sessions/" + id)
                .then()
                .statusCode(204);
        given().header("Authorization", "Bearer " + value)
                .get("/api/missions")
                .then()
                .statusCode(401);
        assertThat(journal.readAll())
                .anyMatch(e -> e.event() instanceof ApiTokenRevoked t
                        && t.token().equals(id)
                        && t.reason().equals("revoked")
                        && t.sha256().equals(SessionRegistry.sha256(value)));
        given().header("Authorization", "Bearer " + token.value())
                .delete("/api/auth/sessions/" + id)
                .then()
                .statusCode(404);
    }

    @Test
    void signingOutEndsThisBrowserAndClearsItsCookie() {
        String cookie = sessions.openBrowser("test").secret();
        var out = given().cookie(LocalAccessGuard.COOKIE, cookie)
                .header("Origin", own())
                .header(LocalAccessGuard.REQUEST_HEADER, "1")
                .post("/api/auth/sign-out");
        out.then().statusCode(204);
        assertThat(out.getDetailedCookie(LocalAccessGuard.COOKIE).getMaxAge()).isZero();
        given().cookie(LocalAccessGuard.COOKIE, cookie)
                .get("/api/missions")
                .then()
                .statusCode(401);
    }

    @Test
    void signingOutEverywhereEndsEverySessionAndReplacesTheDirectorsToken() {
        String before = token.value();
        String browser = sessions.openBrowser("test").secret();
        String script = issued("all");
        given().cookie(LocalAccessGuard.COOKIE, browser)
                .header("Origin", own())
                .header(LocalAccessGuard.REQUEST_HEADER, "1")
                .post("/api/auth/sign-out-everywhere")
                .then()
                .statusCode(204);
        for (String old : List.of(before, script)) {
            given().header("Authorization", "Bearer " + old)
                    .get("/api/missions")
                    .then()
                    .statusCode(401);
        }
        given().cookie(LocalAccessGuard.COOKIE, browser)
                .get("/api/missions")
                .then()
                .statusCode(401);
        assertThat(token.value()).isNotEqualTo(before);
        given().header("Authorization", "Bearer " + token.value())
                .get("/api/missions")
                .then()
                .statusCode(200);
        assertThat(journal.readAll())
                .anyMatch(e ->
                        e.event() instanceof ApiTokenRevoked t && t.reason().equals("everywhere"));
    }
}
