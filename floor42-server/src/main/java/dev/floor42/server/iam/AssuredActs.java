package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.AssurancePolicy;
import dev.floor42.domain.AssurancePolicy.Act;
import dev.floor42.domain.AssurancePolicy.Seat;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.event.DeskEvent.ApprovalSigned;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * A risky act waits here for the proof {@code AssurancePolicy} asks for (ADR-0081). The click records the pending act
 * (5 minutes, single use, bound to the browser session and to the signed-in director); Keycloak is then asked for
 * {@code acr_values=floor42-strong} with {@code prompt=login} and {@code max_age=0}. Floor42 checks the ID token
 * itself: signature, issuer, audience, nonce, a fresh {@code auth_time}, and {@code acr} equal to
 * {@code floor42-strong}, never an {@code acr} read from a query or a form. Only then is the act carried out, with the
 * signature as an event of the same command.
 *
 * <p>With four eyes the first signature waits up to 30 minutes for a countersignature: a second person signs in
 * (their own sign-in, no Floor42 session is created, nothing of the director's is touched), Floor42 reads their realm
 * roles from the access token of that code exchange and asks the authorization decision point ({@link Countersigners}).
 * The callback is the sign-in's {@code /auth/callback}: {@link StepUpApprovals} hands it the states it does not know.
 */
@ApplicationScoped
public class AssuredActs {

    private static final Logger LOG = Logger.getLogger(AssuredActs.class);
    /** From the click to the first sign-in's callback. */
    static final Duration TTL = Duration.ofMinutes(5);

    static final int MAX_PENDING = 8;
    private static final String CLIENT = "floor42-desk";
    private static final int ORDER = Integer.MIN_VALUE + 3;

    /** What is carried out once the signatures are in. It throws the refusal when the act must not happen. */
    @FunctionalInterface
    public interface Execution {
        void run(List<ApprovalSigned> signed);
    }

    /** The code flow started for one seat of a pending act. */
    private record Flow(
            PendingAct pending, Seat seat, String nonce, String verifier, URI redirect, String bind, Instant at) {}

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
    Countersigners countersigners;

    @Inject
    ObjectMapper json;

    Clock clock = Clock.systemUTC();

    private final Map<String, PendingAct> pending = new LinkedHashMap<>();
    private final Map<String, Flow> flows = new LinkedHashMap<>();

    void register(@Observes Router router) {
        router.get("/auth/keycloak/assure").order(ORDER).blockingHandler(rc -> begin(rc, Seat.FIRST), false);
        router.get("/auth/keycloak/countersign").order(ORDER).blockingHandler(rc -> begin(rc, Seat.SECOND), false);
    }

    /** True when this browser session may start an assured act: a Keycloak sign-in with live tokens, not a script. */
    public boolean mayAssure(Optional<SessionRegistry.Session> session) {
        return session.isPresent()
                && session.get().kind() == SessionRegistry.Kind.BROWSER
                && !session.get().breakGlass()
                && directorTokens.live(session.get().id()).isPresent();
    }

    /**
     * Records the pending act of this click and gives the path the UI follows. The caller has checked
     * {@link #mayAssure}.
     *
     * @param fourEyes whether a countersignature is needed after the first signature
     * @param run carried out with the signatures, once they are checked
     */
    public synchronized String record(Act act, boolean fourEyes, SessionRegistry.Session session, Execution run) {
        Tokens director = directorTokens.live(session.id()).orElseThrow();
        String sub = Jwts.parse(director.accessToken()).claims().path("sub").asText("");
        Instant now = clock.instant();
        expire(now);
        while (pending.size() >= MAX_PENDING)
            pending.remove(pending.keySet().iterator().next());
        String id = PreSignIn.newSecret();
        pending.put(id, new PendingAct(id, act, fourEyes, session.id(), sub, now, run));
        return "/auth/keycloak/assure?act=" + id;
    }

    /** The acts that wait for a second pair of eyes, oldest first. */
    public synchronized List<AssuredWaiting> waiting() {
        Instant now = clock.instant();
        expire(now);
        List<AssuredWaiting> out = new ArrayList<>();
        for (PendingAct p : pending.values()) {
            if (p.first == null) continue;
            out.add(new AssuredWaiting(
                    p.id,
                    p.act.kind().name(),
                    p.act.ref(),
                    p.first.subject().substring(0, Math.min(8, p.first.subject().length())),
                    p.firstAt.plus(AssurancePolicy.COUNTERSIGN_WINDOW),
                    "/auth/keycloak/countersign?act=" + p.id));
        }
        return out;
    }

    private void expire(Instant now) {
        pending.values()
                .removeIf(p -> p.first == null
                        ? p.at.plus(TTL).isBefore(now)
                        : p.firstAt.plus(AssurancePolicy.COUNTERSIGN_WINDOW).isBefore(now));
        flows.values().removeIf(f -> f.at().plus(TTL).isBefore(now));
    }

    // ---- step one: the redirect to Keycloak

    private void begin(RoutingContext rc, Seat seat) {
        if (!settings.enabled()) {
            reply(rc, 404, "IAM_OFF", "IAM mode is off");
            return;
        }
        PendingAct p = pendingOf(rc, seat);
        if (p == null) {
            reply(rc, 401, "STEP_UP_UNKNOWN", "No act waits for this: start it again");
            return;
        }
        if (!PreSignIn.navigation(rc)) {
            reply(rc, 403, "NOT_A_NAVIGATION", "A sign-in continues from the browser's own navigation");
            return;
        }
        String state = PreSignIn.newSecret();
        Flow f = start(rc, state, p, seat);
        rc.response()
                .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
                .putHeader(HttpHeaders.LOCATION, authorizationUrl(state, f))
                .setStatusCode(302)
                .end();
    }

    /**
     * The pending act this request names. The first seat only from the browser session that clicked; the second seat
     * only for an act whose first signature stands, by its unguessable id: it is asked from another window, with no
     * Floor42 session, and the id starts a sign-in, it signs nothing.
     */
    private synchronized PendingAct pendingOf(RoutingContext rc, Seat seat) {
        String id = rc.request().getParam("act");
        PendingAct p = id == null ? null : pending.get(id);
        Instant now = clock.instant();
        if (p == null) return null;
        if (seat == Seat.SECOND) {
            boolean open = p.first != null
                    && !p.firstAt.plus(AssurancePolicy.COUNTERSIGN_WINDOW).isBefore(now);
            return open ? p : null;
        }
        Cookie cookie = rc.request().getCookie(LocalAccessGuard.COOKIE);
        if (cookie == null || p.first != null || p.at.plus(TTL).isBefore(now)) return null;
        Optional<SessionRegistry.Session> session = sessions.authenticate(cookie.getValue());
        return session.isPresent() && session.get().id().equals(p.browserSession) ? p : null;
    }

    private Flow start(RoutingContext rc, String state, PendingAct p, Seat seat) {
        URI redirect = URI.create(LocalAccessGuard.signInBase(
                        rc.request().getHeader(HttpHeaders.HOST),
                        rc.request().localAddress().port())
                + "/auth/callback");
        String secret = PreSignIn.newSecret();
        Flow f = new Flow(
                p,
                seat,
                PreSignIn.newSecret(),
                PreSignIn.newSecret(),
                redirect,
                PreSignIn.hash(secret),
                clock.instant());
        synchronized (this) {
            expire(clock.instant());
            while (flows.size() >= MAX_PENDING)
                flows.remove(flows.keySet().iterator().next());
            flows.put(state, f);
        }
        PreSignIn.give(rc, state, secret);
        return f;
    }

    /**
     * The password is asked again and the second factor with it ({@code acr_values=floor42-strong}, a request: Floor42
     * checks the answer). No {@code login_hint}: the second seat is someone else, and the first is asked their name.
     */
    private String authorizationUrl(String state, Flow f) {
        String query = "response_type=code&client_id=" + CLIENT + "&redirect_uri="
                + OidcFlow.enc(f.redirect().toString()) + "&scope=openid&state=" + state + "&nonce=" + f.nonce()
                + "&prompt=login&max_age=0&acr_values=" + AssurancePolicy.ACR_STRONG + "&code_challenge="
                + OidcFlow.challenge(f.verifier()) + "&code_challenge_method=S256";
        return settings.issuer().toString().replaceAll("/+$", "") + "/protocol/openid-connect/auth?" + query;
    }

    // ---- step two: the callback

    /** True when the state is one of ours (the callback is answered here); false: it is the sign-in's. */
    boolean callback(RoutingContext rc) {
        Flow f = take(rc.request().getParam("state"));
        if (f == null) return false;
        String state = rc.request().getParam("state");
        if (!PreSignIn.presented(rc, state, f.bind())) {
            reply(rc, 401, "SIGN_IN_BROWSER_UNKNOWN", "This sign-in was not started in this browser: start it again");
            return true;
        }
        String code = rc.request().getParam("code");
        if (code == null || rc.request().getParam("error") != null) {
            fail(rc, f, 401, "SIGN_IN_FAILED", "Keycloak did not complete the sign-in");
            return true;
        }
        Tokens tokens = redeem(f, code);
        JsonNode claims = tokens == null || tokens.idToken() == null
                ? null
                : checker().claims(tokens.idToken(), f.nonce()).orElse(null);
        if (claims == null) {
            fail(rc, f, 401, "SIGN_IN_FAILED", "Keycloak did not confirm the sign-in");
        } else {
            conclude(rc, f, tokens, claims);
        }
        return true;
    }

    /** The tokens of the code, or null: a failed exchange is a failed sign-in, with nothing of what Keycloak said. */
    private Tokens redeem(Flow f, String code) {
        try {
            return keycloak.redeemCode(code, f.verifier(), f.redirect());
        } catch (IdpRefusedException | IdpUnavailableException e) {
            // Handled: reported as a failed sign-in by the caller.
            return null;
        }
    }

    private IdTokenCheck checker() {
        return new IdTokenCheck(settings.issuer(), CLIENT, jwks, clock);
    }

    /** The signature counts only if Keycloak confirmed it: the strong level, a fresh login, the right person. */
    private void conclude(RoutingContext rc, Flow f, Tokens tokens, JsonNode claims) {
        PendingAct p = f.pending();
        String sub = claims.path("sub").asText("");
        String acr = claims.path("acr").asText("");
        if (!AssurancePolicy.ACR_STRONG.equals(acr)) {
            fail(
                    rc,
                    f,
                    401,
                    "ASSURANCE_TOO_LOW",
                    "The sign-in did not reach the strong level: the second factor is missing");
        } else if (!StepUpApprovals.freshLogin(claims, f.at())) {
            fail(rc, f, 401, "STEP_UP_STALE", "The sign-in was not typed again: nothing is approved");
        } else if (f.seat() == Seat.FIRST && !IdTokenCheck.sameSecret(p.directorSub, sub)) {
            fail(rc, f, 401, "STEP_UP_WRONG_USER", "The sign-in belongs to another user: nothing is approved");
        } else if (f.seat() == Seat.FIRST && !checker().director(tokens.accessToken(), sub)) {
            fail(rc, f, 401, "DESK_DIRECTOR_ONLY", "Only the director may approve this act: nothing is approved");
        } else if (f.seat() == Seat.FIRST) {
            first(rc, f, signature(p, Seat.FIRST, sub, acr, claims));
        } else {
            second(rc, f, tokens, signature(p, Seat.SECOND, sub, acr, claims));
        }
    }

    private static ApprovalSigned signature(PendingAct p, Seat seat, String sub, String acr, JsonNode claims) {
        String jti = claims.path("jti").asText("");
        return new ApprovalSigned(
                p.act, seat, sub, acr, claims.path("auth_time").asLong(), jti.isBlank() ? null : jti);
    }

    private void first(RoutingContext rc, Flow f, ApprovalSigned signed) {
        PendingAct p = f.pending();
        synchronized (this) {
            if (pending.get(p.id) != p || p.first != null || p.at.plus(TTL).isBefore(clock.instant())) {
                reply(rc, 401, "STEP_UP_UNKNOWN", "This act expired or was replaced: start it again");
                return;
            }
            if (p.fourEyes) {
                p.first = signed;
                p.firstAt = clock.instant();
            } else {
                pending.remove(p.id);
            }
        }
        if (p.fourEyes) {
            LOG.info("A first signature waits for a second one");
            ActPages.send(rc, "A second signature is needed: it waits on the desk.", true);
        } else {
            carryOut(rc, f, List.of(signed), true);
        }
    }

    /**
     * The second person: a different subject, holding the reviewer role (the access token of this exchange), and
     * allowed by the decision point. Their sign-in creates nothing in Floor42 and does not change who the director is.
     */
    private void second(RoutingContext rc, Flow f, Tokens tokens, ApprovalSigned signed) {
        PendingAct p = f.pending();
        ApprovalSigned first = firstOf(p);
        if (first == null || p.firstAt.plus(AssurancePolicy.COUNTERSIGN_WINDOW).isBefore(clock.instant())) {
            reply(rc, 401, "SIGNATURE_EXPIRED", "The first signature expired: the act is not approved");
            return;
        }
        if (first.subject().equals(signed.subject())) {
            reply(rc, 403, "SAME_PERSON", "The second signature must be another person than the first");
            return;
        }
        Optional<java.util.Set<String>> roles = tokens.accessToken() == null
                ? Optional.empty()
                : checker().realmRoles(tokens.accessToken(), signed.subject());
        Countersigners.Refusal no = countersigners.refusal(roles, signed.subject(), first.subject());
        if (no != null) {
            reply(rc, no.status(), no.code(), no.message());
            return;
        }
        if (!consume(p, first)) {
            reply(rc, 401, "STEP_UP_UNKNOWN", "This act was already carried out or replaced");
            return;
        }
        carryOut(rc, f, List.of(first, signed), false);
    }

    private synchronized boolean consume(PendingAct p, ApprovalSigned first) {
        return pending.get(p.id) == p && p.first == first && pending.remove(p.id) != null;
    }

    private synchronized ApprovalSigned firstOf(PendingAct p) {
        return pending.get(p.id) == p ? p.first : null;
    }

    private void carryOut(RoutingContext rc, Flow f, List<ApprovalSigned> signed, boolean toDesk) {
        try {
            f.pending().run.run(signed);
        } catch (AssuredFailure e) {
            // Handled: the act's own refusal is the answer.
            reply(rc, e.status(), e.code(), e.getMessage());
            return;
        } catch (DomainException e) {
            // Handled: the domain said no, so the act is not carried out; the code is logged.
            LOG.warnf("An assured act was refused by the domain: %s", e.code());
            reply(rc, 409, e.code(), e.getMessage());
            return;
        } catch (RuntimeException e) {
            // Handled: the act is not carried out and the pending one is already consumed.
            LOG.warn("An assured act failed after its signatures: " + e.getMessage());
            reply(rc, 409, "ASSURED_ACT_FAILED", "The act could not be carried out");
            return;
        }
        LOG.infof(
                "An assured act (%s) was carried out with %d signature(s)",
                f.pending().act.kind(), signed.size());
        if (toDesk) {
            ActPages.send(rc, "Done.", true);
        } else {
            ActPages.send(rc, "Countersigned. You can close this window.", false);
        }
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

    /** A failure of the first seat drops the act (single use); one of the second leaves the first signature standing. */
    private void fail(RoutingContext rc, Flow f, int status, String code, String message) {
        if (f.seat() == Seat.FIRST) {
            synchronized (this) {
                pending.remove(f.pending().id, f.pending());
            }
        }
        reply(rc, status, code, message);
    }

    private void reply(RoutingContext rc, int status, String code, String message) {
        OidcFlow.reply(rc, json, status, code, message);
    }
}
