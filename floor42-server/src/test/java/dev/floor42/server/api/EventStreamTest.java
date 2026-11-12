package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EventStreamTest {

    @Inject
    SessionToken token;

    @Inject
    Vertx vertx;

    @TestHTTPResource("/")
    URI base;

    final ObjectMapper json = new ObjectMapper();

    WebSocketConnectOptions options(String authorization) {
        WebSocketConnectOptions o = new WebSocketConnectOptions()
                .setHost("127.0.0.1")
                .setPort(base.getPort())
                .setURI("/ws/events");
        if (authorization != null) o.addHeader("Authorization", authorization);
        return o;
    }

    @Test
    void theStreamCarriesStoredEventsWithProvenance() throws Exception {
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

        given().header("Authorization", "Bearer " + token.value())
                .post("/api/demo")
                .then()
                .statusCode(200);

        await(
                "the demo's events on the stream",
                Duration.ofSeconds(5),
                () -> received.stream()
                        .anyMatch(e -> e.path("type").asText().equals("QuestionAsked")
                                && e.path("params").path("subject").asText().contains("Should the summary")));
        ws.close();

        assertThat(received)
                .allMatch(e -> e.path("type").asText().equals("hello")
                        || e.path("origin").asText().equals("SIMULATED"));
        // Only the events recording a real run may light a machine; a decision never does.
        assertThat(received)
                .filteredOn(e -> e.path("ran").asBoolean())
                .extracting(e -> e.path("type").asText())
                .isNotEmpty()
                .isSubsetOf("ActionExecuted", "ActionFailed");
        assertThat(received)
                .filteredOn(e -> e.path("type").asText().equals("ActionAuthorized"))
                .allMatch(e -> !e.path("ran").asBoolean() && e.path("tool").isTextual());
    }

    @Test
    void theLogHistoryIsTheLatestEventsOldestFirst() {
        String auth = "Bearer " + token.value();
        given().header("Authorization", auth).post("/api/demo").then().statusCode(200);

        List<String> types = given().header("Authorization", auth)
                .get("/api/events?limit=5")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("type");
        List<String> journal = given().header("Authorization", auth)
                .get("/api/journal?limit=5")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("type");

        assertThat(types).hasSize(5).isEqualTo(journal);
        given().get("/api/events").then().statusCode(401);
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
