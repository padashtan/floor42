package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.security.ConsoleSignIn;
import dev.floor42.server.security.LocalAccessGuard;
import dev.floor42.server.security.SessionRegistry;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * The director's sign-in through Keycloak: Authorization Code with PKCE (S256) and a nonce, on the public client
 * {@code floor42-desk}. The routes sit outside {@code /api}, so the transport guard (loopback Host, no proxy) still
 * runs before them. State and nonce are single use; at most 32 logins wait, the oldest pushed out first, for 5 minutes. Nothing secret is logged
 * or put in a URL except the code and state Keycloak itself returns.
 */
@ApplicationScoped
public class KeycloakSignIn {

    private static final Logger LOG = Logger.getLogger(KeycloakSignIn.class);
    static final int MAX_PENDING = 32;
    static final Duration PENDING_TTL = Duration.ofMinutes(5);
    private static final String CLIENT = "floor42-desk";
    private static final int ORDER = Integer.MIN_VALUE + 2;

    private record Pending(String state, String nonce, String verifier, URI redirect, String bind, Instant at) {}

    @Inject
    IamSettings settings;

    @Inject
    KeycloakClient keycloak;

    @Inject
    Jwks jwks;

    @Inject
    SessionRegistry sessions;

    @Inject
    OfficeFacts office;

    @Inject
    DirectorTokens directorTokens;

    @Inject
    ObjectMapper json;

    @Inject
    StepUpApprovals stepUp;

    Clock clock = Clock.systemUTC();

    private final Map<String, Pending> pending = new LinkedHashMap<>();

    /** Test seam: forgets every pending sign-in. */
    synchronized void clearPending() {
        pending.clear();
    }

    void register(@Observes Router router) {
        router.get("/auth/keycloak/enabled").order(ORDER).handler(this::enabled);
        // Blocking: the handlers call Keycloak and the vault, never on the event loop.
        router.get("/auth/keycloak").order(ORDER).blockingHandler(this::begin, false);
        router.get("/auth/callback").order(ORDER).blockingHandler(this::callback, false);
    }

    void onConsoleSignIn(@Observes ConsoleSignIn signIn) {
        if (!settings.enabled() || signIn.session() == null) return;
        LOG.warn("Director signed in with the console link (break-glass)");
        office.breakGlassSignIn(signIn.session());
    }

    private void enabled(RoutingContext rc) {
        if (!settings.enabled()) iamOff(rc);
        else rc.response().setStatusCode(204).end();
    }

    /** Test hook: whether the last sign-in handler ran on a Vert.x event-loop thread (it must not). */
    volatile boolean lastOnEventLoop;

    private void begin(RoutingContext rc) {
        lastOnEventLoop = io.vertx.core.Context.isOnEventLoopThread();
        if (!settings.enabled()) {
            iamOff(rc);
            return;
        }
        if (!PreSignIn.navigation(rc)) {
            reply(rc, 403, "NOT_A_NAVIGATION", "A sign-in starts from the browser's own address bar or a link");
            return;
        }
        // The loopback name and this daemon's real port, never the caller's Host as sent.
        URI redirect = URI.create(LocalAccessGuard.signInBase(
                        rc.request().getHeader(HttpHeaders.HOST),
                        rc.request().localAddress().port())
                + "/auth/callback");
        String secret = PreSignIn.newSecret();
        Pending p = remember(redirect, PreSignIn.hash(secret), PreSignIn.held(rc));
        PreSignIn.give(rc, p.state(), secret);
        String challenge = OidcFlow.challenge(p.verifier());
        String query = "response_type=code&client_id=" + CLIENT + "&redirect_uri=" + OidcFlow.enc(redirect.toString())
                + "&scope=openid&state=" + p.state() + "&nonce=" + p.nonce() + "&code_challenge=" + challenge
                + "&code_challenge_method=S256";
        rc.response()
                .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
                .putHeader(
                        HttpHeaders.LOCATION,
                        settings.issuer().toString().replaceAll("/+$", "") + "/protocol/openid-connect/auth?" + query)
                .setStatusCode(302)
                .end();
    }

