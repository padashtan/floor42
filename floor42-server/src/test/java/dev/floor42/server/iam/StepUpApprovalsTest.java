package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.MissionId;
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
 * The plan approval step-up: pending approval single use, 5 minutes, bound to the browser session and to the director,
 * PKCE and nonce, a fresh password proof, the same user. Real Keycloak client and token checks against a fake Keycloak;
 * the Vert.x request and response are plain data ({@link FakeRc}).
 */
class StepUpApprovalsTest {

    private static final MissionId M1 = new MissionId("M-1");
    private static final MissionId M2 = new MissionId("M-2");

    @TempDir
    Path dir;

    FakeKeycloak kc;
    StepUpApprovals approvals;
    SessionRegistry registry;
    DirectorTokens directorTokens;
    Delegations delegations;
    Session session;
    String secret;
    boolean iamOn = true;
    RuntimeException approveFails;
    final List<String> approved = new ArrayList<>();
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
        directorTokens = new DirectorTokens();
        directorTokens.sessions = registry;
        directorTokens.keycloak = keycloak;
        delegations = new Delegations(
                dir.resolve("iam").resolve("delegations.json"),
                vault,
                keycloak,
                () -> iamOn,
                Optional::empty,
                directorTokens,
                json);
        approvals = new StepUpApprovals();
        approvals.keycloak = keycloak;
        approvals.jwks =
                new Jwks(URI.create(kc.issuer() + "/protocol/openid-connect/certs"), HttpClient.newHttpClient(), clock);
        approvals.sessions = registry;
        approvals.directorTokens = directorTokens;
        approvals.delegations = delegations;
        approvals.json = json;
        approvals.clock = clock;
        approvals.approver = new PlanApprover() {
            public void approve(MissionId mission, Double budgetUsd, boolean noCap) {
                if (approveFails != null) throw approveFails;
                approved.add(mission + "/" + budgetUsd + "/" + noCap);
            }

            public void requireAwaiting(MissionId mission) {}
        };
        // settings is read through enabled(): the IAM switch of the test
        approvals.settings =
                new IamSettings(dir, Optional.of(true), Optional.of(kc.issuer().toString()), Optional.empty(), json) {
                    @Override
                    public boolean enabled() {
                        return iamOn;
                    }
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
        approvals.register(router);
        SessionRegistry.Issued issued = registry.openBrowser("test-agent");
        session = issued.session();
        secret = issued.secret();
        Tokens director = new Tokens(
                kc.directorToken("director"), "refresh", null, now.get().plusSeconds(3600));
        directorTokens.put(session.id(), director);
    }

    @AfterEach
    void stop() {
        kc.close();
    }

    private String click(MissionId m) {
        return approvals.record(m, 12.5, false, List.of("crm", "crm", " wiki "), session);
    }

