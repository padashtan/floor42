package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.AssurancePolicy;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * A script token bound to the director's Keycloak session (ADR-0083) is issued after a step-up, as a plan approval is
 * (ADR-0064 point 2): the click records a pending issue (5 minutes, single use, bound to the browser session and to the
 * signed-in director); the director then types the Keycloak password again ({@code prompt=login}, {@code max_age=0},
 * {@code acr_values=floor42-strong}, {@code offline_access}: issuing a token is a STRONG act, {@code ActKind.SCRIPT_TOKEN}
 * of ADR-0081), which opens a Keycloak session of its own for this token. Only when the id_token proves a
 * fresh authentication of the same user is the token issued, and the page that answers shows it once. Any failure
 * drops the pending issue and issues nothing. The callback is the sign-in's {@code /auth/callback} (the only redirect
 * URI the realm lists): {@link KeycloakSignIn} hands it the states it does not know.
 */
@ApplicationScoped
public class BoundTokenIssuance {

    private static final Logger LOG = Logger.getLogger(BoundTokenIssuance.class);
    static final Duration TTL = Duration.ofMinutes(5);
    static final int MAX_PENDING = 5;
    private static final String CLIENT = "floor42-desk";
    private static final int ORDER = Integer.MIN_VALUE + 2;
    static final String PATH = "/auth/keycloak/bind";

    private record Pending(BoundTokenIssuer.Request request, String browserSession, String directorSub, Instant at) {}

    private record Flow(Pending pending, String nonce, String verifier, URI redirect, String bind, Instant at) {}

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
    StepUpApprovals stepUp;

    @Inject
    BoundTokenIssuer issuer;

    @Inject
    ObjectMapper json;

    Clock clock = Clock.systemUTC();

    /** By browser session: a second click replaces the first. */
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    private final Map<String, Flow> flows = new LinkedHashMap<>();

    void register(@Observes Router router) {
        router.get(PATH).order(ORDER).blockingHandler(this::begin, false);
    }

    /** Whether this browser session may ask for a bound token: the plan approval's own condition. */
    public boolean mayAsk(Optional<SessionRegistry.Session> session) {
        return stepUp.mayApprove(session);
    }

    /** Records the pending issue of this click and gives the path the UI follows. The caller has checked {@link #mayAsk}. */
    public synchronized String record(BoundTokenIssuer.Request request, SessionRegistry.Session session) {
        Tokens director = directorTokens.live(session.id()).orElseThrow();
        String sub = Jwts.parse(director.accessToken()).claims().path("sub").asText("");
        Instant now = clock.instant();
        pending.values().removeIf(p -> p.at().plus(TTL).isBefore(now));
        while (pending.size() >= MAX_PENDING)
            pending.remove(pending.keySet().iterator().next());
        pending.put(session.id(), new Pending(request, session.id(), sub, now));
        return PATH;
    }

    // ---- step one: the redirect to Keycloak

    private void begin(RoutingContext rc) {
        if (!settings.enabled()) {
            reply(rc, 404, "IAM_OFF", "IAM mode is off");
            return;
        }
        Pending p = pendingOf(rc);
        if (p == null) {
            reply(rc, 401, "BOUND_TOKEN_UNKNOWN", "No token waits for this: ask for it again");
            return;
        }
        if (!PreSignIn.navigation(rc)) {
            reply(rc, 403, "NOT_A_NAVIGATION", "A bound token continues from the browser's own navigation");
            return;
        }
        String state = PreSignIn.newSecret();
        Flow f = start(rc, state, p);
        rc.response()
                .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
                .putHeader(HttpHeaders.LOCATION, authorizationUrl(state, f))
                .setStatusCode(302)
                .end();
    }

