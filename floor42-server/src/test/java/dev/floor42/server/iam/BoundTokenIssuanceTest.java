package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.security.LocalAccessGuard;
import dev.floor42.server.security.SessionRegistry;
import dev.floor42.server.security.SessionRegistry.Session;
import io.vertx.core.Handler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The step-up behind a script token bound to Keycloak (ADR-0083): a pending request single use, 5 minutes, bound to the
 * browser session and to the director, PKCE and nonce, a fresh password proof of the same user; the token is shown once
 * in the page that answers. Real Keycloak client and token checks against a fake Keycloak.
 */
class BoundTokenIssuanceTest {

    private static final BoundTokenIssuer.Request ASK = new BoundTokenIssuer.Request("missions:write", 7, "night");

    @TempDir
    Path dir;

    FakeKeycloak kc;
    BoundTokenIssuance issuance;
    SessionRegistry registry;
    Session session;
    String secret;
    boolean iamOn = true;
    RuntimeException issueFails;
    final List<String> issued = new ArrayList<>();
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
    Handler<RoutingContext> route;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        kc = FakeKeycloak.start("floor42");
        kc.registerClient("floor42-desk", null);
        ObjectMapper json = new ObjectMapper();
        Clock clock = new Clock() {
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(java.time.ZoneId z) {
                return this;
            }

            public Instant instant() {
                return now.get();
            }
        };
        SecretVault vault = new SecretVault(dir);
        KeycloakClient keycloak = new KeycloakClient(kc.issuer().toString(), vault, json);
        registry = new SessionRegistry();
        for (String f : new String[] {"idleHours", "absoluteHours"}) {
            var field = SessionRegistry.class.getDeclaredField(f);
            field.setAccessible(true);
            field.setLong(registry, 24);
        }
        DirectorTokens directorTokens = new DirectorTokens();
        directorTokens.sessions = registry;
        directorTokens.keycloak = keycloak;
        IamSettings settings =
                new IamSettings(dir, Optional.of(true), Optional.of(kc.issuer().toString()), Optional.empty(), json) {
                    @Override
                    public boolean enabled() {
                        return iamOn;
                    }
                };
        StepUpApprovals stepUp = new StepUpApprovals();
        stepUp.sessions = registry;
        stepUp.directorTokens = directorTokens;
        issuance = new BoundTokenIssuance();
        issuance.settings = settings;
        issuance.keycloak = keycloak;
        issuance.jwks =
                new Jwks(URI.create(kc.issuer() + "/protocol/openid-connect/certs"), HttpClient.newHttpClient(), clock);
        issuance.sessions = registry;
        issuance.directorTokens = directorTokens;
        issuance.stepUp = stepUp;
        issuance.json = json;
        issuance.clock = clock;
        issuance.issuer = (request, subject, offline, proof, userAgent) -> {
            if (issueFails != null) throw issueFails;
            assertThat(offline.refreshToken()).isNotBlank();
            assertThat(proof.acr()).isEqualTo("floor42-strong");
            issued.add(request.scope() + "/" + request.days() + "/" + request.label() + "/" + subject);
            return new BoundTokenIssuer.Issued("S-1", "SECRET<&>value", request.scope(), "2026-11-23T00:00:00Z");
        };
        var routeProxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {io.vertx.ext.web.Route.class}, (p, m, a) -> {
                    if (m.getName().equals("blockingHandler")) {
                        @SuppressWarnings("unchecked")
                        Handler<RoutingContext> h = (Handler<RoutingContext>) a[0];
                        route = h;
                    }
                    return p;
                });
        Router router = (Router) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Router.class}, (p, m, a) -> routeProxy);
        issuance.register(router);
        SessionRegistry.Issued opened = registry.openBrowser("test-agent");
        session = opened.session();
        secret = opened.secret();
        directorTokens.put(
                session.id(),
                new Tokens(
                        kc.directorToken("director"), "refresh", null, now.get().plusSeconds(3600)));
    }

    @AfterEach
    void stop() {
        kc.close();
    }

    private FakeRc begin(String cookieSecret) {
        FakeRc rc = new FakeRc().header("Host", "127.0.0.1:7420");
        if (cookieSecret != null) rc.withCookie(LocalAccessGuard.COOKIE, cookieSecret);
        route.handle(rc.rc);
        return rc;
    }

    private static Map<String, String> query(String url) {
        Map<String, String> out = new HashMap<>();
        for (String kv : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] p = kv.split("=", 2);
            out.put(p[0], URLDecoder.decode(p.length > 1 ? p[1] : "", StandardCharsets.UTF_8));
        }
        return out;
    }

    private FakeRc callback(FakeRc started, Map<String, String> extra) throws Exception {
        String location = started.sentHeaders.get("location");
        HttpResponse<String> r =
                http.send(HttpRequest.newBuilder(URI.create(location)).build(), HttpResponse.BodyHandlers.ofString());
        Map<String, String> back = query(r.headers().firstValue("Location").orElseThrow());
        String state = query(location).get("state");
        FakeRc rc = new FakeRc().param("state", state).param("code", back.get("code"));
        String name = "floor42_pre_" + state.substring(0, 8).replaceAll("[^A-Za-z0-9_-]", "_");
        String pre = started.given(name);
        if (pre != null) rc.withCookie(name, pre);
        extra.forEach(rc::param);
        return rc;
    }

    private FakeRc complete() throws Exception {
        issuance.record(ASK, session);
        FakeRc cb = callback(begin(secret), Map.of());
        assertThat(issuance.callback(cb.rc)).isTrue();
        return cb;
    }

    private boolean revokeSeen() {
        return kc.requests().stream().anyMatch(r -> r.get("_path").endsWith("/revoke"));
    }

    @Test
    void onlyABrowserSessionWithLiveKeycloakTokensMayAsk() {
        assertThat(issuance.mayAsk(Optional.of(session))).isTrue();
        assertThat(issuance.mayAsk(Optional.empty())).isFalse();
        Session script =
                registry.issueScript(SessionRegistry.Scope.ALL, 1, "s", "ua").session();
        assertThat(issuance.mayAsk(Optional.of(script))).isFalse();
        assertThat(issuance.mayAsk(Optional.of(registry.openBreakGlass("ua").session())))
                .isFalse();
    }

    @Test
    void theRedirectAsksForThePasswordAgainWithPkceAndAnOfflineToken() {
        assertThat(issuance.record(ASK, session)).isEqualTo("/auth/keycloak/bind");
        FakeRc rc = begin(secret);
        assertThat(rc.status).isEqualTo(302);
        Map<String, String> q = query(rc.sentHeaders.get("location"));
        assertThat(q)
                .containsEntry("client_id", "floor42-desk")
                .containsEntry("scope", "openid offline_access")
                .containsEntry("prompt", "login")
                .containsEntry("max_age", "0")
                .containsEntry("acr_values", "floor42-strong")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("redirect_uri", "http://127.0.0.1:7420/auth/callback");
        assertThat(q.get("nonce")).hasSizeGreaterThan(30);
    }

    @Test
    void noRequestNoCookieOrAnotherBrowserIsUnknown() {
        assertThat(begin(secret).status).isEqualTo(401);
        issuance.record(ASK, session);
        assertThat(begin(null).status).isEqualTo(401);
        assertThat(begin("not-a-session-secret").status).isEqualTo(401);
        FakeRc other = begin(registry.openBrowser("other").secret());
        assertThat(other.status).isEqualTo(401);
        assertThat(other.sentHeaders).doesNotContainKey("location");
    }

    @Test
    void withIamOffThereIsNothingToBind() {
        issuance.record(ASK, session);
        iamOn = false;
        assertThat(begin(secret).code()).isEqualTo("IAM_OFF");
    }

    @Test
    void aTypedPasswordOfTheSameDirectorIssuesOnceAndTheTokenIsShownOnce() throws Exception {
        FakeRc cb = complete();
        assertThat(cb.status).isEqualTo(200);
        assertThat(cb.sentHeaders.get("cache-control")).isEqualTo("no-store");
        assertThat(cb.body).contains("SECRET&lt;&amp;&gt;value").doesNotContain("SECRET<&>value");
        assertThat(issued).hasSize(1).first().asString().startsWith("missions:write/7/night/");
        // Single use: nothing waits any more, and the callback is not ours a second time.
        assertThat(begin(secret).status).isEqualTo(401);
        assertThat(issuance.callback(cb.rc)).isFalse();
        assertThat(issued).hasSize(1);
    }

    @Test
    void aKeycloakThatIgnoredPromptLoginIsNotAStepUpAndItsSessionIsEnded() throws Exception {
        issuance.record(ASK, session);
        FakeRc started = begin(secret);
        kc.ignorePromptLogin(true);
        now.set(now.get().plusSeconds(10));
        FakeRc cb = callback(started, Map.of());
        assertThat(issuance.callback(cb.rc)).isTrue();
        assertThat(cb.status).isEqualTo(401);
        assertThat(cb.code()).isEqualTo("BOUND_TOKEN_STEP_UP_MISSING");
        assertThat(issued).isEmpty();
        assertThat(revokeSeen()).isTrue();
    }

    @Test
    void thePasswordOfAnotherUserIssuesNothing() throws Exception {
        issuance.record(ASK, session);
        kc.signInAs("mallory");
        FakeRc cb = callback(begin(secret), Map.of());
        assertThat(issuance.callback(cb.rc)).isTrue();
        assertThat(cb.code()).isEqualTo("BOUND_TOKEN_WRONG_USER");
        assertThat(issued).isEmpty();
        assertThat(revokeSeen()).isTrue();
    }

    @Test
    void aTokenThatCannotBeIssuedEndsTheKeycloakSessionAndSaysSo() throws Exception {
        issueFails = new IllegalStateException("journal refused");
        FakeRc cb = complete();
        assertThat(cb.status).isEqualTo(409);
        assertThat(cb.code()).isEqualTo("BOUND_TOKEN_FAILED");
        assertThat(cb.body).doesNotContain("journal refused");
        assertThat(revokeSeen()).isTrue();
    }

    @Test
    void anErrorFromKeycloakOrAnOldRequestIssuesNothing() throws Exception {
        issuance.record(ASK, session);
        FakeRc denied = callback(begin(secret), Map.of("error", "access_denied"));
        assertThat(issuance.callback(denied.rc)).isTrue();
        assertThat(denied.code()).isEqualTo("SIGN_IN_FAILED");
        assertThat(begin(secret).status).isEqualTo(401);

        issuance.record(ASK, session);
        now.set(now.get().plus(BoundTokenIssuance.TTL).plusSeconds(1));
        assertThat(begin(secret).status).isEqualTo(401);
        assertThat(issued).isEmpty();
    }

    @Test
    void aSignInThatDidNotReachTheStrongLevelIssuesNothing() throws Exception {
        issuance.record(ASK, session);
        kc.idTokenAcr("floor42-basic");
        FakeRc cb = callback(begin(secret), Map.of());
        assertThat(issuance.callback(cb.rc)).isTrue();
        assertThat(cb.code()).isEqualTo("ASSURANCE_TOO_LOW");
        assertThat(issued).isEmpty();
        assertThat(revokeSeen()).isTrue();
    }

    @Test
    void aSecondRequestReplacesTheFirstSoTheFirstFlowCannotIssue() throws Exception {
        issuance.record(ASK, session);
        FakeRc cb = callback(begin(secret), Map.of());
        issuance.record(new BoundTokenIssuer.Request("all", 30, null), session);
        assertThat(issuance.callback(cb.rc)).isTrue();
        assertThat(cb.code()).isEqualTo("BOUND_TOKEN_UNKNOWN");
        assertThat(issued).isEmpty();
    }
}
