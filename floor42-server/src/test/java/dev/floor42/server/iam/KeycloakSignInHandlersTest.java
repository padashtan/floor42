package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.TicketId;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.security.ConsoleSignIn;
import dev.floor42.server.security.SessionRegistry;
import io.vertx.core.Handler;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Proxy;
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
import java.util.Collection;
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
 * The director's sign-in through Keycloak driven handler by handler: PKCE (S256), single-use state and nonce, the browser
 * binding cookie, bounded and expiring pending logins, and the console link as break-glass.
 */
class KeycloakSignInHandlersTest {

    @TempDir
    Path dir;

    FakeKeycloak kc;
    KeycloakSignIn signIn;
    SessionRegistry registry;
    DirectorTokens directorTokens;
    boolean iamOn = true;
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
    final Map<String, Handler<RoutingContext>> routes = new HashMap<>();
    final List<String> breakGlass = new ArrayList<>();
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
        KeycloakClient keycloak = new KeycloakClient(kc.issuer().toString(), new SecretVault(dir), json);
        registry = new SessionRegistry();
        for (String f : new String[] {"idleHours", "absoluteHours"}) {
            var field = SessionRegistry.class.getDeclaredField(f);
            field.setAccessible(true);
            field.setLong(registry, 24);
        }
        directorTokens = new DirectorTokens();
        directorTokens.sessions = registry;
        directorTokens.keycloak = keycloak;
        signIn = new KeycloakSignIn();
        signIn.settings =
                new IamSettings(dir, Optional.of(true), Optional.of(kc.issuer().toString()), Optional.empty(), json) {
                    @Override
                    public boolean enabled() {
                        return iamOn;
                    }
                };
        signIn.keycloak = keycloak;
        signIn.jwks =
                new Jwks(URI.create(kc.issuer() + "/protocol/openid-connect/certs"), HttpClient.newHttpClient(), clock);
        signIn.sessions = registry;
        signIn.directorTokens = directorTokens;
        signIn.json = json;
        signIn.stepUp = new StepUpApprovals();
        signIn.clock = clock;
        signIn.office = new OfficeFacts() {
            public Collection<AgentId> activeStaff() {
                return List.of();
            }

            public Optional<MissionStatus> missionStatus(MissionId mission) {
                return Optional.empty();
            }

            public boolean isRetroTicket(MissionId mission, TicketId ticket) {
                return false;
            }

            public void breakGlassSignIn(String session) {
                breakGlass.add(session);
            }
        };
        Object[] path = new Object[1];
        Route route =
                (Route) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Route.class}, (p, m, a) -> {
                    if (m.getName().equals("handler") || m.getName().equals("blockingHandler")) {
                        @SuppressWarnings("unchecked")
                        Handler<RoutingContext> h = (Handler<RoutingContext>) a[0];
                        routes.put((String) path[0], h);
                    }
                    return p;
                });
        signIn.register((Router)
                Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Router.class}, (p, m, a) -> {
                    path[0] = a[0];
                    return route;
                }));
    }

    @AfterEach
    void stop() {
        kc.close();
    }

    private FakeRc run(String route, FakeRc rc) {
        routes.get(route).handle(rc.rc);
        return rc;
    }

    private FakeRc begin(Cookie... held) {
        FakeRc rc = new FakeRc().header("Host", "127.0.0.1:7420");
        for (Cookie c : held) rc.withCookie(c.getName(), c.getValue());
        return run("/auth/keycloak", rc);
    }

    private static Map<String, String> query(String url) {
        Map<String, String> out = new HashMap<>();
        for (String kv : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] p = kv.split("=", 2);
            out.put(p[0], URLDecoder.decode(p.length > 1 ? p[1] : "", StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String stateOf(FakeRc begun) {
        return query(begun.sentHeaders.get("location")).get("state");
    }

    /** What Keycloak sends the browser back with after the login: the callback request, with the browser's cookies. */
    private FakeRc comeBack(FakeRc begun) throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(begun.sentHeaders.get("location")))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        Map<String, String> back = query(r.headers().firstValue("Location").orElseThrow());
        FakeRc rc = new FakeRc().header("User-Agent", "browser/9");
        rc.param("state", back.get("state")).param("code", back.get("code"));
        begun.sentCookies.forEach(c -> rc.withCookie(c.getName(), c.getValue()));
        return rc;
    }

    private FakeRc callback(FakeRc rc) {
        return run("/auth/callback", rc);
    }

    // ---- enabled, begin

    @Test
    void theEnabledProbeAnswersOnlyWithIamOn() {
        assertThat(run("/auth/keycloak/enabled", new FakeRc()).status).isEqualTo(204);
        iamOn = false;
        FakeRc off = run("/auth/keycloak/enabled", new FakeRc());
        assertThat(off.status).isEqualTo(404);
        assertThat(off.code()).isEqualTo("IAM_OFF");
    }

    @Test
    void theRedirectCarriesPkceAStateANonceAndTheRealLoopbackCallback() {
        FakeRc rc = begin();
        assertThat(rc.status).isEqualTo(302);
        assertThat(rc.sentHeaders.get("cache-control")).isEqualTo("no-store");
        String location = rc.sentHeaders.get("location");
        assertThat(location).startsWith(kc.issuer() + "/protocol/openid-connect/auth?");
        Map<String, String> q = query(location);
        assertThat(q)
                .containsEntry("response_type", "code")
                .containsEntry("client_id", "floor42-desk")
                .containsEntry("scope", "openid")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("redirect_uri", "http://127.0.0.1:7420/auth/callback");
        assertThat(q.get("state")).hasSizeGreaterThan(30);
        assertThat(q.get("nonce")).hasSizeGreaterThan(30).isNotEqualTo(q.get("state"));
        assertThat(q.get("code_challenge")).hasSize(43);
        assertThat(q).doesNotContainKeys("prompt", "max_age");
        assertThat(rc.sentCookies).hasSize(1);
        assertThat(rc.sentCookies.get(0).isHttpOnly()).isTrue();
        assertThat(signIn.lastOnEventLoop).isFalse();
    }

    @Test
    void theCallbackNeverFollowsTheHostHeaderOfTheCaller() {
        FakeRc rc = new FakeRc().header("Host", "evil.example:6666");
        run("/auth/keycloak", rc);
        assertThat(query(rc.sentHeaders.get("location")).get("redirect_uri"))
                .isEqualTo("http://127.0.0.1:7420/auth/callback");
    }

    @Test
    void withIamOffNeitherRouteStartsAnything() {
        iamOn = false;
        FakeRc rc = begin();
        assertThat(rc.status).isEqualTo(404);
        assertThat(rc.code()).isEqualTo("IAM_OFF");
        assertThat(rc.sentCookies).isEmpty();
        FakeRc cb = callback(new FakeRc().param("state", "x"));
        assertThat(cb.status).isEqualTo(404);
        assertThat(cb.code()).isEqualTo("IAM_OFF");
    }

    @Test
    void aSubresourceOrAFetchOfAnotherSiteCannotStartASignIn() {
        for (var h : List.of(
                Map.entry("Sec-Fetch-Site", "cross-site"),
                Map.entry("Sec-Fetch-Mode", "no-cors"),
                Map.entry("Sec-Fetch-Dest", "image"))) {
            FakeRc rc = new FakeRc().header("Host", "127.0.0.1:7420").header(h.getKey(), h.getValue());
            run("/auth/keycloak", rc);
            assertThat(rc.status).isEqualTo(403);
            assertThat(rc.code()).isEqualTo("NOT_A_NAVIGATION");
            assertThat(rc.sentCookies).isEmpty();
        }
        FakeRc nav = new FakeRc()
                .header("Host", "127.0.0.1:7420")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Dest", "document");
        run("/auth/keycloak", nav);
        assertThat(nav.status).isEqualTo(302);
    }

    @Test
    void aNewStartOfTheSameBrowserReplacesItsOwnPendingLoginOthersKeepTheirs() throws Exception {
        FakeRc first = begin();
        FakeRc other = begin();
        FakeRc second = begin(first.sentCookies.get(0));
        assertThat(callback(comeBack(first)).code()).isEqualTo("SIGN_IN_STATE_UNKNOWN");
        assertThat(callback(comeBack(other)).status).isEqualTo(303);
        assertThat(callback(comeBack(second)).status).isEqualTo(303);
    }

    @Test
    void aFullTableDropsTheOldestLoginAndKeepsTheOthers() throws Exception {
        List<FakeRc> starts = new ArrayList<>();
        for (int i = 0; i < KeycloakSignIn.MAX_PENDING + 1; i++) starts.add(begin());
        assertThat(callback(comeBack(starts.get(0))).code()).isEqualTo("SIGN_IN_STATE_UNKNOWN");
        assertThat(callback(comeBack(starts.get(1))).status).isEqualTo(303);
        assertThat(callback(comeBack(starts.get(KeycloakSignIn.MAX_PENDING))).status)
                .isEqualTo(303);
    }

    @Test
    void anExpiredLoginMakesRoomAndIsRefused() throws Exception {
        FakeRc old = begin();
        FakeRc oldBack = comeBack(old);
        now.set(now.get().plus(KeycloakSignIn.PENDING_TTL).minusSeconds(1));
        FakeRc fresh = begin();
        FakeRc freshBack = comeBack(fresh);
        now.set(now.get().plusSeconds(2));
        assertThat(callback(oldBack).code()).isEqualTo("SIGN_IN_STATE_UNKNOWN");
        assertThat(callback(freshBack).status).isEqualTo(303);
    }

    @Test
    void aLoginJustInsideItsLifetimeStillWorks() throws Exception {
        FakeRc begun = begin();
        FakeRc back = comeBack(begun);
        now.set(now.get().plus(KeycloakSignIn.PENDING_TTL).minusSeconds(1));
        assertThat(callback(back).status).isEqualTo(303);
    }

    // ---- callback

    @Test
    void aGoodCallbackOpensAnOrdinaryBrowserSessionHoldingTheKeycloakTokens() throws Exception {
        FakeRc cb = callback(comeBack(begin()));
        assertThat(cb.status).isEqualTo(303);
        assertThat(cb.sentHeaders.get("location")).isEqualTo("/");
        Cookie cookie = cb.sentCookies.get(0);
        assertThat(cookie.getName()).isEqualTo("floor42_session");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo(io.vertx.core.http.CookieSameSite.STRICT);
        var session = registry.authenticate(cookie.getValue()).orElseThrow();
        assertThat(session.kind()).isEqualTo(SessionRegistry.Kind.BROWSER);
        assertThat(session.breakGlass()).isFalse();
        assertThat(session.userAgent()).isEqualTo("browser/9");
        Tokens held = directorTokens.live(session.id()).orElseThrow();
        assertThat(held.refreshToken()).isNotNull();
        assertThat(signIn.lastOnEventLoop).isFalse();
    }

    @Test
    void aStateIsUsedOnce() throws Exception {
        FakeRc back = comeBack(begin());
        assertThat(callback(back).status).isEqualTo(303);
        FakeRc replay = callback(back);
        assertThat(replay.status).isEqualTo(401);
        assertThat(replay.code()).isEqualTo("SIGN_IN_STATE_UNKNOWN");
        assertThat(registry.list()).hasSize(1);
    }

    @Test
    void anUnknownMissingOrNearMissStateOpensNothing() throws Exception {
        FakeRc begun = begin();
        FakeRc back = comeBack(begun);
        String state = back.params.get("state");
        for (String bad : new String[] {state + "x", state.substring(1), "unknown"}) {
            FakeRc rc = new FakeRc().param("state", bad).param("code", back.params.get("code"));
            assertThat(callback(rc).code()).isEqualTo("SIGN_IN_STATE_UNKNOWN");
        }
        FakeRc none = callback(new FakeRc().param("code", "c"));
        assertThat(none.status).isEqualTo(401);
        assertThat(none.code()).isEqualTo("SIGN_IN_STATE_UNKNOWN");
        assertThat(registry.list()).isEmpty();
        assertThat(callback(back).status).isEqualTo(303);
    }

    @Test
    void aCallbackFromAnotherBrowserIsRefusedAndConsumesTheState() throws Exception {
        FakeRc back = comeBack(begin());
        back.cookies.clear();
        FakeRc cb = callback(back);
        assertThat(cb.status).isEqualTo(401);
        assertThat(cb.code()).isEqualTo("SIGN_IN_BROWSER_UNKNOWN");
        assertThat(registry.list()).isEmpty();
        assertThat(callback(back).code()).isEqualTo("SIGN_IN_STATE_UNKNOWN");
    }

    @Test
    void aCookieWithAnotherSecretIsNotTheBinding() throws Exception {
        FakeRc back = comeBack(begin());
        back.cookies.replaceAll((k, v) -> v + "x");
        assertThat(callback(back).code()).isEqualTo("SIGN_IN_BROWSER_UNKNOWN");
    }

    @Test
    void anErrorOrNoCodeFromKeycloakIsAFailedSignIn() throws Exception {
        FakeRc denied = comeBack(begin());
        denied.param("error", "access_denied");
        FakeRc a = callback(denied);
        assertThat(a.status).isEqualTo(401);
        assertThat(a.code()).isEqualTo("SIGN_IN_FAILED");
        FakeRc noCode = comeBack(begin());
        noCode.params.remove("code");
        assertThat(callback(noCode).code()).isEqualTo("SIGN_IN_FAILED");
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void anIdTokenForAnotherNonceIsNotASignIn() throws Exception {
        kc.idTokenNonce("someone-elses");
        FakeRc cb = callback(comeBack(begin()));
        assertThat(cb.status).isEqualTo(401);
        assertThat(cb.code()).isEqualTo("SIGN_IN_FAILED");
        assertThat(cb.sentCookies).isEmpty();
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void aCodeKeycloakRefusesOrAKeycloakThatIsDownIsAFailedSignIn() throws Exception {
        FakeRc bad = comeBack(begin());
        bad.param("code", "forged-code");
        assertThat(callback(bad).code()).isEqualTo("SIGN_IN_FAILED");
        FakeRc back = comeBack(begin());
        kc.down(true);
        assertThat(callback(back).code()).isEqualTo("SIGN_IN_FAILED");
        assertThat(registry.list()).isEmpty();
    }

    // ---- break-glass

    @Test
    void theConsoleLinkSignInIsJournaledOnlyWithIamOnAndASession() {
        signIn.onConsoleSignIn(new ConsoleSignIn("B-1"));
        assertThat(breakGlass).containsExactly("B-1");
        signIn.onConsoleSignIn(new ConsoleSignIn(null));
        assertThat(breakGlass).hasSize(1);
        iamOn = false;
        signIn.onConsoleSignIn(new ConsoleSignIn("B-2"));
        assertThat(breakGlass).hasSize(1);
    }
}
