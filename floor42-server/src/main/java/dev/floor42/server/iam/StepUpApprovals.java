package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.MissionId;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.security.LocalAccessGuard;
import dev.floor42.server.security.SessionRegistry;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Plan approval in IAM mode is a step-up: the click only records a pending approval (5 minutes, single use, bound to
 * the browser session and to the signed-in director); the director then types the Keycloak password again
 * ({@code prompt=login}, {@code max_age=0}, {@code offline_access}), which gives this mission its own offline session.
 * Only when the id_token proves a fresh authentication of the same user is the offline token stored in the vault and
 * the plan approved. Any failure leaves the mission awaiting approval and drops the pending approval. The callback
 * is the sign-in's {@code /auth/callback} (the only redirect URI the realm lists): {@link KeycloakSignIn} hands it
 * the states it does not know.
 */
@ApplicationScoped
public class StepUpApprovals {

    private static final Logger LOG = Logger.getLogger(StepUpApprovals.class);
    static final Duration TTL = Duration.ofMinutes(5);
    static final int MAX_PENDING = 5;
    private static final String CLIENT = "floor42-desk";
    private static final int ORDER = Integer.MIN_VALUE + 2;

    /** The click: what will be approved, for whom, from which browser session. */
    record Approval(
            MissionId mission,
            Double budgetUsd,
            boolean noCap,
            List<String> resources,
            String browserSession,
            String directorSub,
            Instant at) {}

    /** The code flow started for an approval. */
    private record Flow(Approval approval, String nonce, String verifier, URI redirect, String bind, Instant at) {}

    @Inject
    IamSettings settings;

    @Inject
    KeycloakClient keycloak;

    @Inject
    Jwks jwks;

    @Inject
    SessionRegistry sessions;

    @Inject
    DirectorTokens directorTokens;

    @Inject
    Delegations delegations;

    @Inject
    PlanApprover approver;

    @Inject
    ObjectMapper json;

    Clock clock = Clock.systemUTC();

    private final Map<MissionId, Approval> approvals = new LinkedHashMap<>();
    private final Map<String, Flow> flows = new LinkedHashMap<>();

    void register(@Observes Router router) {
        router.get("/auth/keycloak/approve").order(ORDER).blockingHandler(this::begin, false);
    }

    /** True when this browser session may approve in IAM mode: a Keycloak sign-in with live tokens, not a script. */
    public boolean mayApprove(Optional<SessionRegistry.Session> session) {
        return session.isPresent()
                && session.get().kind() == SessionRegistry.Kind.BROWSER
                && !session.get().breakGlass()
                && directorTokens.live(session.get().id()).isPresent();
    }

    /**
     * Records the pending approval of this click (a second click replaces the first) and gives the path the UI follows.
     * The caller has checked {@link #mayApprove} and that the plan awaits approval.
     */
    public synchronized String record(
            MissionId m, Double budgetUsd, boolean noCap, List<String> resources, SessionRegistry.Session session) {
        Tokens director = directorTokens.live(session.id()).orElseThrow();
        String sub = Jwts.parse(director.accessToken()).claims().path("sub").asText("");
        Instant now = clock.instant();
        approvals.values().removeIf(a -> a.at().plus(TTL).isBefore(now));
        while (approvals.size() >= MAX_PENDING)
            approvals.remove(approvals.keySet().iterator().next());
        approvals.put(
                m, new Approval(m, budgetUsd, noCap, Delegations.cleanResources(resources), session.id(), sub, now));
        return "/auth/keycloak/approve?mission=" + URLEncoder.encode(m.toString(), StandardCharsets.UTF_8);
    }

    // ---- step one: the redirect to Keycloak

    /** Test hook: whether the last handler ran on a Vert.x event-loop thread (it must not). */
    public volatile boolean lastOnEventLoop;

    private void begin(RoutingContext rc) {
        lastOnEventLoop = io.vertx.core.Context.isOnEventLoopThread();
        if (!settings.enabled()) {
            reply(rc, 404, "IAM_OFF", "IAM mode is off");
            return;
        }
        Approval a = approvalOf(rc);
        if (a == null) {
            reply(rc, 401, "DELEGATION_APPROVAL_UNKNOWN", "No approval waits for this: click Approve again");
            return;
        }
        if (!PreSignIn.navigation(rc)) {
            reply(rc, 403, "NOT_A_NAVIGATION", "An approval continues from the browser's own navigation");
            return;
        }
        String state = PreSignIn.newSecret();
        Flow f = start(rc, state, a);
        rc.response()
                .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
                .putHeader(HttpHeaders.LOCATION, authorizationUrl(state, f))
                .setStatusCode(302)
                .end();
    }

