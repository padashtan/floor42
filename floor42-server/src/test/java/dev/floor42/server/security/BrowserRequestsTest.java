package dev.floor42.server.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * ADR-0044: SameSite does not tell ports apart, so a page on another local port (a server an agent started) sends the
 * director's cookie along. The daemon then needs the exact Origin and, for anything that changes state, the
 * {@code X-Floor42-Request} header, which such a page cannot set without a preflight the daemon never grants.
 */
@QuarkusTest
class BrowserRequestsTest {

    @Inject
    SessionToken token;

    @Inject
    SessionRegistry sessions;

    @Inject
    Vertx vertx;

    @TestHTTPResource("/")
    URI base;

    String own() {
        return "http://127.0.0.1:" + base.getPort();
    }

    String cookie() {
        return sessions.openBrowser("test browser").secret();
    }

    @Test
    void aPageOnAnotherLocalPortCannotChangeAnythingWithTheCookie() {
        String other = "http://127.0.0.1:" + (base.getPort() == 8000 ? 8001 : 8000);
        given().cookie(LocalAccessGuard.COOKIE, cookie())
                .header("Origin", other)
                .header(LocalAccessGuard.REQUEST_HEADER, "1")
                .contentType("application/json")
                .body("{}")
                .post("/api/agents/chris/resume")
                .then()
                .statusCode(403)
                .body("message", containsString("Origin"));
        // Nor read: CORS hides the answer anyway, and the daemon refuses before answering.
        given().cookie(LocalAccessGuard.COOKIE, cookie())
                .header("Origin", other)
                .get("/api/missions")
                .then()
                .statusCode(403);
        // localhost on another port is another origin too.
        given().cookie(LocalAccessGuard.COOKIE, cookie())
                .header("Origin", "http://localhost:" + base.getPort() + "0")
                .header(LocalAccessGuard.REQUEST_HEADER, "1")
                .post("/api/notes")
                .then()
                .statusCode(403);
    }

    @Test
    void aBrowserRequestThatChangesStateNeedsItsOriginAndTheHeader() {
        String c = cookie();
        // No Origin at all: a browser always sends one on a POST, so its absence is refused.
        given().cookie(LocalAccessGuard.COOKIE, c)
                .header(LocalAccessGuard.REQUEST_HEADER, "1")
                .contentType("application/json")
                .body("{\"text\":\"from the page\"}")
                .post("/api/notes")
                .then()
                .statusCode(403)
                .body("message", containsString("Origin"));
        // The right Origin without the header: what a plain form or a no-cors fetch could send.
        given().cookie(LocalAccessGuard.COOKIE, c)
                .header("Origin", own())
                .contentType("application/json")
                .body("{\"text\":\"from the page\"}")
                .post("/api/notes")
                .then()
                .statusCode(403)
                .body("message", containsString(LocalAccessGuard.REQUEST_HEADER));
        // Floor42's own page sends both.
        given().cookie(LocalAccessGuard.COOKIE, c)
                .header("Origin", own())
                .header(LocalAccessGuard.REQUEST_HEADER, "1")
                .contentType("application/json")
                .body("{\"text\":\"from the page\"}")
                .post("/api/notes")
                .then()
                .statusCode(200);
        // Reading needs neither.
        given().cookie(LocalAccessGuard.COOKIE, c).get("/api/missions").then().statusCode(200);
        // DELETE and PUT are changes too.
        given().cookie(LocalAccessGuard.COOKIE, c)
                .header("Origin", own())
                .delete("/api/terminals/TERM-0")
                .then()
                .statusCode(403);
    }

    @Test
    void scriptsWithABearerTokenNeedNeitherOriginNorHeaderButAForeignOriginIsStillRefused() {
        given().header("Authorization", "Bearer " + token.value())
                .contentType("application/json")
                .body("{\"text\":\"from a script\"}")
                .post("/api/notes")
                .then()
                .statusCode(200);
        given().header("Authorization", "Bearer " + token.value())
                .header("Origin", "http://127.0.0.1:5173")
                .get("/api/missions")
                .then()
                .statusCode(403);
    }

    @Test
    void anotherLocalPortCannotAskForASignInLink() {
        given().header("Origin", "http://127.0.0.1:1").post("/auth/open").then().statusCode(403);
    }

