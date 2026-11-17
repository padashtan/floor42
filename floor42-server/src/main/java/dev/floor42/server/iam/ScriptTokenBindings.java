package dev.floor42.server.iam;

import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.security.SessionRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import org.jboss.logging.Logger;

/**
 * Script tokens bound to a Keycloak session (ADR-0083). A bound token rests on its own offline session, taken at a
 * password step-up: its refresh token sits in the vault under the token's id and is never shown, to the script or
 * anyone. On a use of the token {@link #live} refreshes it at most once every 60 s; Keycloak refusing the refresh
 * (session revoked, user disabled, offline lifetime reached) marks the token dead for good, with no further call.
 * Keycloak unreachable is {@link Liveness#UNAVAILABLE}: the guard refuses, never allows. The access token a refresh
 * returns is dropped at once.
 *
 * <p>Everything else is memory: script tokens die with the daemon, so at start the offline sessions a previous run left
 * (listed in a vault index) are revoked at Keycloak, and a token that left the registry (expired, revoked, signed out
 * everywhere) loses its offline session within a minute.
 */
@ApplicationScoped
public class ScriptTokenBindings {

    private static final Logger LOG = Logger.getLogger(ScriptTokenBindings.class);
    /** The only binding there is: what the journal records in {@code ApiTokenIssued.binding}. */
    public static final String BINDING = "KEYCLOAK_OFFLINE_SESSION";

    static final Duration CHECK_EVERY = Duration.ofSeconds(60);
    /** After Keycloak failed to answer, the token is refused without another call for this long. */
    static final Duration RETRY_AFTER = Duration.ofSeconds(5);

    private static final String DESK = "floor42-desk";
    private static final String INDEX = "scriptbind-index";

    /** What the guard learns about a token without waiting for Keycloak, or after it did. */
    public enum Liveness {
        /** Not a bound token. */
        UNBOUND,
        /** Bound and its session answered less than 60 s ago. */
        LIVE,
        /** Bound, last answer older than 60 s: {@link #live} must ask Keycloak (it blocks). */
        STALE,
        /** Keycloak ended the session, or the token was revoked: refused for good. */
        DEAD,
        /** Keycloak did not answer: refused for now. */
        UNAVAILABLE
    }

    private static final class Bound {
        final String subject;
        /** One refresh at a time; a use that cannot get it in a second is refused (a stalled Keycloak). */
        final ReentrantLock refresh = new ReentrantLock();

        volatile Instant checkedAt;
        volatile Instant failedAt;
        volatile boolean dead;

        Bound(String subject, Instant checkedAt) {
            this.subject = subject;
            this.checkedAt = checkedAt;
        }
    }

    private final SecretVault vault;
    private final KeycloakClient keycloak;
    private final Predicate<String> open;
    private final Map<String, Bound> bound = new ConcurrentHashMap<>();

    @Inject
    Vertx vertx;

    private long timer;

    Clock clock = Clock.systemUTC();

    @Inject
    ScriptTokenBindings(SecretVault vault, KeycloakClient keycloak, SessionRegistry sessions) {
        this(vault, keycloak, id -> sessions.find(id).isPresent());
    }

    /** {@code open}: whether the script token is still one of the registry's. */
    ScriptTokenBindings(SecretVault vault, KeycloakClient keycloak, Predicate<String> open) {
        this.vault = vault;
        this.keycloak = keycloak;
        this.open = open;
    }

    public static String entry(String tokenId) {
        return "scriptbind-" + tokenId.toLowerCase(java.util.Locale.ROOT);
    }

    /** Keeps the offline token of this script token, in the vault only. The first check counts as made now. */
    public synchronized void bind(String tokenId, Tokens offline, String subject) {
        if (offline == null || offline.refreshToken() == null || subject == null || subject.isBlank())
            throw new IllegalArgumentException("A binding needs an offline refresh token and a subject");
        vault.put(entry(tokenId), offline.refreshToken().getBytes(StandardCharsets.UTF_8));
        bound.put(tokenId, new Bound(subject, clock.instant()));
        writeIndex();
    }

    /** The Keycloak {@code sub} this token is bound to. */
    public Optional<String> subject(String tokenId) {
        Bound b = bound.get(tokenId);
        return b == null ? Optional.empty() : Optional.of(b.subject);
    }

    /** No call, no wait: safe on the event loop. */
    public Liveness peek(String tokenId) {
        Bound b = tokenId == null ? null : bound.get(tokenId);
        if (b == null) return Liveness.UNBOUND;
        if (b.dead) return Liveness.DEAD;
        Instant failed = b.failedAt;
        if (failed != null && failed.plus(RETRY_AFTER).isAfter(clock.instant())) return Liveness.UNAVAILABLE;
        return b.checkedAt.plus(CHECK_EVERY).isAfter(clock.instant()) ? Liveness.LIVE : Liveness.STALE;
    }

    /**
     * Asks Keycloak when the last answer is older than 60 s; concurrent uses wait for the one call. Blocks: never on
     * the event loop.
     */
    public Liveness live(String tokenId) {
        Bound b = bound.get(tokenId);
        if (b == null) return Liveness.UNBOUND;
        try {
            if (!b.refresh.tryLock(1, TimeUnit.SECONDS)) return Liveness.UNAVAILABLE;
        } catch (InterruptedException e) {
            // Handled: the flag is restored and the answer is UNAVAILABLE, never bound.
            Thread.currentThread().interrupt();
            return Liveness.UNAVAILABLE;
        }
        try {
            Liveness seen = peek(tokenId);
            return seen == Liveness.STALE ? refresh(tokenId, b) : seen;
        } finally {
            b.refresh.unlock();
        }
    }

