package dev.floor42.server.api.iam;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.JsonNode;
import dev.floor42.server.api.ApiTestBase;
import dev.floor42.server.iam.IamOnProfile;
import dev.floor42.server.security.LocalAccessGuard;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.RedirectConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * In IAM mode a script token starts no agent work without a human (1.2.0): approving tickets, a nudge, a directive and
 * an answer are the director's, as plan approval already is. Past the guard, an unknown id answers 404 or 400: only a
 * 403 {@code IAM_SCRIPT_TOKEN_REFUSED} is the refusal under test.
 */
@QuarkusTest
@TestProfile(IamOnProfile.class)
class ScriptTokenInIamTest extends ApiTestBase {

    private static final String CODE = "IAM_SCRIPT_TOKEN_REFUSED";
    /** Method and path: each must be refused before the scope is looked at, whatever the token's scope. */
    private static final List<String[]> ROUTES = List.of(
            new String[] {"POST", "/api/missions/M-999/tickets/approve"},
            new String[] {"POST", "/api/missions/M-999/tickets/approve/"},
            new String[] {"POST", "/api/missions/M-999/approve"},
            new String[] {"POST", "/api/missions/M-999/nudge"},
            new String[] {"POST", "/api/missions/M-999/workshop/directive"},
            new String[] {"POST", "/api/missions/M-999/workshop/chris/reject"},
            new String[] {"POST", "/api/questions/Q-999/answer"},
            new String[] {"POST", "/api/questions/Q-999/ask-back"},
            new String[] {"POST", "/api/missions/M-999/resume"},
            new String[] {"POST", "/api/resume/session"},
            new String[] {"POST", "/api/agents/chris/resume"},
            new String[] {"POST", "/api/terminals"},
            new String[] {"POST", "/api/terminals/T-1/attach"},
            new String[] {"GET", "/ws/terminal/T-1"},
            new String[] {"POST", "/api/missions/M-999/committees/1/send-back"},
            new String[] {"POST", "/api/missions/M-999/graph/extend"},
            new String[] {"GET", "/api/preflight?project=x"},
            new String[] {"GET", "/api/projects/folders"},
            new String[] {"GET", "/api/projects/init?path=x"},
            new String[] {"PUT", "/api/iam/settings"},
            new String[] {"PUT", "/api/iam/secrets/admin"},
            // grants and MCP ticks are OpenFGA tuples written by the director on screen only (ADR-0080)
            new String[] {"PUT", "/api/iam/agents/chris/grants"},
            new String[] {"PUT", "/api/iam/mcp-servers/demo-api/tools"},
            new String[] {"DELETE", "/api/iam/resources/demo-api"});

    private static final List<String> READ_TOOLS =
            List.of("list_missions", "get_mission", "list_questions", "get_costs");
    private static final RestAssuredConfig NO_REDIRECT =
            RestAssuredConfig.config().redirect(RedirectConfig.redirectConfig().followRedirects(false));

    private String issued(String scope) {
        return api().body("{\"scope\":\"" + scope + "\",\"days\":1,\"label\":\"iam\"}")
                .post("/api/auth/tokens")
                .then()
                .statusCode(201)
                .extract()
                .path("token");
    }

    private static io.restassured.specification.RequestSpecification as(String bearer) {
        return given().header("Authorization", "Bearer " + bearer)
                .contentType("application/json")
                .body("{}");
    }

    private static int call(io.restassured.specification.RequestSpecification spec, String method, String path) {
        return spec.request(method, path).statusCode();
    }

    @Test
    void aScriptTokenIsDeniedByDefault() {
        for (String scope : List.of("missions:write", "all")) {
            String bearer = issued(scope);
            for (String[] r : ROUTES) {
                as(bearer).request(r[0], r[1]).then().statusCode(403).body("code", equalTo(CODE));
            }
        }
    }

    @Test
    void aScriptTokenProposesAMissionWithItsPlainFieldsOnly() {
        String bearer = issued("missions:write");
        String base = "\"objective\":\"o\",\"repository\":\"/nowhere\"";
        for (String extra : List.of(
                "\"plan\":true",
                "\"panel\":\"idea\"",
                "\"committee\":[\"x\"]",
                "\"workflow\":\"w\"",
                "\"deliverTo\":\"deliverables\"",
                "\"mode\":\"graph\"")) {
            given().header("Authorization", "Bearer " + bearer)
                    .contentType("application/json")
                    .body("{" + base + "," + extra + "}")
                    .post("/api/missions")
                    .then()
                    .statusCode(403)
                    .body("code", equalTo(CODE));
        }
        given().header("Authorization", "Bearer " + bearer)
                .contentType("application/json")
                .body("{\"title\":\"t\"," + base + "}")
                .post("/api/missions")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_REPOSITORY"));
    }

