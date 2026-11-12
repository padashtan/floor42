package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.MissionId;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.runtime.ContextWindows;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The context gauge (W18) with the fake Claude Code: a live session's context in use comes on /ws/context and stays
 * readable from /api/sessions/{id}/context, the last message's usage, its subagent's left out. Nothing is journaled.
 */
@QuarkusTest
class ContextGaugeApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    Vertx vertx;

    @TestHTTPResource("/")
    URI base;

    final ObjectMapper json = new ObjectMapper();

    WebSocketConnectOptions options(String authorization) {
        WebSocketConnectOptions o = new WebSocketConnectOptions()
                .setHost("127.0.0.1")
                .setPort(base.getPort())
                .setURI("/ws/context");
        if (authorization != null) o.addHeader("Authorization", authorization);
        return o;
    }

    @Test
    void aLiveSessionsContextIsPushedAndKept() throws Exception {
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        WebSocket ws = vertx.createWebSocketClient()
                .connect(options("Bearer " + token.value()))
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        ws.textMessageHandler(m -> {
            try {
                received.add(json.readTree(m));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Gauge\",\"objective\":\"Describe this repository [fake:context]\","
                        + "\"repository\":\"" + repo.toString().replace("\\", "\\\\") + "\",\"plan\":true}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        MissionId m = new MissionId(id);
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200);

        await(
                "the planner's context on the stream",
                Duration.ofSeconds(30),
                () -> received.stream().anyMatch(r -> r.path("mission").asText().equals(id)));
        await(
                "the plan",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.tickets().of(m).size() == 4));
        ws.close();

        List<JsonNode> mine = received.stream()
                .filter(r -> r.path("mission").asText().equals(id))
                .toList();
        assertThat(mine)
                .as("two lines of one message: one reading; the subagent's line: none")
                .hasSize(1);
        JsonNode reading = mine.getFirst();
        assertThat(reading.path("usedTokens").asLong()).isEqualTo(4L + 1_200L + 18_000L);
        assertThat(reading.path("agent").asText()).isEqualTo("geoffrey");
        String model = reading.path("model").asText();
        Long window = ContextWindows.of(model);
        if (window == null) assertThat(reading.path("windowTokens").isNull()).isTrue();
        else assertThat(reading.path("windowTokens").asLong()).isEqualTo(window);

        // The session id is the one the mission lists.
        String session = reading.path("session").asText();
        List<String> sessions =
                office.query(c -> c.sessions().of(m).stream().map(s -> s.id()).toList());
        assertThat(sessions).contains(session);
        // As the UI asks for it: the id encoded in the path (it holds a colon).
        api().urlEncodingEnabled(false)
                .get("/api/sessions/" + URLEncoder.encode(session, StandardCharsets.UTF_8) + "/context")
                .then()
                .statusCode(200)
                .body("usedTokens", org.hamcrest.Matchers.equalTo(19_204))
                .body("session", org.hamcrest.Matchers.equalTo(session));
        api().get("/api/sessions/{id}/context", "M-0:nobody").then().statusCode(404);
        given().get("/api/sessions/{id}/context", session).then().statusCode(401);

        api().body("{\"reason\":\"test done\"}").post("/api/missions/" + id + "/cancel");
    }

    @Test
    void aKnownSessionWithoutAReadingAnswersNoContentAndAnUnknownOneNotFound() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Quiet\",\"objective\":\"Describe this repository\"," + "\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\",\"plan\":true}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200);
        await(
                "a session of the mission",
                Duration.ofSeconds(30),
                () -> !office.query(c -> c.sessions().of(new MissionId(id))).isEmpty());
        String session =
                office.query(c -> c.sessions().of(new MissionId(id)).getFirst().id());

        api().urlEncodingEnabled(false)
                .get("/api/sessions/" + URLEncoder.encode(session, StandardCharsets.UTF_8) + "/context")
                .then()
                .statusCode(204);
        api().get("/api/sessions/{id}/context", "M-0:nobody").then().statusCode(404);

        api().body("{\"reason\":\"test done\"}").post("/api/missions/" + id + "/cancel");
    }

    @Test
    void theStreamRequiresTheSessionToken() {
        var attempt = vertx.createWebSocketClient()
                .connect(options(null))
                .toCompletionStage()
                .toCompletableFuture();

        assertThat(attempt).failsWithin(Duration.ofSeconds(5));
    }
}
