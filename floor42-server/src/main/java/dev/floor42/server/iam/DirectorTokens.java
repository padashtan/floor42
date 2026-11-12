package dev.floor42.server.iam;

import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.security.SessionRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Keycloak tokens of the director's browser sessions, in memory only and never shown. A session that no longer
 * lives in the registry (sign-out, revocation, "sign out everywhere") loses its tokens the next time they are asked for.
 */
@ApplicationScoped
public class DirectorTokens {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(DirectorTokens.class);
    private static final Duration REFRESH_BEFORE = Duration.ofSeconds(30);
    private static final String DESK = "floor42-desk";

    @Inject
    SessionRegistry sessions;

    @Inject
    KeycloakClient keycloak;

    private final Map<String, Tokens> held = new ConcurrentHashMap<>();

    public void put(String browserSessionId, Tokens tokens) {
        held.put(browserSessionId, tokens);
    }

    public void remove(String browserSessionId) {
        held.remove(browserSessionId);
    }

    /**
     * Sign-out: drops this browser's tokens and ends its Keycloak session with the refresh token (RFC 7009 style, best
     * effort). A Keycloak that cannot be reached never blocks the local sign-out.
     */
    public void signOut(String browserSessionId) {
        Tokens t = held.remove(browserSessionId);
        if (t == null || t.refreshToken() == null) return;
        try {
            keycloak.logout(t.refreshToken());
        } catch (RuntimeException e) {
            // Handled: logged, the local sign-out has already happened and stands.
            LOG.warn("Keycloak did not end the session of a signed-out browser: "
                    + e.getClass().getSimpleName());
        }
    }

    /** The tokens of this browser session, refreshed when they expire within 30 s; empty when none are usable. */
    public synchronized Optional<Tokens> live(String browserSessionId) {
        Tokens t = held.get(browserSessionId);
        if (t == null) return Optional.empty();
        if (sessions.find(browserSessionId).isEmpty()) {
            held.remove(browserSessionId);
            return Optional.empty();
        }
        if (t.expiresAt().minus(REFRESH_BEFORE).isAfter(Instant.now())) return Optional.of(t);
        return refreshed(browserSessionId, t);
    }

    /**
     * When this browser's Keycloak login stops being usable: the refresh token's own expiry (its {@code exp}), else the
     * access token's. Only a date, never a token. Empty when the session holds no Keycloak login.
     */
    public Optional<Instant> loginEndsAt(String browserSessionId) {
        Tokens t = held.get(browserSessionId);
        if (t == null) return Optional.empty();
        try {
            long exp = Jwts.parse(t.refreshToken()).claims().path("exp").asLong(0);
            if (exp > 0) return Optional.of(Instant.ofEpochSecond(exp));
        } catch (IllegalArgumentException e) {
            // Handled: an opaque refresh token has no readable expiry; the access token's stands in.
        }
        return Optional.of(t.expiresAt());
    }

    /** The freshest live director login, for sessions the daemon plans itself. */
    public Optional<Tokens> any() {
        return held.keySet().stream()
                .map(this::live)
                .flatMap(Optional::stream)
                .max(Comparator.comparing(Tokens::expiresAt));
    }

    private Optional<Tokens> refreshed(String id, Tokens old) {
        if (old.refreshToken() == null) {
            held.remove(id);
            return Optional.empty();
        }
        try {
            Tokens n = keycloak.refresh(old.refreshToken(), DESK);
            Tokens kept = new Tokens(
                    n.accessToken(),
                    n.refreshToken() != null ? n.refreshToken() : old.refreshToken(),
                    n.idToken() != null ? n.idToken() : old.idToken(),
                    n.expiresAt());
            held.put(id, kept);
            return Optional.of(kept);
        } catch (IdpRefusedException e) {
            // Handled: Keycloak ended this login (revoked, expired); the director signs in again.
            held.remove(id);
            return Optional.empty();
        } catch (IdpUnavailableException e) {
            // Handled: Keycloak is unreachable; the tokens stay for the next try, unusable now (fail closed).
            return Optional.empty();
        }
    }
}