    private FakeRc begin(String mission, String cookieSecret) {
        FakeRc rc = new FakeRc().header("Host", "127.0.0.1:7420");
        if (mission != null) rc.param("mission", mission);
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

    /** The redirect Keycloak gives back after the password: callback request with state, code and the pre-cookie. */
    private FakeRc callback(FakeRc started, Map<String, String> extra) throws Exception {
        String location = started.sentHeaders.get("location");
        HttpResponse<String> r =
                http.send(HttpRequest.newBuilder(URI.create(location)).build(), HttpResponse.BodyHandlers.ofString());
        Map<String, String> back = query(r.headers().firstValue("Location").orElseThrow());
        String state = query(location).get("state");
        FakeRc rc = new FakeRc().param("state", state).param("code", back.get("code"));
        String pre = started.given("floor42_pre_" + state.substring(0, 8).replaceAll("[^A-Za-z0-9_-]", "_"));
        if (pre != null) rc.withCookie("floor42_pre_" + state.substring(0, 8).replaceAll("[^A-Za-z0-9_-]", "_"), pre);
        extra.forEach(rc::param);
        return rc;
    }

    private FakeRc complete() throws Exception {
        click(M1);
        FakeRc started = begin("M-1", secret);
        FakeRc cb = callback(started, Map.of());
        assertThat(approvals.callback(cb.rc)).isTrue();
        return cb;
    }

    // ---- mayApprove, record

    @Test
    void onlyABrowserSessionWithLiveKeycloakTokensMayApprove() {
        assertThat(approvals.mayApprove(Optional.of(session))).isTrue();
        assertThat(approvals.mayApprove(Optional.empty())).isFalse();
        Session script =
                registry.issueScript(SessionRegistry.Scope.ALL, 1, "s", "ua").session();
        directorTokens.put(
                script.id(),
                new Tokens(kc.directorToken("director"), "r", null, now.get().plusSeconds(3600)));
        assertThat(approvals.mayApprove(Optional.of(script))).isFalse();
        Session glass = registry.openBreakGlass("ua").session();
        directorTokens.put(
                glass.id(),
                new Tokens(kc.directorToken("director"), "r", null, now.get().plusSeconds(3600)));
        assertThat(approvals.mayApprove(Optional.of(glass))).isFalse();
        Session bare = registry.openBrowser("ua").session();
        assertThat(approvals.mayApprove(Optional.of(bare))).isFalse();
    }

    @Test
    void theClickGivesTheApprovalPathOfThatMission() {
        assertThat(click(M1)).isEqualTo("/auth/keycloak/approve?mission=M-1");
    }

    // ---- step one

    @Test
    void theRedirectAsksForThePasswordAgainWithPkceAndAnOfflineToken() {
        click(M1);
        FakeRc rc = begin("M-1", secret);
        assertThat(rc.status).isEqualTo(302);
        assertThat(rc.sentHeaders.get("cache-control")).isEqualTo("no-store");
        String location = rc.sentHeaders.get("location");
        assertThat(location).startsWith(kc.issuer() + "/protocol/openid-connect/auth?");
        Map<String, String> q = query(location);
        assertThat(q)
                .containsEntry("response_type", "code")
                .containsEntry("client_id", "floor42-desk")
                .containsEntry("scope", "openid offline_access")
                .containsEntry("prompt", "login")
                .containsEntry("max_age", "0")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("redirect_uri", "http://127.0.0.1:7420/auth/callback");
        assertThat(q.get("state")).hasSizeGreaterThan(30);
        assertThat(q.get("nonce")).hasSizeGreaterThan(30).isNotEqualTo(q.get("state"));
        assertThat(q.get("code_challenge")).hasSize(43);
        assertThat(rc.sentCookies).hasSize(1);
        assertThat(rc.sentCookies.get(0).isHttpOnly()).isTrue();
        assertThat(approvals.lastOnEventLoop).isFalse();
    }

    @Test
    void theRedirectUriNeverFollowsTheHostHeaderOfTheCaller() {
        click(M1);
        FakeRc rc = new FakeRc().header("Host", "evil.example:6666").param("mission", "M-1");
        rc.withCookie(LocalAccessGuard.COOKIE, secret);
        route.handle(rc.rc);
        assertThat(query(rc.sentHeaders.get("location")).get("redirect_uri"))
                .isEqualTo("http://127.0.0.1:7420/auth/callback");
        click(M2);
        FakeRc localhost = new FakeRc().header("Host", "localhost:9999").param("mission", "M-2");
        localhost.withCookie(LocalAccessGuard.COOKIE, secret);
        route.handle(localhost.rc);
        assertThat(query(localhost.sentHeaders.get("location")).get("redirect_uri"))
                .isEqualTo("http://localhost:7420/auth/callback");
    }

    @Test
    void withIamOffThereIsNothingToApproveWithAPassword() {
        click(M1);
        iamOn = false;
        FakeRc rc = begin("M-1", secret);
        assertThat(rc.status).isEqualTo(404);
        assertThat(rc.code()).isEqualTo("IAM_OFF");
    }

    @Test
    void noApprovalNoCookieOrAnotherBrowserIsUnknown() {
        click(M1);
        assertThat(begin("M-2", secret).code()).isEqualTo("DELEGATION_APPROVAL_UNKNOWN");
        assertThat(begin(null, secret).status).isEqualTo(401);
        assertThat(begin("M-1", null).status).isEqualTo(401);
        assertThat(begin("not-a-mission", secret).status).isEqualTo(401);
        assertThat(begin("M-1", "not-a-session-secret").status).isEqualTo(401);
        String other = registry.openBrowser("other").secret();
        FakeRc rc = begin("M-1", other);
        assertThat(rc.status).isEqualTo(401);
        assertThat(rc.code()).isEqualTo("DELEGATION_APPROVAL_UNKNOWN");
        assertThat(rc.sentHeaders).doesNotContainKey("location");
    }

    @Test
    void aFetchOrAnImageOfAnotherSiteCannotStartTheFlow() {
        click(M1);
        FakeRc rc = new FakeRc().header("Host", "127.0.0.1:7420").param("mission", "M-1");
        rc.withCookie(LocalAccessGuard.COOKIE, secret).header("Sec-Fetch-Site", "cross-site");
        route.handle(rc.rc);
        assertThat(rc.status).isEqualTo(403);
        assertThat(rc.code()).isEqualTo("NOT_A_NAVIGATION");
        FakeRc fetch = new FakeRc().header("Host", "127.0.0.1:7420").param("mission", "M-1");
        fetch.withCookie(LocalAccessGuard.COOKIE, secret).header("Sec-Fetch-Mode", "cors");
        route.handle(fetch.rc);
        assertThat(fetch.status).isEqualTo(403);
        FakeRc img = new FakeRc().header("Host", "127.0.0.1:7420").param("mission", "M-1");
        img.withCookie(LocalAccessGuard.COOKIE, secret).header("Sec-Fetch-Dest", "image");
        route.handle(img.rc);
        assertThat(img.status).isEqualTo(403);
    }

    @Test
    void anApprovalOlderThanFiveMinutesIsGone() {
        click(M1);
        now.set(now.get().plus(StepUpApprovals.TTL).minusSeconds(1));
        assertThat(begin("M-1", secret).status).isEqualTo(302);
        now.set(now.get().plusSeconds(2));
        assertThat(begin("M-1", secret).code()).isEqualTo("DELEGATION_APPROVAL_UNKNOWN");
    }

    @Test
    void aSixthPendingApprovalEvictsTheOldestOne() {
        for (int i = 1; i <= StepUpApprovals.MAX_PENDING + 1; i++) click(new MissionId("M-" + i));
        assertThat(begin("M-1", secret).status).isEqualTo(401);
        assertThat(begin("M-2", secret).status).isEqualTo(302);
        assertThat(begin("M-6", secret).status).isEqualTo(302);
    }

    @Test
    void expiredApprovalsMakeRoomBeforeTheLiveOnesAreEvicted() {
        for (int i = 1; i <= StepUpApprovals.MAX_PENDING - 1; i++) click(new MissionId("M-" + i));
        now.set(now.get().plus(StepUpApprovals.TTL).plusSeconds(1));
        click(new MissionId("M-8"));
        click(new MissionId("M-9"));
        assertThat(begin("M-9", secret).status).isEqualTo(302);
        assertThat(begin("M-8", secret).status).isEqualTo(302);
    }

    // ---- step two

    @Test
    void aTypedPasswordOfTheSameDirectorApprovesOnceAndStoresTheDelegation() throws Exception {
        FakeRc cb = complete();
        assertThat(cb.status).isEqualTo(200);
        assertThat(cb.sentHeaders.get("cache-control")).isEqualTo("no-store");
        assertThat(cb.sentHeaders.get("content-type")).isEqualTo("text/html; charset=utf-8");
        assertThat(cb.body).contains("url=/");
        assertThat(approved).containsExactly("M-1/12.5/false");
        assertThat(delegations.find(M1))
                .get()
                .extracting(MissionDelegation::resources)
                .isEqualTo(List.of("crm", "wiki"));
        assertThat(begin("M-1", secret).status).isEqualTo(401);
    }

    @Test
    void aReplayOfTheSameCallbackFindsNothing() throws Exception {
        FakeRc cb = complete();
        assertThat(approvals.callback(cb.rc)).isFalse();
        assertThat(approved).hasSize(1);
    }

    @Test
    void aStateThatIsNotOursIsLeftToTheSignIn() throws Exception {
        click(M1);
        FakeRc started = begin("M-1", secret);
        String state = query(started.sentHeaders.get("location")).get("state");
        assertThat(approvals.callback(new FakeRc().param("state", state + "x").rc))
                .isFalse();
        assertThat(approvals.callback(new FakeRc().param("state", state.substring(1)).rc))
                .isFalse();
        assertThat(approvals.callback(new FakeRc().rc)).isFalse();
        assertThat(approvals.callback(callback(started, Map.of()).rc)).isTrue();
    }

    @Test
    void aCallbackWithoutThePreSignInCookieDidNotStartInThisBrowser() throws Exception {
        click(M1);
        FakeRc started = begin("M-1", secret);
        started.sentCookies.clear();
        FakeRc cb = callback(started, Map.of());
        assertThat(approvals.callback(cb.rc)).isTrue();
        assertThat(cb.status).isEqualTo(401);
        assertThat(cb.code()).isEqualTo("SIGN_IN_BROWSER_UNKNOWN");
        assertThat(approved).isEmpty();
        assertThat(approvals.callback(cb.rc)).isFalse();
    }

    @Test
    void aCookieOfAnotherFlowDoesNotOpenThisOne() throws Exception {
        click(M1);
        FakeRc started = begin("M-1", secret);
        FakeRc cb = callback(started, Map.of());
        cb.cookies.replaceAll((k, v) -> v + "tampered");
        assertThat(cb.cookies).hasSize(1);
        assertThat(approvals.callback(cb.rc)).isTrue();
        assertThat(cb.code()).isEqualTo("SIGN_IN_BROWSER_UNKNOWN");
    }

    @Test
    void aFlowOlderThanFiveMinutesIsGone() throws Exception {
        click(M1);
        FakeRc started = begin("M-1", secret);
        FakeRc cb = callback(started, Map.of());
        now.set(now.get().plus(StepUpApprovals.TTL).plusSeconds(1));
        assertThat(approvals.callback(cb.rc)).isFalse();
        assertThat(approved).isEmpty();
    }

    @Test
    void anErrorFromKeycloakOrNoCodeFailsAndDropsTheApproval() throws Exception {
        click(M1);
        FakeRc started = begin("M-1", secret);
        FakeRc denied = callback(started, Map.of("error", "access_denied"));
        assertThat(approvals.callback(denied.rc)).isTrue();
        assertThat(denied.status).isEqualTo(401);
        assertThat(denied.code()).isEqualTo("SIGN_IN_FAILED");
        assertThat(begin("M-1", secret).status).isEqualTo(401);

        click(M1);
        FakeRc second = begin("M-1", secret);
        FakeRc noCode = callback(second, Map.of());
        noCode.params.remove("code");
        assertThat(approvals.callback(noCode.rc)).isTrue();
        assertThat(noCode.code()).isEqualTo("SIGN_IN_FAILED");
        assertThat(approved).isEmpty();
    }

    @Test
    void aSecondClickReplacesTheFirstSoTheFirstFlowCannotApprove() throws Exception {
        click(M1);
        FakeRc started = begin("M-1", secret);
        FakeRc cb = callback(started, Map.of());
        approvals.record(M1, 99.0, true, List.of(), session);
        assertThat(approvals.callback(cb.rc)).isTrue();
        assertThat(cb.status).isEqualTo(401);
        assertThat(cb.code()).isEqualTo("DELEGATION_APPROVAL_UNKNOWN");
        assertThat(approved).isEmpty();
    }

    @Test
    void anApprovalThatExpiredBeforeThePasswordWasTypedIsRefused() throws Exception {
        click(M1);
        now.set(now.get().plus(StepUpApprovals.TTL).minusSeconds(1));
        FakeRc started = begin("M-1", secret);
        FakeRc cb = callback(started, Map.of());
        now.set(now.get().plusSeconds(2));
        assertThat(approvals.callback(cb.rc)).isTrue();
        assertThat(cb.code()).isEqualTo("DELEGATION_APPROVAL_UNKNOWN");
        assertThat(approved).isEmpty();
    }

    @Test
    void anIdTokenOfAnotherNonceIsNotAProof() throws Exception {
        click(M1);
        kc.idTokenNonce("someone-elses-nonce");
        FakeRc cb = callback(begin("M-1", secret), Map.of());
        assertThat(approvals.callback(cb.rc)).isTrue();
        assertThat(cb.code()).isEqualTo("SIGN_IN_FAILED");
        assertThat(approved).isEmpty();
    }

    @Test
    void anIdTokenWithoutSubOrIatIsNotAProof() throws Exception {
        for (String claim : new String[] {"sub", "iat"}) {
            click(M1);
            kc.omitIdTokenClaim(claim);
            FakeRc cb = callback(begin("M-1", secret), Map.of());
            assertThat(approvals.callback(cb.rc)).isTrue();
            assertThat(cb.code()).isEqualTo("SIGN_IN_FAILED");
        }
        assertThat(approved).isEmpty();
    }

    @Test
    void aKeycloakThatIgnoredPromptLoginIsNotAStepUp() throws Exception {
        click(M1);
        FakeRc started = begin("M-1", secret);
        kc.ignorePromptLogin(true);
        now.set(now.get().plusSeconds(10));
        FakeRc cb = callback(started, Map.of());
        assertThat(approvals.callback(cb.rc)).isTrue();
        assertThat(cb.status).isEqualTo(401);
        assertThat(cb.code()).isEqualTo("DELEGATION_STEP_UP_MISSING");
        assertThat(approved).isEmpty();
        assertThat(delegations.find(M1)).isEmpty();
    }

    @Test
    void thePasswordOfAnotherUserDoesNotApprove() throws Exception {
        click(M1);
        kc.signInAs("mallory");
        FakeRc cb = callback(begin("M-1", secret), Map.of());
        assertThat(approvals.callback(cb.rc)).isTrue();
        assertThat(cb.status).isEqualTo(401);
        assertThat(cb.code()).isEqualTo("DELEGATION_WRONG_USER");
        assertThat(approved).isEmpty();
        assertThat(delegations.find(M1)).isEmpty();
    }

    @Test
    void aKeycloakThatIsDownFailsTheSignInWithoutApproving() throws Exception {
        click(M1);
        FakeRc started = begin("M-1", secret);
        FakeRc cb = callback(started, Map.of());
        kc.down(true);
        assertThat(approvals.callback(cb.rc)).isTrue();
        assertThat(cb.code()).isEqualTo("SIGN_IN_FAILED");
        assertThat(approved).isEmpty();
    }

    @Test
    void aPlanThatCannotBeApprovedUndoesTheDelegationAndSaysSo() throws Exception {
        click(M1);
        approveFails = new IllegalStateException("not awaiting approval");
        FakeRc cb = callback(begin("M-1", secret), Map.of());
        assertThat(approvals.callback(cb.rc)).isTrue();
        assertThat(cb.status).isEqualTo(409);
        assertThat(cb.code()).isEqualTo("DELEGATION_APPROVAL_FAILED");
        assertThat(cb.body).contains("not awaiting approval");
        assertThat(delegations.isRevoked(M1)).isTrue();
        assertThat(kc.requests()).anyMatch(r -> r.get("_path").endsWith("/revoke"));
        assertThat(begin("M-1", secret).status).isEqualTo(401);
    }
}
