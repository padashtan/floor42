package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.AssurancePolicy;
import dev.floor42.domain.AssurancePolicy.Act;
import dev.floor42.domain.AssurancePolicy.ActKind;
import dev.floor42.domain.AssurancePolicy.Seat;
import dev.floor42.domain.event.DeskEvent.ApprovalSigned;
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
import java.time.Duration;
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
 * Assured approvals (ADR-0081): a risky act is carried out only when Floor42 itself checked that Keycloak returned the
 * strong level after a fresh sign-in of the director, and with four eyes a second person, who is not the director,
 * holds the reviewer role and is allowed by the decision point. Real Keycloak client and token checks against a fake
 * Keycloak; the decision point is a fake that follows the model (a reviewer who is not the director).
 */
class AssuredActsTest {

    private static final Act ACT = new Act(ActKind.QUESTION, "Q-7");
    private static final Act SETTING = new Act(ActKind.SETTING, "FOUR_EYES");

    @TempDir
    Path dir;

    FakeKeycloak kc;
    AssuredActs acts;
    SessionRegistry registry;
    Countersigners countersigners;
    Session session;
    String secret;
    final List<List<ApprovalSigned>> ran = new ArrayList<>();
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
    final List<Handler<RoutingContext>> routes = new ArrayList<>();
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
        DirectorTokens directorTokens = new DirectorTokens();
        directorTokens.sessions = registry;
        directorTokens.keycloak = keycloak;
        countersigners = new Countersigners();
        // The model's rule, as the fake decision point knows it: a reviewer (the role tuple is sent) who is not the
        // director.
        countersigners.wire((user, relation, object, contextual) -> relation.equals("countersigner")
                && !user.equals("user:director")
                && contextual.stream().anyMatch(t -> t.relation().equals("member")));
        acts = new AssuredActs();
        acts.keycloak = keycloak;
        acts.jwks =
                new Jwks(URI.create(kc.issuer() + "/protocol/openid-connect/certs"), HttpClient.newHttpClient(), clock);
        acts.sessions = registry;
        acts.directorTokens = directorTokens;
        acts.countersigners = countersigners;
        acts.json = json;
        acts.clock = clock;
        acts.settings =
                new IamSettings(dir, Optional.of(true), Optional.of(kc.issuer().toString()), Optional.empty(), json);
        var routeProxy = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {io.vertx.ext.web.Route.class}, (p, m, a) -> {
                    if (m.getName().equals("blockingHandler")) {
                        @SuppressWarnings("unchecked")
                        Handler<RoutingContext> h = (Handler<RoutingContext>) a[0];
                        routes.add(h);
                    }
                    return p;
                });
        Router router = (Router) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Router.class}, (p, m, a) -> routeProxy);
        acts.register(router);
        SessionRegistry.Issued issued = registry.openBrowser("test-agent");
        session = issued.session();
        secret = issued.secret();
        directorTokens.put(
                session.id(),
                new Tokens(
                        kc.directorToken("director"), "refresh", null, now.get().plusSeconds(3600)));
    }

    @AfterEach
    void stop() {
        kc.close();
    }

    // ---- helpers

    private String click(Act act, boolean fourEyes) {
        return acts.record(act, fourEyes, session, ran::add);
    }

    private static String actOf(String path) {
        return path.substring(path.indexOf("act=") + 4);
    }

    private FakeRc assure(String path, String cookieSecret) {
        FakeRc rc = new FakeRc().header("Host", "127.0.0.1:7420").param("act", actOf(path));
        if (cookieSecret != null) rc.withCookie(LocalAccessGuard.COOKIE, cookieSecret);
        routes.get(0).handle(rc.rc);
        return rc;
    }

    /** The second person opens the link in a private window: no Floor42 cookie at all. */
    private FakeRc countersign(String act) {
        FakeRc rc = new FakeRc().header("Host", "127.0.0.1:7420").param("act", act);
        routes.get(1).handle(rc.rc);
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

    /** What Keycloak sends back after the sign-in: the callback request with state, code and the pre-sign-in cookie. */
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

    private FakeRc signIn(FakeRc started) throws Exception {
        FakeRc cb = callback(started, Map.of());
        assertThat(acts.callback(cb.rc)).isTrue();
        return cb;
    }

    private FakeRc firstSignature(boolean fourEyes) throws Exception {
        return signIn(assure(click(ACT, fourEyes), secret));
    }

    // ---- step one

    @Test
    void theRedirectAsksForTheStrongLevelAndTheSecondFactorEveryTime() {
        FakeRc rc = assure(click(ACT, false), secret);
        assertThat(rc.status).isEqualTo(302);
        Map<String, String> q = query(rc.sentHeaders.get("location"));
        assertThat(q)
                .containsEntry("client_id", "floor42-desk")
                .containsEntry("scope", "openid")
                .containsEntry("acr_values", "floor42-strong")
                .containsEntry("prompt", "login")
                .containsEntry("max_age", "0")
                .containsEntry("code_challenge_method", "S256")
                .doesNotContainKey("login_hint");
        assertThat(q.get("scope")).doesNotContain("offline_access");
        assertThat(rc.sentCookies).hasSize(1);
    }

    @Test
    void onlyABrowserSessionWithLiveKeycloakTokensMayAssureAnAct() {
        assertThat(acts.mayAssure(Optional.of(session))).isTrue();
        assertThat(acts.mayAssure(Optional.empty())).isFalse();
        Session script =
                registry.issueScript(SessionRegistry.Scope.ALL, 1, "s", "ua").session();
        acts.directorTokens.put(
                script.id(),
                new Tokens(kc.directorToken("director"), "r", null, now.get().plusSeconds(3600)));
        assertThat(acts.mayAssure(Optional.of(script))).isFalse();
        Session glass = registry.openBreakGlass("ua").session();
        acts.directorTokens.put(
                glass.id(),
                new Tokens(kc.directorToken("director"), "r", null, now.get().plusSeconds(3600)));
        assertThat(acts.mayAssure(Optional.of(glass))).isFalse();
    }

    @Test
    void anotherBrowserOrNoCookieCannotStartTheFirstSeat() {
        String path = click(ACT, false);
        assertThat(assure(path, null).status).isEqualTo(401);
        assertThat(assure(path, registry.openBrowser("other").secret()).code()).isEqualTo("STEP_UP_UNKNOWN");
        assertThat(assure("act=nothing", secret).status).isEqualTo(401);
    }

    // ---- strong approval of a risky act (S3)

    @Test
    void aStrongSignInCarriesTheActWithItsSignature() throws Exception {
        FakeRc cb = firstSignature(false);
        assertThat(cb.status).isEqualTo(200);
        assertThat(ran).hasSize(1);
        ApprovalSigned s = ran.getFirst().getFirst();
        assertThat(s.act()).isEqualTo(ACT);
        assertThat(s.seat()).isEqualTo(Seat.FIRST);
        assertThat(s.subject()).isEqualTo("director");
        assertThat(s.acr()).isEqualTo(AssurancePolicy.ACR_STRONG);
        assertThat(s.authTime())
                .isBetween(now.get().getEpochSecond() - 60, now.get().getEpochSecond() + 60);
        assertThat(s.idTokenId()).isNotBlank();
    }

    @Test
    void anAllowWithoutTheStrongAcrIsRefused() throws Exception {
        kc.idTokenAcr("floor42-basic");
        String path = click(ACT, false);
        FakeRc cb = signIn(assure(path, secret));
        assertThat(cb.status).isEqualTo(401);
        assertThat(cb.code()).isEqualTo("ASSURANCE_TOO_LOW");
        assertThat(ran).isEmpty();
        assertThat(assure(path, secret).status).isEqualTo(401);
    }

    @Test
    void aStrongStepUpOfAUserWithNoFactorDoesNotYieldStrong() throws Exception {
        // The realm refuses to enrol a factor there: Keycloak sends no code (an error), or at best the basic level.
        String path = click(ACT, false);
        FakeRc started = assure(path, secret);
        FakeRc denied = callback(started, Map.of("error", "access_denied"));
        assertThat(acts.callback(denied.rc)).isTrue();
        assertThat(denied.code()).isEqualTo("SIGN_IN_FAILED");

        kc.omitIdTokenClaim("acr");
        FakeRc noAcr = signIn(assure(click(ACT, false), secret));
        assertThat(noAcr.code()).isEqualTo("ASSURANCE_TOO_LOW");
        assertThat(ran).isEmpty();
    }

    @Test
    void anAcrTheDirectorTypedInTheQueryIsIgnored() throws Exception {
        kc.idTokenAcr("floor42-basic");
        FakeRc started = assure(click(ACT, false), secret);
        FakeRc cb = callback(started, Map.of("acr", "floor42-strong", "acr_values", "floor42-strong"));
        assertThat(acts.callback(cb.rc)).isTrue();
        assertThat(cb.code()).isEqualTo("ASSURANCE_TOO_LOW");
        assertThat(ran).isEmpty();
    }

    @Test
    void aStaleSignInIsRefused() throws Exception {
        // Keycloak answered from its single sign-on session, as when prompt=login was dropped on the way.
        kc.ignorePromptLogin(true);
        FakeRc cb = firstSignature(false);
        assertThat(cb.status).isEqualTo(401);
        assertThat(cb.code()).isEqualTo("STEP_UP_STALE");
        assertThat(ran).isEmpty();
    }

    @Test
    void aSignInOfAnotherUserIsNotTheDirectors() throws Exception {
        kc.signInAs("someone-else");
        FakeRc cb = firstSignature(false);
        assertThat(cb.code()).isEqualTo("STEP_UP_WRONG_USER");
        assertThat(ran).isEmpty();
    }

    @Test
    void aFirstSeatWithoutTheDirectorRoleIsRefused() throws Exception {
        kc.accessRoles("floor42-reviewer");
        try {
            FakeRc cb = firstSignature(false);
            assertThat(cb.status).isEqualTo(401);
            assertThat(cb.code()).isEqualTo("DESK_DIRECTOR_ONLY");
            assertThat(ran).isEmpty();
        } finally {
            kc.accessRoles("floor42-director");
        }
    }

    @Test
    void anActIsSingleUse() throws Exception {
        String path = click(ACT, false);
        FakeRc cb = signIn(assure(path, secret));
        assertThat(ran).hasSize(1);
        assertThat(acts.callback(cb.rc)).isFalse();
        assertThat(assure(path, secret).status).isEqualTo(401);
        assertThat(ran).hasSize(1);
    }

    @Test
    void anActOlderThanFiveMinutesIsGone() {
        String path = click(ACT, false);
        now.set(now.get().plus(AssuredActs.TTL).plusSeconds(1));
        assertThat(assure(path, secret).code()).isEqualTo("STEP_UP_UNKNOWN");
    }

    @Test
    void aRefusalOfTheActItselfIsReportedAndNothingStays() throws Exception {
        String path = acts.record(ACT, false, session, signed -> {
            throw new dev.floor42.domain.DomainException(
                    dev.floor42.domain.Refusal.ASSURANCE_TOO_LOW, "the domain said no");
        });
        FakeRc cb = signIn(assure(path, secret));
        assertThat(cb.status).isEqualTo(409);
        assertThat(cb.code()).isEqualTo("ASSURANCE_TOO_LOW");
    }

    // ---- four eyes (S4)

    private String waitingAct() {
        return acts.waiting().getFirst().id();
    }

    private FakeRc secondSignIn(String act, String sub, String... roles) throws Exception {
        kc.signInAs(sub);
        kc.accessRoles(roles);
        FakeRc started = countersign(act);
        assertThat(started.status).isEqualTo(302);
        return signIn(started);
    }

    @Test
    void theFirstSignatureWaitsForASecondOneAndRunsNothing() throws Exception {
        FakeRc cb = firstSignature(true);
        assertThat(cb.status).isEqualTo(200);
        assertThat(ran).isEmpty();
        assertThat(acts.waiting()).hasSize(1);
        var waiting = acts.waiting().getFirst();
        assertThat(waiting.kind()).isEqualTo("QUESTION");
        assertThat(waiting.ref()).isEqualTo("Q-7");
        assertThat(waiting.firstSubject()).isEqualTo("director");
        assertThat(waiting.until()).isEqualTo(now.get().plus(AssurancePolicy.COUNTERSIGN_WINDOW));
        assertThat(waiting.path()).startsWith("/auth/keycloak/countersign?act=");
    }

    @Test
    void aReviewerWhoIsNotTheDirectorCountersignsAndTheActRunsWithBothSignatures() throws Exception {
        firstSignature(true);
        FakeRc cb = secondSignIn(waitingAct(), "reviewer-1", "floor42-reviewer");
        assertThat(cb.status).isEqualTo(200);
        assertThat(ran).hasSize(1);
        assertThat(ran.getFirst()).extracting(ApprovalSigned::seat).containsExactly(Seat.FIRST, Seat.SECOND);
        assertThat(ran.getFirst()).extracting(ApprovalSigned::subject).containsExactly("director", "reviewer-1");
        assertThat(acts.waiting()).isEmpty();
    }

    @Test
    void theCountersignCallbackCreatesNoSession() throws Exception {
        firstSignature(true);
        int before = registry.list().size();
        FakeRc cb = secondSignIn(waitingAct(), "reviewer-1", "floor42-reviewer");
        assertThat(cb.status).isEqualTo(200);
        assertThat(cb.sentCookies)
                .as("no Floor42 cookie is given to the second person")
                .isEmpty();
        assertThat(registry.list()).hasSize(before);
        assertThat(cb.body).doesNotContain("url=/");
    }

    @Test
    void theSameSubjectCannotSignTwice() throws Exception {
        firstSignature(true);
        // The director's own account, whatever roles it holds, is the same person.
        FakeRc cb = secondSignIn(waitingAct(), "director", "floor42-reviewer");
        assertThat(cb.status).isEqualTo(403);
        assertThat(cb.code()).isEqualTo("SAME_PERSON");
        assertThat(ran).isEmpty();
        assertThat(acts.waiting())
                .as("a wrong second person does not cancel the first signature")
                .hasSize(1);
    }

    @Test
    void aUserWithoutTheReviewerRoleIsRefused() throws Exception {
        firstSignature(true);
        FakeRc cb = secondSignIn(waitingAct(), "intern-1");
        assertThat(cb.status).isEqualTo(403);
        assertThat(cb.code()).isEqualTo("NOT_A_REVIEWER");
        assertThat(ran).isEmpty();
    }

    @Test
    void aReviewerTheDecisionPointRefusesIsRefused() throws Exception {
        firstSignature(true);
        countersigners.wire((user, relation, object, contextual) -> false);
        FakeRc cb = secondSignIn(waitingAct(), "reviewer-1", "floor42-reviewer");
        assertThat(cb.code()).isEqualTo("NOT_A_REVIEWER");
    }

    @Test
    void withoutADecisionPointNobodyCountersigns() throws Exception {
        firstSignature(true);
        countersigners.wire(null);
        FakeRc cb = secondSignIn(waitingAct(), "reviewer-1", "floor42-reviewer");
        assertThat(cb.status).isEqualTo(503);
        assertThat(cb.code()).isEqualTo("FGA_UNAVAILABLE");
        assertThat(ran).isEmpty();
    }

    @Test
    void aDecisionPointThatFailsRefusesAndNeverAllows() throws Exception {
        firstSignature(true);
        countersigners.wire((user, relation, object, contextual) -> {
            throw new IllegalStateException("down");
        });
        FakeRc cb = secondSignIn(waitingAct(), "reviewer-1", "floor42-reviewer");
        assertThat(cb.code()).isEqualTo("FGA_UNAVAILABLE");
    }

    @Test
    void theRoleComesFromTheAccessTokenOnlyAndAnUnsignedOneCountsForNothing() throws Exception {
        firstSignature(true);
        // No realm_access in the token: the role is unknown, the decision point is not even asked to allow.
        FakeRc cb = secondSignIn(waitingAct(), "reviewer-1");
        assertThat(cb.code()).isEqualTo("NOT_A_REVIEWER");
    }

    @Test
    void aFirstSignatureExpires() throws Exception {
        firstSignature(true);
        String act = waitingAct();
        now.set(now.get().plus(AssurancePolicy.COUNTERSIGN_WINDOW).plusSeconds(1));
        assertThat(countersign(act).code()).isEqualTo("STEP_UP_UNKNOWN");
        assertThat(acts.waiting()).isEmpty();
    }

    @Test
    void aFirstSignatureThatExpiresDuringTheSecondSignInIsRefused() throws Exception {
        firstSignature(true);
        String act = waitingAct();
        Duration almost = AssurancePolicy.COUNTERSIGN_WINDOW.minusSeconds(30);
        now.set(now.get().plus(almost));
        kc.clockOffset(almost);
        kc.signInAs("reviewer-1");
        kc.accessRoles("floor42-reviewer");
        FakeRc started = countersign(act);
        assertThat(started.status).isEqualTo(302);
        FakeRc cb = callback(started, Map.of());
        now.set(now.get().plus(Duration.ofSeconds(60)));
        assertThat(acts.callback(cb.rc)).isTrue();
        assertThat(cb.code()).isEqualTo("SIGNATURE_EXPIRED");
        assertThat(ran).isEmpty();
    }

    @Test
    void theSecondSeatIsNotOpenBeforeTheFirstSignature() {
        String path = click(ACT, true);
        assertThat(countersign(actOf(path)).code()).isEqualTo("STEP_UP_UNKNOWN");
    }

    @Test
    void turningFourEyesOffIsAnActOfTwoSignatures() throws Exception {
        String path = acts.record(SETTING, true, session, ran::add);
        signIn(assure(path, secret));
        assertThat(ran).isEmpty();
        secondSignIn(waitingAct(), "reviewer-1", "floor42-reviewer");
        assertThat(ran).hasSize(1);
        assertThat(ran.getFirst()).extracting(a -> a.act().ref()).containsOnly("FOUR_EYES");
    }
}