    /**
     * A new start of the same browser replaces its own pending login; a full table pushes the oldest out (a flood must
     * keep flooding, and it is logged).
     */
    private synchronized Pending remember(URI redirect, String bind, java.util.Set<String> ownBinds) {
        Instant now = clock.instant();
        pending.values().removeIf(p -> p.at().plus(PENDING_TTL).isBefore(now) || ownBinds.contains(p.bind()));
        for (Iterator<String> oldest = pending.keySet().iterator(); pending.size() >= MAX_PENDING; ) {
            oldest.next();
            oldest.remove();
            LOG.warn("Too many pending sign-ins: the oldest was dropped");
        }
        Pending p =
                new Pending(PreSignIn.newSecret(), PreSignIn.newSecret(), PreSignIn.newSecret(), redirect, bind, now);
        pending.put(p.state(), p);
        return p;
    }

    /** The pending login of this state, removed whatever happens next: a state is used once. */
    private synchronized Pending take(String state) {
        if (state == null) return null;
        Instant now = clock.instant();
        Pending found = null;
        for (Pending p : pending.values()) {
            // Every entry is compared, so the time taken does not tell how much of a guess was right.
            if (IdTokenCheck.sameSecret(p.state(), state)) found = p;
        }
        if (found == null) return null;
        pending.remove(found.state());
        return found.at().plus(PENDING_TTL).isBefore(now) ? null : found;
    }

    private void callback(RoutingContext rc) {
        lastOnEventLoop = io.vertx.core.Context.isOnEventLoopThread();
        if (!settings.enabled()) {
            iamOff(rc);
            return;
        }
        if (stepUp.callback(rc)) return;
        Pending p = take(rc.request().getParam("state"));
        if (p == null) {
            refuse(rc, "SIGN_IN_STATE_UNKNOWN", "This sign-in is unknown, expired or already used: start again");
            return;
        }
        if (!PreSignIn.presented(rc, p.state(), p.bind())) {
            refuse(rc, "SIGN_IN_BROWSER_UNKNOWN", "This sign-in was not started in this browser: start again");
            return;
        }
        String code = rc.request().getParam("code");
        Tokens tokens = code == null || rc.request().getParam("error") != null ? null : redeem(code, p);
        if (tokens == null) refuse(rc, "SIGN_IN_FAILED", "Keycloak did not sign you in");
        else open(rc, tokens);
    }

    private void open(RoutingContext rc, Tokens tokens) {
        String userAgent = rc.request().getHeader(HttpHeaders.USER_AGENT);
        SessionRegistry.Issued issued = sessions.openBrowser(userAgent);
        directorTokens.put(issued.session().id(), tokens);
        LOG.info("Director signed in through Keycloak");
        rc.response()
                .addCookie(LocalAccessGuard.sessionCookie(issued.secret()))
                .putHeader(HttpHeaders.LOCATION, "/")
                .setStatusCode(303)
                .end();
    }

    private Tokens redeem(String code, Pending p) {
        try {
            Tokens t = keycloak.redeemCode(code, p.verifier(), p.redirect());
            IdTokenCheck check = new IdTokenCheck(settings.issuer(), CLIENT, jwks, clock);
            return t.idToken() != null && check.valid(t.idToken(), p.nonce()) ? t : null;
        } catch (IdpRefusedException | IdpUnavailableException e) {
            // Handled: reported as SIGN_IN_FAILED, with nothing of what Keycloak said.
            return null;
        }
    }

    private void iamOff(RoutingContext rc) {
        reply(rc, 404, "IAM_OFF", "IAM mode is off");
    }

    private void refuse(RoutingContext rc, String code, String message) {
        reply(rc, 401, code, message);
    }

    private void reply(RoutingContext rc, int status, String code, String message) {
        OidcFlow.reply(rc, json, status, code, message);
    }
}
