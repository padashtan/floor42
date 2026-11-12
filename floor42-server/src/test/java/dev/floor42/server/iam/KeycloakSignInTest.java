package dev.floor42.server.iam;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.server.security.LocalAccessGuard;
import dev.floor42.server.security.SessionRegistry;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The director's sign-in through a fake Keycloak: PKCE, single-use state, nonce, and the console link as break-glass. */
@QuarkusTest
@TestProfile(KeycloakSignInTest.IamOn.class)
class KeycloakSignInTest {

    static volatile FakeKeycloak fake;

    /** Starts the fake issuer and points IAM mode at it. */
    public static class FakeIssuer implements QuarkusTestResourceLifecycleManager {
        @Override
        public Map<String, String> start() {
            fake = FakeKeycloak.start("floor42");
            fake.registerClient("floor42-desk", null);
            return Map.of("floor42.iam.issuer", fake.issuer().toString());
        }

        @Override
        public void stop() {
            fake.close();
        }
    }

    public static class IamOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floor42.iam.enabled", "true");
        }

        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(FakeIssuer.class));
        }
    }

    private static final RestAssuredConfig NO_REDIRECT =
            RestAssuredConfig.config().redirect(RedirectConfig.redirectConfig().followRedirects(false));
    private static final HttpClient BROWSER = HttpClient.newHttpClient();

    @Inject
    SessionRegistry sessions;

    @Inject
    SessionToken token;

    @Inject
    dev.floor42.server.journal.Journal journal;

    @Inject
    DirectorTokens directorTokens;

    @Inject
    KeycloakSignIn signIn;

    /** The pre-sign-in cookies the browser of the last {@link #approvedCallback} holds. */
    private Map<String, String> pre = Map.of();

    @AfterEach
    void rightNonce() {
        signIn.clearPending();
        fake.idTokenNonce(null);
    }

    /** Starts a sign-in, lets the fake Keycloak approve it, and gives the callback path with its code and state. */
    private String approvedCallback() throws Exception {
        Response begun = given().config(NO_REDIRECT).get("/auth/keycloak");
        begun.then().statusCode(302);
        pre = begun.getCookies();
        String location = begun.header("Location");
        assertThat(location)
                .startsWith(fake.issuer() + "/protocol/openid-connect/auth?")
                .contains("response_type=code", "client_id=floor42-desk", "code_challenge_method=S256", "scope=openid");
        HttpResponse<String> back = BROWSER.send(
                HttpRequest.newBuilder(URI.create(location)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(back.statusCode()).isEqualTo(302);
        URI callback = URI.create(back.headers().firstValue("Location").orElseThrow());
        assertThat(callback.getPath()).isEqualTo("/auth/callback");
        return callback.getPath() + "?" + callback.getRawQuery();
    }

    @Test
    void pkceRoundTripOpensABrowserSessionWithTokens() throws Exception {
        String cb = approvedCallback();
        Response done = given().config(NO_REDIRECT).cookies(pre).get(cb);
        done.then().statusCode(303).header("Location", "/");
        var cookie = done.getDetailedCookie(LocalAccessGuard.COOKIE);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualToIgnoringCase("Strict");
        var session = sessions.authenticate(cookie.getValue()).orElseThrow();
        assertThat(signIn.lastOnEventLoop)
                .as("sign-in handlers block (Keycloak, vault): off the event loop")
                .isFalse();
        assertThat(session.breakGlass()).isFalse();
        assertThat(directorTokens.live(session.id())).isPresent();
        assertThat(directorTokens.loginEndsAt(session.id())).isPresent();
        given().cookie(LocalAccessGuard.COOKIE, cookie.getValue())
                .get("/api/auth/sessions")
                .then()
                .body("find { it.current }.keycloakUntil", org.hamcrest.Matchers.notNullValue());
        assertThat(directorTokens.any()).isPresent();
        given().cookie(LocalAccessGuard.COOKIE, cookie.getValue())
                .get("/api/agents")
                .then()
                .statusCode(200);
        directorTokens.remove(session.id());
        assertThat(directorTokens.live(session.id())).isEmpty();
    }

    @Test
    void replayedStateIsRefused() throws Exception {
        String callback = approvedCallback();
        given().config(NO_REDIRECT).cookies(pre).get(callback).then().statusCode(303);
        given().config(NO_REDIRECT)
                .cookies(pre)
                .get(callback)
                .then()
                .statusCode(401)
                .body("code", equalTo("SIGN_IN_STATE_UNKNOWN"));
    }

    @Test
    void anUnknownStateIsRefused() {
        given().get("/auth/callback?code=abc&state=nope")
                .then()
                .statusCode(401)
                .body("code", equalTo("SIGN_IN_STATE_UNKNOWN"));
        given().get("/auth/callback").then().statusCode(401).body("code", equalTo("SIGN_IN_STATE_UNKNOWN"));
    }

    @Test
    void idTokenWithWrongNonceIsRefused() throws Exception {
        fake.idTokenNonce("another-nonce");
        String cb = approvedCallback();
        Response done = given().config(NO_REDIRECT).cookies(pre).get(cb);
        done.then().statusCode(401).body("code", equalTo("SIGN_IN_FAILED"));
        assertThat(done.getDetailedCookie(LocalAccessGuard.COOKIE)).isNull();
    }

    @Test
    void keycloakSayingNoIsRefused() throws Exception {
        String callback = approvedCallback();
        given().cookies(pre)
                .get(callback.replaceAll("code=[^&]*", "code=forged"))
                .then()
                .statusCode(401)
                .body("code", equalTo("SIGN_IN_FAILED"));
    }

    @Test
    void aFloodPushesTheOldestOutButNeverRefusesTheDirector() throws Exception {
        String oldest = approvedCallback();
        Map<String, String> oldestCookies = pre;
        for (int i = 0; i < KeycloakSignIn.MAX_PENDING; i++) {
            given().config(NO_REDIRECT).get("/auth/keycloak").then().statusCode(302);
        }
        given().config(NO_REDIRECT)
                .cookies(oldestCookies)
                .get(oldest)
                .then()
                .statusCode(401)
                .body("code", equalTo("SIGN_IN_STATE_UNKNOWN"));
        // The director can still sign in.
        String cb = approvedCallback();
        given().config(NO_REDIRECT).cookies(pre).get(cb).then().statusCode(303);
    }

    @Test
    void aNewStartOfTheSameBrowserReplacesItsOwnPendingLogin() throws Exception {
        String first = approvedCallback();
        Map<String, String> firstCookies = pre;
        given().config(NO_REDIRECT)
                .cookies(firstCookies)
                .get("/auth/keycloak")
                .then()
                .statusCode(302);
        given().config(NO_REDIRECT)
                .cookies(firstCookies)
                .get(first)
                .then()
                .statusCode(401)
                .body("code", equalTo("SIGN_IN_STATE_UNKNOWN"));
    }

    @Test
    void revokingASessionEndsItsKeycloakSessionToo() throws Exception {
        String cb = approvedCallback();
        String cookie = given().config(NO_REDIRECT)
                .cookies(pre)
                .get(cb)
                .getDetailedCookie(LocalAccessGuard.COOKIE)
                .getValue();
        var session = sessions.authenticate(cookie).orElseThrow();
        long before = fake.requests().stream()
                .filter(r -> r.get("_path").endsWith("/logout"))
                .count();
        given().header("Authorization", "Bearer " + token.value())
                .delete("/api/auth/sessions/" + session.id())
                .then()
                .statusCode(204);
        assertThat(directorTokens.live(session.id())).isEmpty();
        assertThat(fake.requests().stream()
                        .filter(r -> r.get("_path").endsWith("/logout"))
                        .count())
                .isEqualTo(before + 1);
    }

    @Test
    void aSignInStartsOnlyAsATopLevelNavigation() {
        for (String[] h : new String[][] {
            {"Sec-Fetch-Site", "cross-site"}, {"Sec-Fetch-Mode", "no-cors"}, {"Sec-Fetch-Dest", "image"}
        }) {
            given().config(NO_REDIRECT)
                    .header(h[0], h[1])
                    .get("/auth/keycloak")
                    .then()
                    .statusCode(403)
                    .body("code", equalTo("NOT_A_NAVIGATION"));
        }
        given().config(NO_REDIRECT)
                .headers("Sec-Fetch-Site", "same-origin", "Sec-Fetch-Mode", "navigate", "Sec-Fetch-Dest", "document")
                .get("/auth/keycloak")
                .then()
                .statusCode(302);
    }

    @Test
    void theRedirectUriUsesTheRealPortWhateverTheHostHeaderSays() {
        Response begun =
                given().header("Host", "localhost:9999").config(NO_REDIRECT).get("/auth/keycloak");
        begun.then().statusCode(302);
        String expected = java.net.URLEncoder.encode(
                "http://localhost:" + io.restassured.RestAssured.port + "/auth/callback",
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(begun.header("Location")).contains("redirect_uri=" + expected);
    }

    @Test
    void aCallbackWithoutTheBrowsersPreSignInCookieIsRefused() throws Exception {
        String callback = approvedCallback();
        Map<String, String> good = pre;
        assertThat(good).hasSize(1);
        given().config(NO_REDIRECT)
                .get(callback)
                .then()
                .statusCode(401)
                .body("code", equalTo("SIGN_IN_BROWSER_UNKNOWN"));
        // The state is spent by the refusal, so the right cookie cannot rescue it afterwards.
        given().config(NO_REDIRECT)
                .cookies(good)
                .get(callback)
                .then()
                .statusCode(401)
                .body("code", equalTo("SIGN_IN_STATE_UNKNOWN"));
    }

    @Test
    void aCallbackWithAWrongPreSignInCookieIsRefused() throws Exception {
        String callback = approvedCallback();
        String name = pre.keySet().iterator().next();
        given().config(NO_REDIRECT)
                .cookie(name, "not-the-secret")
                .get(callback)
                .then()
                .statusCode(401)
                .body("code", equalTo("SIGN_IN_BROWSER_UNKNOWN"));
    }

    @Test
    void signingOutEndsTheKeycloakSessionAndNeverBlocksWhenItFails() throws Exception {
        for (int failing : new int[] {200, 500}) {
            fake.logoutStatus(failing);
            int before = (int) fake.requests().stream()
                    .filter(r -> r.get("_path").endsWith("/logout"))
                    .count();
            String cb = approvedCallback();
            Response done = given().config(NO_REDIRECT).cookies(pre).get(cb);
            String cookie = done.getDetailedCookie(LocalAccessGuard.COOKIE).getValue();
            var session = sessions.authenticate(cookie).orElseThrow();
            assertThat(directorTokens.live(session.id())).isPresent();
            given().cookie(LocalAccessGuard.COOKIE, cookie)
                    .header(LocalAccessGuard.REQUEST_HEADER, "1")
                    .header("Origin", "http://127.0.0.1:" + io.restassured.RestAssured.port)
                    .post("/api/auth/sign-out")
                    .then()
                    .statusCode(204);
            assertThat(sessions.authenticate(cookie)).isEmpty();
            assertThat(directorTokens.live(session.id())).isEmpty();
            assertThat(fake.requests().stream()
                            .filter(r -> r.get("_path").endsWith("/logout"))
                            .count())
                    .isEqualTo(before + 1);
        }
        fake.logoutStatus(200);
    }

    @Test
    void redirectUriIsLoopbackOnly() {
        given().header("Host", "evil.test")
                .config(NO_REDIRECT)
                .get("/auth/keycloak")
                .then()
                .statusCode(403);
        given().header("Host", "evil.test:7420")
                .get("/auth/callback?code=x&state=y")
                .then()
                .statusCode(403);
    }

    @Test
    void aConsoleLinkSignInWithIamOnJournalsOneBreakGlassEventWithNoSecret() {
        long before = breakGlassEvents().size();
        Response r = given().config(NO_REDIRECT)
                .header("Authorization", "Bearer " + token.value())
                .post("/auth");
        r.then().statusCode(303);
        String cookie = r.getDetailedCookie(LocalAccessGuard.COOKIE).getValue();
        var events = breakGlassEvents();
        assertThat(events).hasSize((int) before + 1);
        var last = events.get(events.size() - 1);
        assertThat(last.origin()).isEqualTo(dev.floor42.domain.Origin.REAL);
        assertThat(last.event())
                .isEqualTo(new dev.floor42.domain.event.PreferenceEvent.BreakGlassSignIn(
                        sessions.authenticate(cookie).orElseThrow().id()));
        assertThat(last.toString()).doesNotContain(token.value()).doesNotContain(cookie);
    }

    private List<dev.floor42.domain.event.EventEnvelope> breakGlassEvents() {
        return journal.readAll().stream()
                .filter(e -> e.event() instanceof dev.floor42.domain.event.PreferenceEvent.BreakGlassSignIn)
                .toList();
    }

    @Test
    void breakGlassSessionHasNoDirectorTokens() {
        Response r = given().config(NO_REDIRECT)
                .header("Authorization", "Bearer " + token.value())
                .post("/auth");
        r.then().statusCode(303);
        String cookie = r.getDetailedCookie(LocalAccessGuard.COOKIE).getValue();
        var session = sessions.authenticate(cookie).orElseThrow();
        assertThat(session.breakGlass()).isTrue();
        assertThat(directorTokens.live(session.id())).isEmpty();
        given().cookie(LocalAccessGuard.COOKIE, cookie)
                .get("/api/auth/sessions")
                .then()
                .statusCode(200)
                .body("find { it.current }.breakGlass", equalTo(true))
                .body("find { it.current }.keycloakUntil", org.hamcrest.Matchers.nullValue());
    }
}