    /** The flow of this request, bound to the browser by the pre-sign-in cookie it is given. */
    private Flow start(RoutingContext rc, String state, Pending p) {
        // The loopback name and this daemon's real port, never the caller's Host as sent.
        URI redirect = URI.create(LocalAccessGuard.signInBase(
                        rc.request().getHeader(HttpHeaders.HOST),
                        rc.request().localAddress().port())
                + "/auth/callback");
        String secret = PreSignIn.newSecret();
        Flow f = new Flow(
                p, PreSignIn.newSecret(), PreSignIn.newSecret(), redirect, PreSignIn.hash(secret), clock.instant());
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

    /** The password is asked again (prompt=login, max_age=0) and an offline token is requested for this script token. */
    private String authorizationUrl(String state, Flow f) {
        String query = "response_type=code&client_id=" + CLIENT + "&redirect_uri="
                + OidcFlow.enc(f.redirect().toString())
                + "&scope=" + OidcFlow.enc("openid offline_access") + "&state=" + state + "&nonce=" + f.nonce()
                + "&prompt=login&max_age=0&acr_values=" + AssurancePolicy.ACR_STRONG + "&code_challenge="
                + OidcFlow.challenge(f.verifier())
                + "&code_challenge_method=S256";
        return settings.issuer().toString().replaceAll("/+$", "") + "/protocol/openid-connect/auth?" + query;
    }

    /** The pending issue of the browser session that clicked, only while it is fresh. */
    private synchronized Pending pendingOf(RoutingContext rc) {
        Cookie cookie = rc.request().getCookie(LocalAccessGuard.COOKIE);
        if (cookie == null) return null;
        Optional<SessionRegistry.Session> session = sessions.authenticate(cookie.getValue());
        if (session.isEmpty()) return null;
        Pending p = pending.get(session.get().id());
        return p != null && !p.at().plus(TTL).isBefore(clock.instant()) ? p : null;
    }

    // ---- step two: the callback

    /** True when the state is one of ours (the callback is answered here); false: it is the sign-in's. */
    boolean callback(RoutingContext rc) {
        Flow f = take(rc.request().getParam("state"));
        if (f == null) return false;
        if (!PreSignIn.presented(rc, rc.request().getParam("state"), f.bind())) {
            reply(rc, 401, "SIGN_IN_BROWSER_UNKNOWN", "This was not started in this browser: ask for the token again");
            return true;
        }
        String code = rc.request().getParam("code");
        if (code == null || rc.request().getParam("error") != null) {
            fail(rc, f.pending(), 401, "SIGN_IN_FAILED", "Keycloak did not confirm your password");
        } else {
            finish(rc, f, code);
        }
        return true;
    }

    private void finish(RoutingContext rc, Flow f, String code) {
        Pending p = f.pending();
        if (!stillPending(p)) {
            fail(rc, p, 401, "BOUND_TOKEN_UNKNOWN", "This request expired or was replaced: ask for the token again");
            return;
        }
        Tokens tokens = null;
        JsonNode claims = null;
        try {
            tokens = keycloak.redeemCode(code, f.verifier(), f.redirect());
            claims = tokens.idToken() == null
                    ? null
                    : new IdTokenCheck(settings.issuer(), CLIENT, jwks, clock)
                            .claims(tokens.idToken(), f.nonce())
                            .orElse(null);
        } catch (IdpRefusedException | IdpUnavailableException e) {
            // Handled: reported as a failed sign-in below, with nothing of what Keycloak said.
            claims = null;
        }
        String[] no = proofRefusal(f, tokens, claims);
        if (no == null) {
            issue(rc, p, tokens, claims);
            return;
        }
        if (tokens != null && tokens.refreshToken() != null) discard(tokens);
        fail(rc, p, 401, no[0], no[1]);
    }

    /** Why the id token is not the proof asked for (code, message), or null when it is. */
    private static String[] proofRefusal(Flow f, Tokens tokens, JsonNode claims) {
        Pending p = f.pending();
        if (claims == null || tokens.refreshToken() == null)
            return new String[] {"SIGN_IN_FAILED", "Keycloak did not confirm your password"};
        if (!AssurancePolicy.ACR_STRONG.equals(claims.path("acr").asText("")))
            return new String[] {"ASSURANCE_TOO_LOW", "The sign-in did not reach the strong level: no token was issued"
            };
        if (!StepUpApprovals.freshLogin(claims, f.at()))
            return new String[] {"BOUND_TOKEN_STEP_UP_MISSING", "The password was not typed again: no token was issued"
            };
        if (!IdTokenCheck.sameSecret(p.directorSub(), claims.path("sub").asText("")))
            return new String[] {"BOUND_TOKEN_WRONG_USER", "The password belongs to another user: no token was issued"};
        return null;
    }

    private void issue(RoutingContext rc, Pending p, Tokens tokens, JsonNode claims) {
        String subject = claims.path("sub").asText("");
        BoundTokenIssuer.Issued issued;
        try {
            issued = issuer.issue(
                    p.request(), subject, tokens, proof(claims), rc.request().getHeader(HttpHeaders.USER_AGENT));
        } catch (RuntimeException e) {
            // Handled: nothing is issued, and the Keycloak session this step-up opened is ended.
            discard(tokens);
            LOG.warn("A bound script token could not be issued: " + e.getClass().getSimpleName());
            reply(rc, 409, "BOUND_TOKEN_FAILED", "The token could not be issued");
            return;
        }
        LOG.info("Bound script token " + issued.id() + " issued after a password step-up");
        rc.response()
                .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
                .putHeader("Referrer-Policy", "no-referrer")
                .putHeader(HttpHeaders.CONTENT_TYPE, "text/html; charset=utf-8")
                .setStatusCode(200)
                .end(page(issued));
    }

    private static BoundTokenIssuer.Proof proof(JsonNode claims) {
        String jti = claims.path("jti").asText("");
        return new BoundTokenIssuer.Proof(
                claims.path("acr").asText(""), claims.path("auth_time").asLong(), jti.isBlank() ? null : jti);
    }

    /** Shown once. The secret is random base64url: escaped all the same. */
    static String page(BoundTokenIssuer.Issued issued) {
        return "<!doctype html><meta charset=utf-8><title>Floor42</title>"
                + "<h1>Script token, bound to your Keycloak session</h1>"
                + "<p>Copy it now: it is shown once and Floor42 keeps only its hash. It is valid until "
                + escape(issued.expires()) + " (scope " + escape(issued.scope()) + "), or until you revoke it.</p>"
                + "<p><code id=token>" + escape(issued.secret()) + "</code></p>"
                + "<p><a href=\"/\">Back to Floor42</a></p>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** The Keycloak session a failed or refused step-up opened is not kept. */
    private void discard(Tokens tokens) {
        try {
            keycloak.revoke(tokens.refreshToken());
        } catch (IdpRefusedException | IdpUnavailableException e) {
            // Handled: logged; the session expires by itself in Keycloak, and nothing here holds its token.
            LOG.warn("An unused offline session could not be revoked: "
                    + e.getClass().getSimpleName());
        }
    }

    /** Single use: the pending issue is consumed here, so a replay finds nothing. */
    private synchronized boolean stillPending(Pending p) {
        Pending now = pending.get(p.browserSession());
        if (now != p || p.at().plus(TTL).isBefore(clock.instant())) return false;
        pending.remove(p.browserSession());
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

    /** A failure drops the pending issue (single use, whatever the outcome) and says why. */
    private void fail(RoutingContext rc, Pending p, int status, String code, String message) {
        synchronized (this) {
            pending.remove(p.browserSession(), p);
        }
        reply(rc, status, code, message);
    }

    private void reply(RoutingContext rc, int status, String code, String message) {
        OidcFlow.reply(rc, json, status, code, message);
    }
}