    private Liveness refresh(String tokenId, Bound b) {
        Optional<String> stored = token(tokenId);
        if (stored.isEmpty()) {
            b.dead = true;
            return Liveness.DEAD;
        }
        try {
            Tokens fresh = keycloak.refresh(stored.get(), DESK);
            String current = fresh.refreshToken() != null ? fresh.refreshToken() : stored.get();
            if (!keep(tokenId, b, stored.get(), current)) return Liveness.DEAD;
            b.failedAt = null;
            b.checkedAt = clock.instant();
            return Liveness.LIVE;
        } catch (IdpRefusedException e) {
            // Handled: Keycloak ended the session. Dead here too, nothing left to revoke over there.
            b.dead = true;
            vault.delete(entry(tokenId));
            LOG.info("A bound script token lost its Keycloak session: " + tokenId);
            return Liveness.DEAD;
        } catch (IdpUnavailableException e) {
            // Handled: refused for this use and the next seconds, then asked again.
            b.failedAt = clock.instant();
            return Liveness.UNAVAILABLE;
        }
    }

    /**
     * The refreshed token goes to the vault unless the token ended while Keycloak answered: then the fresh token (a
     * rotated one is a new grant) is revoked instead, and nothing is kept.
     */
    private boolean keep(String tokenId, Bound b, String stored, String current) {
        synchronized (this) {
            if (!b.dead && bound.get(tokenId) == b) {
                if (!current.equals(stored)) vault.put(entry(tokenId), current.getBytes(StandardCharsets.UTF_8));
                return true;
            }
        }
        try {
            keycloak.revoke(current);
        } catch (IdpRefusedException | IdpUnavailableException e) {
            // Handled: the session expires by itself in Keycloak, and nothing here holds this token.
            LOG.warn("A refreshed token of an ended binding could not be revoked: "
                    + e.getClass().getSimpleName());
        }
        return false;
    }

    /**
     * The token ended (revoked from the desk, signed out everywhere, expired): refused from now on, then its offline
     * session is revoked at Keycloak (RFC 7009). A Keycloak that does not take it leaves the entry for the next sweep.
     */
    public void end(String tokenId) {
        Bound b = bound.get(tokenId);
        if (b == null) return;
        b.dead = true;
        forget(tokenId);
    }

    private synchronized void forget(String tokenId) {
        Optional<String> stored = token(tokenId);
        if (stored.isPresent()) {
            try {
                keycloak.revoke(stored.get());
            } catch (IdpRefusedException | IdpUnavailableException e) {
                // Handled: only a 2xx counts; the entry stays in the vault and the sweep retries.
                LOG.warn("The offline session of a bound token could not be revoked yet: "
                        + e.getClass().getSimpleName());
                return;
            }
        }
        vault.delete(entry(tokenId));
        bound.remove(tokenId);
        writeIndex();
    }

    /** Ends every binding whose token left the registry, and retries the revocations Keycloak missed. */
    void sweep() {
        for (String id : List.copyOf(bound.keySet())) {
            Bound b = bound.get(id);
            if (b == null) continue;
            if (open.test(id)) continue;
            b.dead = true;
            forget(id);
        }
    }

    /** Test seam: the clock the 60 s are read on. */
    public void useClock(Clock clock) {
        this.clock = clock;
    }

    void onStart(@Observes StartupEvent ignored) {
        loadIndex();
        // Vert.x's timer, a worker for the calls to Keycloak: no thread of its own (ArchitectureTest).
        vertx.executeBlocking(
                () -> {
                    sweepQuietly();
                    return null;
                },
                false);
        timer = vertx.setPeriodic(
                CHECK_EVERY.toMillis(),
                id -> vertx.executeBlocking(
                        () -> {
                            sweepQuietly();
                            return null;
                        },
                        false));
    }

    /** What a previous run left: dead from the first minute, revoked by the first sweep. */
    void loadIndex() {
        vault.get(INDEX).ifPresent(b -> {
            for (String id : new String(b, StandardCharsets.UTF_8).split("\n")) {
                if (id.isBlank()) continue;
                Bound old = new Bound("", Instant.EPOCH);
                old.dead = true;
                bound.put(id, old);
            }
        });
    }

    void onStop(@Observes ShutdownEvent ignored) {
        vertx.cancelTimer(timer);
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException e) {
            // Handled: logged, the next minute tries again; a failing sweep never stops the timer.
            LOG.warn("The sweep of bound script tokens failed: " + e.getClass().getSimpleName());
        }
    }

    private Optional<String> token(String tokenId) {
        return vault.get(entry(tokenId)).map(b -> new String(b, StandardCharsets.UTF_8));
    }

    private void writeIndex() {
        List<String> ids = new ArrayList<>(bound.keySet());
        if (ids.isEmpty()) vault.delete(INDEX);
        else vault.put(INDEX, String.join("\n", ids).getBytes(StandardCharsets.UTF_8));
    }
}