    /** The flow of this click, bound to the browser by the pre-sign-in cookie it is given. */
    private Flow start(RoutingContext rc, String state, Approval a) {
        // The loopback name and this daemon's real port, never the caller's Host as sent.
        URI redirect = URI.create(LocalAccessGuard.signInBase(
                        rc.request().getHeader(HttpHeaders.HOST),
                        rc.request().localAddress().port())
                + "/auth/callback");
        String secret = PreSignIn.newSecret();
        Flow f = new Flow(
                a, PreSignIn.newSecret(), PreSignIn.newSecret(), redirect, PreSignIn.hash(secret), clock.instant());
        remember(state, f);
        PreSignIn.give(rc, state, secret);
        return f;
    }

    private synchronized void remember(String state, Flow f) {
        flows.values().removeIf(x -> x.at().plus(TTL).isBefore(clock.instant()));
        while (flows.size() >= MAX_PENDING)
            flows.remove(flows.keySet().iterator().next());
        flows.put(state, f);
    }

    /** The password is asked again (prompt=login, max_age=0) and the offline token is requested for this mission. */
    private String authorizationUrl(String state, Flow f) {
        String query = "response_type=code&client_id=" + CLIENT + "&redirect_uri="
                + OidcFlow.enc(f.redirect().toString())
                + "&scope=" + OidcFlow.enc("openid offline_access") + "&state=" + state + "&nonce=" + f.nonce()
                + "&prompt=login&max_age=0&code_challenge=" + OidcFlow.challenge(f.verifier())
                + "&code_challenge_method=S256";
        return settings.issuer().toString().replaceAll("/+$", "") + "/protocol/openid-connect/auth?" + query;
    }
    /** The pending approval of the mission named in the query, only for the browser session that clicked. */
    private synchronized Approval approvalOf(RoutingContext rc) {
        String mission = rc.request().getParam("mission");
        Cookie cookie = rc.request().getCookie(LocalAccessGuard.COOKIE);
        if (mission == null || cookie == null) return null;
        Optional<SessionRegistry.Session> session = sessions.authenticate(cookie.getValue());
        Approval a;
        try {
            a = approvals.get(new MissionId(mission));
        } catch (IllegalArgumentException e) {
            // Handled: not a mission id, so no approval
            return null;
        }
        boolean fresh = a != null && !a.at().plus(TTL).isBefore(clock.instant());
        return fresh && session.isPresent() && session.get().id().equals(a.browserSession()) ? a : null;
    }

    // ---- step two: the callback

    /** True when the state is one of ours (the callback is answered here); false: it is the sign-in's. */
    boolean callback(RoutingContext rc) {
        String state = rc.request().getParam("state");
        Flow f = take(state);
        if (f == null) return false;
        if (!PreSignIn.presented(rc, state, f.bind())) {
            reply(
                    rc,
                    401,
                    "SIGN_IN_BROWSER_UNKNOWN",
                    "This approval was not started in this browser: click Approve again");
            return true;
        }
        String code = rc.request().getParam("code");
        if (code == null || rc.request().getParam("error") != null) {
            fail(rc, f.approval(), 401, "SIGN_IN_FAILED", "Keycloak did not confirm your password");
        } else {
            finish(rc, f, code);
        }
        return true;
    }

    private void finish(RoutingContext rc, Flow f, String code) {
        Approval a = f.approval();
        if (!stillPending(a)) {
            fail(
                    rc,
                    a,
                    401,
                    "DELEGATION_APPROVAL_UNKNOWN",
                    "This approval expired or was replaced: click Approve again");
            return;
        }
        Tokens tokens = null;
        JsonNode claims = null;
        try {
            tokens = keycloak.redeemCode(code, f.verifier(), f.redirect());
            claims = claimsOf(tokens, f);
        } catch (IdpRefusedException | IdpUnavailableException e) {
            // Handled: reported as a failed sign-in below, with nothing of what Keycloak said.
            claims = null;
        }
        conclude(rc, f, tokens, claims);
    }