    int socket(String cookie, String origin, String authorization) throws Exception {
        WebSocketConnectOptions o = new WebSocketConnectOptions()
                .setHost("127.0.0.1")
                .setPort(base.getPort())
                .setURI("/ws/events");
        if (cookie != null) o.addHeader("Cookie", LocalAccessGuard.COOKIE + "=" + cookie);
        if (authorization != null) o.addHeader("Authorization", authorization);
        if (origin == null) {
            o.setAllowOriginHeader(false);
        } else if (!origin.equals("default")) {
            // setAllowOriginHeader(false) would drop this one too (PROGRESS gotchas).
            o.addHeader("Origin", origin);
        }
        try {
            var ws = vertx.createWebSocketClient()
                    .connect(o)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            ws.close();
            return 101;
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof io.vertx.core.http.UpgradeRejectedException r) return r.getStatus();
            throw e;
        }
    }

    @Test
    void theEventStreamOpensOnlyForTheDaemonsOwnOrigin() throws Exception {
        String c = cookie();
        // Vert.x names the daemon's own origin, as the page does.
        assertThat(socket(c, "default", null)).isEqualTo(101);
        assertThat(socket(c, own(), null)).isEqualTo(101);
        assertThat(socket(c, "http://127.0.0.1:1", null)).isEqualTo(403);
        assertThat(socket(c, null, null))
                .as("a browser always names its Origin")
                .isEqualTo(403);
        assertThat(socket(null, own(), null)).isEqualTo(401);
        // A script's bearer opens it without an Origin.
        assertThat(socket(null, null, "Bearer " + token.value())).isEqualTo(101);
    }

    @Test
    void aPathWithAMatrixParameterNeverSlipsPastTheGuard() throws Exception {
        // JAX-RS matches /api;x/agents to /api/agents: a path parameter must not hide a route from the guard.
        var http = java.net.http.HttpClient.newHttpClient();
        for (String path : java.util.List.of(
                "/api;x/agents",
                "/api;/agents",
                "/api/agents;x",
                "/api%3Bx/agents",
                "/ws;x/events",
                "/x/..;/api/agents")) {
            for (String method : java.util.List.of("GET", "POST")) {
                var request = java.net.http.HttpRequest.newBuilder(
                                URI.create(base.toString().replaceAll("/$", "") + path))
                        .method(method, java.net.http.HttpRequest.BodyPublishers.noBody())
                        .build();
                var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(method + " " + path).isEqualTo(400);
                assertThat(response.body()).as(method + " " + path).contains("PATH_REFUSED");
            }
        }
    }

    @Test
    void aRequestAProxyForwardedIsRefusedWithAClearError() {
        for (String header : java.util.List.of("X-Forwarded-For", "X-Forwarded-Host", "Forwarded")) {
            given().header("Authorization", "Bearer " + token.value())
                    .header(header, "203.0.113.7")
                    .get("/api/missions")
                    .then()
                    .statusCode(403)
                    .body("code", equalTo("FORWARDED"))
                    .body("message", containsString("SSH tunnel"));
        }
        given().header("X-Forwarded-Host", "floor42.example")
                .get("/api/status")
                .then()
                .statusCode(403);
    }

    @Test
    void thePageCarriesAStrictPolicyAndTheApiNoSniff() {
        // Quinoa does not serve the UI in tests: the HTML any non-API path gets is the daemon's own page.
        var page = given().get("/some/page");
        String csp = page.header("Content-Security-Policy");
        assertThat(csp)
                .contains("default-src 'self'")
                .contains("script-src 'self';")
                .contains("style-src 'self';")
                .contains("connect-src 'self' ws://127.0.0.1:" + base.getPort())
                .contains("frame-ancestors 'none'")
                .contains("object-src 'none'")
                .contains("base-uri 'none'")
                .doesNotContain("unsafe-eval");
        // The one exception: xterm.js's own <style> elements; scripts and style attributes stay refused.
        assertThat(csp.split(";"))
                .filteredOn(d -> d.contains("unsafe-inline"))
                .containsExactly(" style-src-elem 'self' 'unsafe-inline'");
        page.then()
                .header("X-Frame-Options", "DENY")
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer");
        given().header("Authorization", "Bearer " + token.value())
                .get("/api/missions")
                .then()
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", nullValue());
        // A Host the policy cannot hold is left out of it, never copied in.
        assertThat(dev.floor42.server.api.SecurityHeaders.policy("127.0.0.1:7420; script-src *"))
                .doesNotContain("script-src *");
        assertThat(dev.floor42.server.api.SecurityHeaders.policy("[::1]:7420")).contains("ws://[::1]:7420");
    }

    /** The isolation headers (ZAP 10063, 90004): on the page, the API and the refusals alike; COEP is left out on purpose. */
    @Test
    void everyAnswerIsolatesTheOriginAndDeniesTheDeviceFeatures() {
        var api = given().header("Authorization", "Bearer " + token.value()).get("/api/missions");
        for (var r : java.util.List.of(given().get("/some/page"), api, given().get("/api/missions"))) {
            r.then()
                    .header("Cross-Origin-Opener-Policy", "same-origin")
                    .header("Cross-Origin-Resource-Policy", "same-origin");
            assertThat(r.header("Permissions-Policy"))
                    .contains("camera=()", "microphone=()", "geolocation=()", "usb=()", "payment=()")
                    // What the UI really uses: the copy and paste buttons and the terminal's paste.
                    .contains("clipboard-read=(self)", "clipboard-write=(self)");
            assertThat(r.header("Cross-Origin-Embedder-Policy")).isNull();
        }
    }

    /**
     * The API's documentation describes every route and Swagger UI can call them: like the API, it needs the director
     * outside dev mode. The top bar's Docs link opens it in the signed-in browser, which sends its cookie.
     */
    @Test
    void theApiDocumentationNeedsTheDirector() {
        for (String page : java.util.List.of("/q/openapi", "/q/swagger-ui/index.html", "/q/swagger-ui", "/q")) {
            given().redirects().follow(false).get(page).then().statusCode(401).body("code", equalTo("UNAUTHORIZED"));
        }
        given().cookie(LocalAccessGuard.COOKIE, cookie())
                .get("/q/swagger-ui/index.html")
                .then()
                .statusCode(200);
        // Swagger UI fetches the contract from the same page: the browser's cookie goes along.
        given().cookie(LocalAccessGuard.COOKIE, cookie())
                .get("/q/openapi?format=json")
                .then()
                .statusCode(200)
                .body("info.title", equalTo("Floor42 API"));
        given().header("Authorization", "Bearer " + token.value())
                .get("/q/openapi")
                .then()
                .statusCode(200);
        // A read-only script token reads the documentation too.
        String read = sessions.issueScript(SessionRegistry.Scope.READ, 1, "docs", "test")
                .secret();
        given().header("Authorization", "Bearer " + read)
                .get("/q/openapi")
                .then()
                .statusCode(200);
    }

    @Test
    void swaggerUiTryItOutSendsTheHeader() {
        String page = given().cookie(LocalAccessGuard.COOKIE, cookie())
                .get("/q/swagger-ui/index.html")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(page).contains("X-Floor42-Request");
    }

    @Test
    void scopesAreParsedAsTheyAreWritten() {
        assertThat(LocalAccessGuard.allowedByScope(SessionRegistry.Scope.READ, "/api/missions", true))
                .isFalse();
        assertThat(LocalAccessGuard.allowedByScope(
                        SessionRegistry.Scope.MISSIONS_WRITE, "/api/missions/M-1/approve", true))
                .isTrue();
        assertThat(LocalAccessGuard.allowedByScope(
                        SessionRegistry.Scope.MISSIONS_WRITE, "/api/missions/M-1/approve/x", true))
                .isFalse();
        assertThat(LocalAccessGuard.allowedByScope(
                        SessionRegistry.Scope.MISSIONS_WRITE, "/api/missions/M-1/files", true))
                .isFalse();
        assertThat(LocalAccessGuard.allowedByScope(
                        SessionRegistry.Scope.MISSIONS_WRITE, "/api/missions/M-1/workshop/chris/directive", true))
                .isFalse();
        assertThat(LocalAccessGuard.allowedByScope(SessionRegistry.Scope.MISSIONS_WRITE, "/api/missionsX", true))
                .isFalse();
        assertThat(LocalAccessGuard.allowedByScope(SessionRegistry.Scope.MISSIONS_WRITE, "/api/auth/tokens", true))
                .isFalse();
        assertThat(LocalAccessGuard.allowedByScope(SessionRegistry.Scope.ALL, "/api/auth/tokens", true))
                .isTrue();
    }
}