    @Test
    void aDoubleLeadingSlashDoesNotReachTheResource() throws Exception {
        String bearer = issued("missions:write");
        HttpResponse<String> r = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + io.restassured.RestAssured.port
                                        + "//api/missions/M-1/tickets/approve"))
                                .header("Authorization", "Bearer " + bearer)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isIn(400, 403, 404);
        assertThat(r.body()).doesNotContain("UNKNOWN_MISSION");
    }

    @Inject
    io.vertx.core.Vertx vertx;

    @Test
    void aScriptTokenIsRefusedOnTheEventSocket() {
        String bearer = issued("missions:write");
        var options = new io.vertx.core.http.WebSocketConnectOptions()
                .setHost("127.0.0.1")
                .setPort(io.restassured.RestAssured.port)
                .setURI("/ws/events")
                .addHeader("Authorization", "Bearer " + bearer)
                .addHeader("Origin", "http://127.0.0.1:" + io.restassured.RestAssured.port);
        assertThatThrownBy(() -> vertx.createWebSocketClient()
                        .connect(options)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get(10, java.util.concurrent.TimeUnit.SECONDS))
                .hasMessageContaining("403");
    }

    @Test
    void aScriptTokenStillReadsAndProposesAMission() {
        String bearer = issued("missions:write");
        assertThat(call(as(bearer), "GET", "/api/missions")).isEqualTo(200);
        assertThat(call(as(bearer), "POST", "/api/missions")).isNotIn(401, 403);
    }

    private JsonNode tool(String bearer, String name, String arguments) {
        return given().contentType("application/json")
                .header("Authorization", "Bearer " + bearer)
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                        + "\",\"arguments\":" + arguments + "}}")
                .post("/api/mcp")
                .then()
                .statusCode(200)
                .extract()
                .as(JsonNode.class)
                .path("result");
    }

    @Test
    void theMcpToolsThatAnswerAreRefusedAndTheReadOnesAndCreateAreNot() {
        String bearer = issued("missions:write");
        JsonNode refused = tool(bearer, "answer_question", "{\"id\":\"Q-999\",\"answer\":\"x\"}");
        assertThat(refused.path("isError").asBoolean()).isTrue();
        assertThat(refused.path("content").path(0).path("text").asText()).startsWith(CODE + ":");
        for (String name : READ_TOOLS) {
            String text = tool(bearer, name, "{\"id\":\"M-999\"}")
                    .path("content")
                    .path(0)
                    .path("text")
                    .asText();
            assertThat(text).as(name).doesNotStartWith(CODE);
        }
        String created = tool(bearer, "create_mission", "{}")
                .path("content")
                .path(0)
                .path("text")
                .asText();
        assertThat(created).doesNotStartWith(CODE);
    }

    /** Routes whose answer for an unknown id is harmless, to show the director's credentials are not refused. */
    private static final List<String[]> HARMLESS = List.of(
            new String[] {"POST", "/api/missions/M-999/tickets/approve"},
            new String[] {"POST", "/api/missions/M-999/tickets/approve/"},
            new String[] {"POST", "/api/missions/M-999/nudge"},
            new String[] {"POST", "/api/questions/Q-999/answer"},
            new String[] {"POST", "/api/missions/M-999/resume"});

    @Test
    void theDirectorsOwnTokenAndBrowserSessionStillReachThem() throws Exception {
        String cookie = signedInCookie();
        for (String[] r : HARMLESS) {
            Response own = api().body("{}").request(r[0], r[1]);
            assertThat(own.statusCode()).as(r[1]).isNotIn(401, 403);
            Response browser = given().cookie(LocalAccessGuard.COOKIE, cookie)
                    .header(LocalAccessGuard.REQUEST_HEADER, "1")
                    .header("Origin", "http://127.0.0.1:" + io.restassured.RestAssured.port)
                    .contentType("application/json")
                    .body("{}")
                    .request(r[0], r[1]);
            assertThat(browser.statusCode()).as("browser: " + r[1]).isNotIn(401, 403);
        }
    }

    private String signedInCookie() throws Exception {
        Response begun = given().config(NO_REDIRECT).get("/auth/keycloak");
        begun.then().statusCode(302);
        HttpResponse<String> back = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(begun.header("Location")))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        URI callback = URI.create(back.headers().firstValue("Location").orElseThrow());
        return given().config(NO_REDIRECT)
                .cookies(begun.getCookies())
                .get(callback.getPath() + "?" + callback.getRawQuery())
                .then()
                .statusCode(303)
                .extract()
                .detailedCookie(LocalAccessGuard.COOKIE)
                .getValue();
    }
}
