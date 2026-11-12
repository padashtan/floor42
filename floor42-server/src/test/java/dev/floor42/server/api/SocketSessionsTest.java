package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.security.DirectorSignIn;
import dev.floor42.server.security.LocalAccessGuard;
import dev.floor42.server.security.SessionRegistry;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.RedirectConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A socket never outlives the credential it came with (ADR-0044), nor a shell the session that opened it. */
@QuarkusTest
class SocketSessionsTest {

    @Inject
    SessionToken token;

    @Inject
    SessionRegistry sessions;

    @Inject
    DirectorSignIn signIn;

    @Inject
    SocketSessions socketSessions;

    @Inject
    Vertx vertx;

    @TestHTTPResource("/")
    URI base;

    final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void noSeam() {
        socketSessions.seam(null, null);
    }

    String own() {
        return "http://127.0.0.1:" + base.getPort();
    }

    RequestSpecification browser(String cookie) {
        return given().cookie(LocalAccessGuard.COOKIE, cookie)
                .header("Origin", own())
                .header(LocalAccessGuard.REQUEST_HEADER, "1");
    }

    /** A socket: ends with its close code. */
    record Feed(CompletableFuture<Short> closed) {
        boolean isClosed() {
            return closed.isDone();
        }
    }

    Feed connect(String path, String headerName, String headerValue) throws Exception {
        CompletableFuture<Short> closed = new CompletableFuture<>();
        WebSocketConnectOptions o = new WebSocketConnectOptions()
                .setHost("127.0.0.1")
                .setPort(base.getPort())
                .setURI(path)
                .addHeader(headerName, headerValue)
                .addHeader("Origin", own());
        vertx.createWebSocketClient()
                .connect(o)
                .map(ws -> {
                    ws.closeHandler(v -> closed.complete(ws.closeStatusCode()));
                    return ws;
                })
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        return new Feed(closed);
    }

    Feed feed(String path, String cookie) throws Exception {
        return connect(path, "Cookie", LocalAccessGuard.COOKIE + "=" + cookie);
    }

    JsonNode openShell(String cookie, String name) throws Exception {
        Path dir = Files.createTempDirectory("floor42-sockets-" + name).toRealPath();
        dir.toFile().deleteOnExit();
        String body = browser(cookie)
                .contentType(ContentType.JSON)
                .body(Map.of("project", dir.toString(), "columns", 100, "rows", 30))
                .post("/api/terminals")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        return json.readTree(body);
    }

    Feed terminalSocket(String cookie, JsonNode access) throws Exception {
        String id = access.path("terminal").path("id").asText();
        return feed("/ws/terminal/" + id + "?ticket=" + access.path("ticket").asText(), cookie);
    }

    List<String> shells() {
        return given().header("Authorization", "Bearer " + token.value())
                .get("/api/terminals")
                .jsonPath()
                .getList("terminals.id");
    }

    String sessionId(String userAgent) {
        return sessions.list().stream()
                .filter(s -> userAgent.equals(s.userAgent()))
                .findFirst()
                .orElseThrow()
                .id();
    }

    void closes(Feed f) throws Exception {
        await("the socket closed", Duration.ofSeconds(2), f::isClosed);
    }

    void revoke(String id) {
        given().header("Authorization", "Bearer " + token.value())
                .delete("/api/auth/sessions/" + id)
                .then()
                .statusCode(204);
    }

    @Test
    void signingOutClosesTheSessionsSocketsAndShells() throws Exception {
        String cookie = sessions.openBrowser("out").secret();
        JsonNode access = openShell(cookie, "out");
        String shell = access.path("terminal").path("id").asText();
        Feed screen = terminalSocket(cookie, access);
        Feed events = feed("/ws/events", cookie);

        browser(cookie).post("/api/auth/sign-out").then().statusCode(204);

        closes(events);
        closes(screen);
        await("no shell left", Duration.ofSeconds(2), () -> !shells().contains(shell));
    }