    /** The step-up counts only if Keycloak confirmed it, a fresh login, of the same user. */
    private void conclude(RoutingContext rc, Flow f, Tokens tokens, JsonNode claims) {
        Approval a = f.approval();
        if (claims == null || tokens.refreshToken() == null) {
            fail(rc, a, 401, "SIGN_IN_FAILED", "Keycloak did not confirm your password");
        } else if (!freshLogin(claims, f.at())) {
            fail(
                    rc,
                    a,
                    401,
                    "DELEGATION_STEP_UP_MISSING",
                    "The password was not typed again: the plan is not approved");
        } else if (!IdTokenCheck.sameSecret(a.directorSub(), claims.path("sub").asText(""))) {
            fail(rc, a, 401, "DELEGATION_WRONG_USER", "The password belongs to another user: the plan is not approved");
        } else {
            approve(rc, a, tokens);
        }
    }

    private JsonNode claimsOf(Tokens tokens, Flow f) {
        if (tokens.idToken() == null) return null;
        return new IdTokenCheck(settings.issuer(), CLIENT, jwks, clock)
                .claims(tokens.idToken(), f.nonce())
                .orElse(null);
    }

    /**
     * The authentication happened at or after the second of the redirect to Keycloak (auth_time has whole seconds, so
     * the redirect is truncated to its second; no backward skew): the password was typed again.
     */
    static boolean freshLogin(JsonNode claims, Instant redirectedAt) {
        return claims.path("auth_time").isIntegralNumber()
                && !Instant.ofEpochSecond(claims.path("auth_time").asLong())
                        .isBefore(redirectedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }
    /** The delegation is stored first, then the plan approved; if the approval fails the delegation is undone. */
    private void approve(RoutingContext rc, Approval a, Tokens tokens) {
        MissionId m = a.mission();
        try {
            delegations.grant(m, tokens, a.resources());
            approver.approve(m, a.budgetUsd(), a.noCap());
        } catch (RuntimeException e) {
            // Handled: the mission stays awaiting approval and the delegation is undone, locally first.
            delegations.revokeLocally(m);
            LOG.warn("The approval of " + m + " failed after the step-up: " + e.getMessage());
            delegations.revokeRemote(m);
            fail(rc, a, 409, "DELEGATION_APPROVAL_FAILED", String.valueOf(e.getMessage()));
            return;
        }
        LOG.info("Plan of " + m + " approved after a password step-up");
        // A page, not a 303: the cookie is SameSite=Strict, and a redirect chain started by Keycloak does not carry it.
        rc.response()
                .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
                .putHeader(HttpHeaders.CONTENT_TYPE, "text/html; charset=utf-8")
                .setStatusCode(200)
                .end("<!doctype html><meta charset=utf-8><meta http-equiv=refresh content=\"0;url=/\">"
                        + "<title>Floor42</title><a href=\"/\">Back to Floor42</a>");
    }

    /** Single use: the pending approval is consumed here, so a replay finds nothing. */
    private synchronized boolean stillPending(Approval a) {
        Approval now = approvals.get(a.mission());
        if (now != a || a.at().plus(TTL).isBefore(clock.instant())) return false;
        approvals.remove(a.mission());
        return true;
    }

    private synchronized Flow take(String state) {
        if (state == null) return null;
        Flow found = null;
        String key = null;
        for (Map.Entry<String, Flow> e : flows.entrySet()) {
            // Every entry is compared, so the time taken does not tell how much of a guess was right.
            if (IdTokenCheck.sameSecret(e.getKey(), state)) {
                found = e.getValue();
                key = e.getKey();
            }
        }
        if (found == null) return null;
        flows.remove(key);
        return found.at().plus(TTL).isBefore(clock.instant()) ? null : found;
    }

    /** A failure drops the pending approval (it is single use, whatever the outcome) and says why. */
    private void fail(RoutingContext rc, Approval a, int status, String code, String message) {
        synchronized (this) {
            approvals.remove(a.mission(), a);
        }
        reply(rc, status, code, message);
    }

    private void reply(RoutingContext rc, int status, String code, String message) {
        OidcFlow.reply(rc, json, status, code, message);
    }
}
