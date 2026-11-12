package dev.floor42.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.iam.FakeRc;
import dev.floor42.server.security.SessionRegistry.Scope;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Event;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The request guard of the daemon (ADR-0034, ADR-0044) driven handler by handler, without a server: host and origin
 * rules, no proxy, hidden routes, credential kinds, scopes, the browser's anti-CSRF header, and the sign-in routes.
 */
class LocalAccessGuardHandlersTest {

    private static final String AGENT = "agent-credential";

    @TempDir
    Path dir;

    LocalAccessGuard guard;
    SessionToken token;
    SessionRegistry registry;
    DirectorSignIn signIn;
    final List<ConsoleSignIn> consoleSignIns = new ArrayList<>();
    final List<Handler<RoutingContext>> handlers = new ArrayList<>();

    @BeforeEach
    void start() {
        token = new SessionToken();
        token.dataDir = dir;
        token.generate();
        registry = new SessionRegistry();
        registry.idleHours = 8;
        registry.absoluteHours = 24;
        signIn = new DirectorSignIn();
        signIn.delivery = "none";
        signIn.ttlSeconds = 60;
        signIn.registry = registry;
        guard = new LocalAccessGuard();
        guard.token = token;
        guard.signIn = signIn;
        guard.sessions = registry;
        guard.agents = AGENT::equals;
        guard.json = new ObjectMapper();
        guard.extraOrigins = Optional.empty();
        guard.consoleSignIns = (Event<ConsoleSignIn>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Event.class}, (p, m, a) -> {
                    if (m.getName().equals("fire")) consoleSignIns.add((ConsoleSignIn) a[0]);
                    return null;
                });
        Object route = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Route.class}, (p, m, a) -> {
            if (m.getName().equals("handler")) {
                @SuppressWarnings("unchecked")
                Handler<RoutingContext> h = (Handler<RoutingContext>) a[0];
                handlers.add(h);
            }
            return p;
        });
        guard.register((Router)
                Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Router.class}, (p, m, a) -> route));
    }

    private FakeRc through(FakeRc rc) {
        handlers.get(0).handle(rc.rc);
        return rc;
    }

    private FakeRc api(HttpMethod method, String path) {
        return FakeRc.request(method, path);
    }

    private FakeRc denied(FakeRc rc, int status, String code) {
        through(rc);
        assertThat(rc.passed).as("passed on to the route").isFalse();
        assertThat(rc.status).isEqualTo(status);
        assertThat(rc.code()).isEqualTo(code);
        assertThat(rc.sentHeaders.get("content-type")).isEqualTo("application/json");
        return rc;
    }

    private FakeRc allowed(FakeRc rc) {
        through(rc);
        assertThat(rc.passed)
                .as("passed on to the route (status %d %s)", rc.status, rc.body)
                .isTrue();
        return rc;
    }

    private String script(Scope scope) {
        return registry.issueScript(scope, 1, "s", "ua").secret();
    }

    // ---- transport

    @Test
    void onlyTheLoopbackNamesAreServed() {
        for (String host : List.of("127.0.0.1:7420", "localhost:7420", "floor42.localhost", "[::1]:7420")) {
            allowed(api(HttpMethod.GET, "/index.html").header("Host", host));
        }
        for (String host :
                List.of("evil.example:7420", "127.0.0.1.evil.example", "localhost.evil.example", "", "LOCALHOST")) {
            denied(api(HttpMethod.GET, "/index.html").header("Host", host), 403, "FORBIDDEN");
        }
        FakeRc none = new FakeRc();
        none.path = "/index.html";
        denied(none, 403, "FORBIDDEN");
    }

    @Test
    void theHttp2AuthorityStandsInForAMissingHost() {
        FakeRc ok = new FakeRc();
        ok.path = "/index.html";
        ok.authority = HostAndPort.create("localhost", 7420);
        allowed(ok);
        FakeRc v6 = new FakeRc();
        v6.path = "/index.html";
        v6.authority = HostAndPort.create("::1", 7420);
        allowed(v6);
        FakeRc bad = new FakeRc();
        bad.path = "/index.html";
        bad.authority = HostAndPort.create("evil.example", 7420);
        denied(bad, 403, "FORBIDDEN");
    }

    @Test
    void aRequestThroughAProxyIsRefusedWhateverTheHeader() {
        for (String h : List.of("X-Forwarded-For", "X-Forwarded-Host", "Forwarded")) {
            FakeRc rc = denied(api(HttpMethod.GET, "/index.html").header(h, "1.2.3.4"), 403, "FORWARDED");
            assertThat(rc.body).contains(h);
        }
    }

    @Test
    void anOriginMustBeTheDaemonsOwnOrListed() {
        allowed(api(HttpMethod.GET, "/index.html").header("Origin", "http://127.0.0.1:7420"));
        allowed(api(HttpMethod.GET, "/index.html").header("Origin", "HTTP://127.0.0.1:7420"));
        denied(api(HttpMethod.GET, "/index.html").header("Origin", "http://evil.example"), 403, "FORBIDDEN");
        denied(api(HttpMethod.GET, "/index.html").header("Origin", "http://localhost:7420"), 403, "FORBIDDEN");
        guard.extraOrigins = Optional.of(List.of(" http://Tunnel.Example:9 "));
        allowed(api(HttpMethod.GET, "/index.html").header("Origin", "http://tunnel.example:9"));
        denied(api(HttpMethod.GET, "/index.html").header("Origin", "http://tunnel.example:8"), 403, "FORBIDDEN");
    }

    @Test
    void aPathHidingARouteIsRefused() {
        for (String p :
                List.of("/api;x/agents", "/api%3Bx/agents", "/api%3bx", "/api%2Fagents", "/api%2fagents", "/a%5Cb")) {
            denied(api(HttpMethod.GET, p), 400, "PATH_REFUSED");
        }
        FakeRc normalizedOnly = api(HttpMethod.GET, "/api/x");
        normalizedOnly.normalizedPath = "/api;y";
        denied(normalizedOnly, 400, "PATH_REFUSED");
        assertThat(LocalAccessGuard.hidesARoute(null)).isFalse();
        assertThat(LocalAccessGuard.hidesARoute("/api/missions/M-1")).isFalse();
    }

    // ---- which paths are guarded

    @Test
    void theApiTheSocketsAndQuarkusPagesNeedTheDirectorTheRestIsServed() {
        allowed(api(HttpMethod.GET, "/"));
        allowed(api(HttpMethod.GET, "/assets/app.js"));
        allowed(api(HttpMethod.GET, "/apiary"));
        allowed(api(HttpMethod.GET, "/quark"));
        allowed(api(HttpMethod.GET, "/api/status"));
        for (String p : List.of("/api", "/api/missions", "/ws/events", "/q", "/q/openapi", "/q/swagger-ui")) {
            denied(api(HttpMethod.GET, p), 401, "UNAUTHORIZED");
        }
        guard.openQuarkusPages = true;
        allowed(api(HttpMethod.GET, "/q/dev-ui"));
        allowed(api(HttpMethod.GET, "/q"));
        denied(api(HttpMethod.GET, "/api"), 401, "UNAUTHORIZED");
    }

    // ---- credentials

    @Test
    void theDirectorTokenIsAFullScopeBearer() {
        FakeRc rc = allowed(
                api(HttpMethod.POST, "/api/missions/M-1/budget").header("Authorization", "Bearer " + token.value()));
        assertThat(rc.context.get("floor42.scope")).isEqualTo(Scope.ALL);
        assertThat(LocalAccessGuard.scopeOf(rc.rc)).isEqualTo(Scope.ALL);
        assertThat(LocalAccessGuard.sessionOf(rc.rc)).isEmpty();
    }

    @Test
    void noCredentialAWrongOneOrOfTheWrongKindIsUnauthorized() {
        denied(api(HttpMethod.GET, "/api/missions"), 401, "UNAUTHORIZED");
        denied(api(HttpMethod.GET, "/api/missions").header("Authorization", "Bearer wrong"), 401, "UNAUTHORIZED");
        denied(api(HttpMethod.GET, "/api/missions").header("Authorization", "Bearer "), 401, "UNAUTHORIZED");
        denied(
                api(HttpMethod.GET, "/api/missions").header("Authorization", "Basic " + token.value()),
                401,
                "UNAUTHORIZED");
        denied(
                api(HttpMethod.GET, "/api/missions").header("Authorization", "bearer " + token.value()),
                401,
                "UNAUTHORIZED");
        String browser = registry.openBrowser("ua").secret();
        denied(api(HttpMethod.GET, "/api/missions").header("Authorization", "Bearer " + browser), 401, "UNAUTHORIZED");
        denied(
                api(HttpMethod.GET, "/api/missions").withCookie(LocalAccessGuard.COOKIE, script(Scope.ALL)),
                401,
                "UNAUTHORIZED");
        denied(api(HttpMethod.GET, "/api/missions").withCookie(LocalAccessGuard.COOKIE, "nope"), 401, "UNAUTHORIZED");
    }

    @Test
    void aBadBearerIsNotRescuedByAGoodCookie() {
        String browser = registry.openBrowser("ua").secret();
        denied(
                api(HttpMethod.GET, "/api/missions")
                        .header("Authorization", "Bearer wrong")
                        .withCookie(LocalAccessGuard.COOKIE, browser),
                401,
                "UNAUTHORIZED");
    }

    @Test
    void anAgentCredentialOpensNoDirectorRoute() {
        FakeRc rc = denied(
                api(HttpMethod.GET, "/api/missions").header("Authorization", "Bearer " + AGENT), 403, "FORBIDDEN");
        assertThat(rc.body).contains("never the API");
    }

    // ---- scopes

    @Test
    void aReadTokenReadsAndNeverChanges() {
        String t = script(Scope.READ);
        for (HttpMethod m : List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS)) {
            FakeRc rc = allowed(api(m, "/api/missions").header("Authorization", "Bearer " + t));
            assertThat(LocalAccessGuard.scopeOf(rc.rc)).isEqualTo(Scope.READ);
            assertThat(LocalAccessGuard.sessionOf(rc.rc)).isPresent();
        }
        for (HttpMethod m : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH)) {
            FakeRc rc = denied(api(m, "/api/missions").header("Authorization", "Bearer " + t), 403, "SCOPE");
            assertThat(rc.body).contains("read");
        }
        denied(api(HttpMethod.GET, "/ws/terminal/1").header("Authorization", "Bearer " + t), 403, "SCOPE");
        allowed(api(HttpMethod.GET, "/ws/events").header("Authorization", "Bearer " + t));
    }

    @Test
    void aMissionsWriteTokenMayOnlySteerMissions() {
        String t = script(Scope.MISSIONS_WRITE);
        for (String p : List.of(
                "/api/missions",
                "/api/missions/M-1/approve",
                "/api/missions/M-1/reject",
                "/api/missions/M-1/nudge",
                "/api/missions/M-1/tickets/approve",
                "/api/missions/M-1/workshop/directive",
                "/api/questions/Q-1/answer")) {
            allowed(api(HttpMethod.POST, p).header("Authorization", "Bearer " + t));
        }
        for (String p : List.of(
                "/api/missions/M-1/budget",
                "/api/missions/M-1/approve/x",
                "/api/missions/approve",
                "/api/missionsx",
                "/api/missions/M-1/other/approve",
                "/api/questions/Q-1/answer/x",
                "/api/permissions",
                "/api/sessions")) {
            denied(api(HttpMethod.POST, p).header("Authorization", "Bearer " + t), 403, "SCOPE");
        }
        denied(api(HttpMethod.GET, "/ws/terminal/1").header("Authorization", "Bearer " + t), 403, "SCOPE");
        allowed(api(HttpMethod.GET, "/api/anything").header("Authorization", "Bearer " + t));
    }

    @Test
    void scopesAreCheckedByTheStaticRule() {
        assertThat(LocalAccessGuard.allowedByScope(Scope.ALL, "/api/x", true)).isTrue();
        assertThat(LocalAccessGuard.allowedByScope(Scope.READ, "/api/x", false)).isTrue();
        assertThat(LocalAccessGuard.allowedByScope(Scope.READ, "/api/missions", true))
                .isFalse();
        assertThat(LocalAccessGuard.allowedByScope(Scope.MISSIONS_WRITE, "/api/x", false))
                .isTrue();
    }

    @Test
    void withoutAGuardedRequestTheScopeReadsAsRead() {
        assertThat(LocalAccessGuard.scopeOf(null)).isEqualTo(Scope.READ);
        assertThat(LocalAccessGuard.scopeOf(new FakeRc().rc)).isEqualTo(Scope.READ);
        assertThat(LocalAccessGuard.sessionOf(null)).isEmpty();
        assertThat(LocalAccessGuard.sessionOf(new FakeRc().rc)).isEmpty();
    }

    // ---- the browser's cookie

    @Test
    void aBrowserReadsWithItsCookieAlone() {
        String browser = registry.openBrowser("ua").secret();
        FakeRc rc = allowed(api(HttpMethod.GET, "/api/missions").withCookie(LocalAccessGuard.COOKIE, browser));
        assertThat(LocalAccessGuard.scopeOf(rc.rc)).isEqualTo(Scope.ALL);
        assertThat(LocalAccessGuard.sessionOf(rc.rc)).isPresent();
        allowed(api(HttpMethod.HEAD, "/api/missions").withCookie(LocalAccessGuard.COOKIE, browser));
        allowed(api(HttpMethod.OPTIONS, "/api/missions").withCookie(LocalAccessGuard.COOKIE, browser));
    }

    @Test
    void aBrowserChangeNamesItsOriginAndSendsTheRequestHeader() {
        String browser = registry.openBrowser("ua").secret();
        String own = "http://127.0.0.1:7420";
        denied(
                api(HttpMethod.POST, "/api/missions")
                        .withCookie(LocalAccessGuard.COOKIE, browser)
                        .header(LocalAccessGuard.REQUEST_HEADER, "1"),
                403,
                "FORBIDDEN");
        FakeRc noHeader = denied(
                api(HttpMethod.POST, "/api/missions")
                        .withCookie(LocalAccessGuard.COOKIE, browser)
                        .header("Origin", own),
                403,
                "FORBIDDEN");
        assertThat(noHeader.body).contains(LocalAccessGuard.REQUEST_HEADER);
        denied(
                api(HttpMethod.POST, "/api/missions")
                        .withCookie(LocalAccessGuard.COOKIE, browser)
                        .header("Origin", own)
                        .header(LocalAccessGuard.REQUEST_HEADER, "2"),
                403,
                "FORBIDDEN");
        allowed(api(HttpMethod.POST, "/api/missions")
                .withCookie(LocalAccessGuard.COOKIE, browser)
                .header("Origin", own)
                .header(LocalAccessGuard.REQUEST_HEADER, "1"));
        allowed(api(HttpMethod.DELETE, "/api/sessions/x")
                .withCookie(LocalAccessGuard.COOKIE, browser)
                .header("Origin", own)
                .header(LocalAccessGuard.REQUEST_HEADER, "1"));
    }

    @Test
    void aBrowserSocketMustNameItsOriginToo() {
        String browser = registry.openBrowser("ua").secret();
        denied(api(HttpMethod.GET, "/ws/events").withCookie(LocalAccessGuard.COOKIE, browser), 403, "FORBIDDEN");
        allowed(api(HttpMethod.GET, "/ws/events")
                .withCookie(LocalAccessGuard.COOKIE, browser)
                .header("Origin", "http://127.0.0.1:7420"));
    }

    @Test
    void aTokenNeedsNeitherOriginNorRequestHeader() {
        allowed(api(HttpMethod.POST, "/api/missions").header("Authorization", "Bearer " + token.value()));
        allowed(api(HttpMethod.GET, "/ws/events").header("Authorization", "Bearer " + token.value()));
    }

    // ---- sign-in routes

    @Test
    void aSignInLinkOpensABrowserSessionOnce() {
        assertThat(signIn.open("http://127.0.0.1:7420")).isTrue();
        String link = signIn.lastLink();
        String code = link.substring(link.indexOf("code=") + 5);
        FakeRc rc = api(HttpMethod.GET, "/auth").param("code", code).header("User-Agent", "browser/1");
        handlers.get(1).handle(rc.rc);
        assertThat(rc.status).isEqualTo(303);
        assertThat(rc.sentHeaders.get("location")).isEqualTo("/");
        var cookie = rc.sentCookies.get(0);
        assertThat(cookie.getName()).isEqualTo("floor42_session");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo(io.vertx.core.http.CookieSameSite.STRICT);
        assertThat(cookie.getPath()).isEqualTo("/");
        var session = registry.authenticate(cookie.getValue()).orElseThrow();
        assertThat(session.breakGlass()).isTrue();
        assertThat(session.userAgent()).isEqualTo("browser/1");
        assertThat(consoleSignIns).containsExactly(new ConsoleSignIn(session.id()));

        FakeRc again = api(HttpMethod.GET, "/auth").param("code", code);
        handlers.get(1).handle(again.rc);
        assertThat(again.status).isEqualTo(401);
        assertThat(again.code()).isEqualTo("UNAUTHORIZED");
        assertThat(again.sentCookies).isEmpty();
        assertThat(consoleSignIns).hasSize(1);
    }

    @Test
    void aWrongLinkOrATokenInTheUrlOpensNothing() {
        FakeRc wrong = api(HttpMethod.GET, "/auth").param("code", "nope");
        handlers.get(1).handle(wrong.rc);
        assertThat(wrong.status).isEqualTo(401);
        FakeRc inUrl = api(HttpMethod.GET, "/auth").param("token", token.value());
        handlers.get(1).handle(inUrl.rc);
        assertThat(inUrl.status).isEqualTo(400);
        assertThat(inUrl.code()).isEqualTo("TOKEN_IN_URL");
        assertThat(inUrl.sentCookies).isEmpty();
        assertThat(registry.list()).isEmpty();
        assertThat(consoleSignIns).isEmpty();
    }

    @Test
    void thePostedDirectorTokenGivesTheBrowserASessionOfItsOwn() {
        FakeRc rc = api(HttpMethod.POST, "/auth").header("Authorization", "Bearer " + token.value());
        handlers.get(2).handle(rc.rc);
        assertThat(rc.status).isEqualTo(303);
        String secret = rc.sentCookies.get(0).getValue();
        assertThat(secret).isNotEqualTo(token.value());
        assertThat(registry.authenticate(secret).orElseThrow().breakGlass()).isTrue();
        assertThat(consoleSignIns).hasSize(1);
    }

    @Test
    void theConsoleSignInNeedsAPostAndTheRightToken() {
        FakeRc get = api(HttpMethod.GET, "/auth").header("Authorization", "Bearer " + token.value());
        handlers.get(1).handle(get.rc);
        assertThat(get.status).isEqualTo(401);
        FakeRc wrong = api(HttpMethod.POST, "/auth").header("Authorization", "Bearer wrong");
        handlers.get(2).handle(wrong.rc);
        assertThat(wrong.status).isEqualTo(401);
        FakeRc none = api(HttpMethod.POST, "/auth");
        handlers.get(2).handle(none.rc);
        assertThat(none.status).isEqualTo(401);
        assertThat(none.code()).isEqualTo("UNAUTHORIZED");
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void askingForALinkPointsItAtTheRealPortAndAsksOnceAtATime() {
        FakeRc rc = api(HttpMethod.POST, "/auth/open").header("Host", "localhost:9999");
        handlers.get(3).handle(rc.rc);
        assertThat(rc.status).isEqualTo(202);
        assertThat(signIn.lastLink()).startsWith("http://localhost:7420/auth?code=");
        FakeRc soon = api(HttpMethod.POST, "/auth/open");
        handlers.get(3).handle(soon.rc);
        assertThat(soon.status).isEqualTo(429);
        assertThat(soon.code()).isEqualTo("TOO_MANY_REQUESTS");
    }

    // ---- pure helpers

    @Test
    void theHostNameIsWhatPrecedesThePort() {
        assertThat(LocalAccessGuard.hostName(null)).isEmpty();
        assertThat(LocalAccessGuard.hostName("localhost")).isEqualTo("localhost");
        assertThat(LocalAccessGuard.hostName("localhost:7420")).isEqualTo("localhost");
        assertThat(LocalAccessGuard.hostName("[::1]:7420")).isEqualTo("[::1]");
        assertThat(LocalAccessGuard.hostName("[::1")).isEqualTo("[::1");
        assertThat(LocalAccessGuard.hostName("localhost:1@elsewhere")).isEqualTo("localhost");
    }

    @Test
    void theSignInBaseNeverFollowsTheCallersPortOrAnythingAfterTheName() {
        assertThat(LocalAccessGuard.signInBase("127.0.0.1:6666", 7420)).isEqualTo("http://127.0.0.1:7420");
        assertThat(LocalAccessGuard.signInBase("localhost:1@elsewhere", 7420)).isEqualTo("http://localhost:7420");
        assertThat(LocalAccessGuard.signInBase("[::1]:5", 7420)).isEqualTo("http://[::1]:7420");
        assertThat(LocalAccessGuard.signInBase("floor42.localhost", 80)).isEqualTo("http://floor42.localhost:80");
        assertThat(LocalAccessGuard.signInBase("evil.example:7420", 7420)).isEqualTo("http://127.0.0.1:7420");
        assertThat(LocalAccessGuard.signInBase(null, 7420)).isEqualTo("http://127.0.0.1:7420");
    }

    @Test
    void theBearerIsWhatFollowsTheBearerPrefix() {
        assertThat(LocalAccessGuard.bearer(new FakeRc().header("Authorization", "Bearer abc").rc))
                .isEqualTo("abc");
        assertThat(LocalAccessGuard.bearer(new FakeRc().header("Authorization", "Token abc").rc))
                .isNull();
        assertThat(LocalAccessGuard.bearer(new FakeRc().rc)).isNull();
    }

    @Test
    void theSessionCookieIsHttpOnlyStrictAndSiteWide() {
        var c = LocalAccessGuard.sessionCookie("v");
        assertThat(c.getValue()).isEqualTo("v");
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.getSameSite()).isEqualTo(io.vertx.core.http.CookieSameSite.STRICT);
        assertThat(c.getPath()).isEqualTo("/");
    }
}