    @Test
    void aLinkUsedTwiceClosesTheSocketsAndShellsOfTheSessionItOpened() throws Exception {
        for (int i = 0; ; i++) {
            int status = given().post("/auth/open").statusCode();
            if (status != 429 || i == 5) break;
            Thread.sleep(2100);
        }
        String link = signIn.lastLink();
        String path = link.substring(link.indexOf("/auth"));
        RestAssuredConfig noRedirect =
                RestAssured.config().redirect(RedirectConfig.redirectConfig().followRedirects(false));
        String cookie = given().config(noRedirect)
                .get(path)
                .then()
                .statusCode(303)
                .extract()
                .detailedCookie(LocalAccessGuard.COOKIE)
                .getValue();
        JsonNode access = openShell(cookie, "twice");
        String shell = access.path("terminal").path("id").asText();
        Feed events = feed("/ws/events", cookie);

        given().config(noRedirect).get(path).then().statusCode(401);

        closes(events);
        await("no shell left", Duration.ofSeconds(2), () -> !shells().contains(shell));
    }

    @Test
    void revokingClosesEverySocketAndTheShell() throws Exception {
        String cookie = sessions.openBrowser("every").secret();
        JsonNode access = openShell(cookie, "every");
        String shell = access.path("terminal").path("id").asText();
        List<Feed> all = List.of(
                feed("/ws/context", cookie),
                feed("/ws/api-calls", cookie),
                feed("/ws/events", cookie),
                terminalSocket(cookie, access));

        revoke(sessionId("every"));

        for (Feed f : all) closes(f);
        await("no shell left", Duration.ofSeconds(2), () -> !shells().contains(shell));
    }

    @Test
    void aSocketOfAnotherSessionOnAShellIsClosedWithItsOwnSessionAndTheShellStays() throws Exception {
        String a = sessions.openBrowser("owner").secret();
        String b = sessions.openBrowser("guest").secret();
        JsonNode access = openShell(a, "shared");
        String shell = access.path("terminal").path("id").asText();
        String ticket = browser(b)
                .post("/api/terminals/" + shell + "/attach")
                .then()
                .statusCode(200)
                .extract()
                .path("ticket");
        Feed theirs = feed("/ws/terminal/" + shell + "?ticket=" + ticket, b);

        revoke(sessionId("guest"));

        closes(theirs);
        assertThat(shells()).contains(shell);
        browser(a).delete("/api/terminals/" + shell).then().statusCode(200);
    }

    @Test
    void aScriptTokenOnASocketIsBoundAndClosedWhenItIsRevoked() throws Exception {
        SessionRegistry.Issued script = sessions.issueScript(SessionRegistry.Scope.READ, 1, "script", "script ua");
        Feed events = connect("/ws/events", "Authorization", "Bearer " + script.secret());

        revoke(script.session().id());

        closes(events);
    }

    @Test
    void theDirectorsOwnTokenSocketsCloseWhenTheTokenIsReplaced() throws Exception {
        String cookie = sessions.openBrowser("everywhere").secret();
        Feed events = connect("/ws/events", "Authorization", "Bearer " + token.value());

        browser(cookie).post("/api/auth/sign-out-everywhere").then().statusCode(204);

        closes(events);
    }

    @Test
    void aSocketWhoseSessionEndsBetweenTheUpgradeAndTheBindingIsClosed() throws Exception {
        SessionRegistry.Issued issued = sessions.openBrowser("window");
        socketSessions.seam(() -> sessions.revoke(issued.session().id()), null);

        Feed events = feed("/ws/events", issued.secret());

        closes(events);
    }

    @Test
    void aSessionSignedInDuringASweepKeepsItsSocketAndItsShell() throws Exception {
        Feed[] feeds = new Feed[1];
        String[] shell = new String[1];
        socketSessions.seam(null, () -> {
            try {
                String cookie = sessions.openBrowser("late").secret();
                JsonNode access = openShell(cookie, "late");
                shell[0] = access.path("terminal").path("id").asText();
                feeds[0] = feed("/ws/events", cookie);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        socketSessions.sweep();
        socketSessions.seam(null, null);
        socketSessions.sweep();

        assertThat(feeds[0].isClosed()).isFalse();
        assertThat(shells()).contains(shell[0]);
        revoke(sessionId("late"));
    }
}
