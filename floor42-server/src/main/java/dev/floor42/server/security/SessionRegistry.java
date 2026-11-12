package dev.floor42.server.security;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The director's open sessions, in memory only (ADR-0044): browsers signed in with a single-use link, and script tokens
 * issued from the desk. Only a SHA-256 of each secret is kept, here and in the journal; the secret itself is handed out
 * once and never stored. Everything here dies with the daemon: a restart signs every browser and script out.
 */
@ApplicationScoped
public class SessionRegistry {

    public enum Kind {
        BROWSER,
        SCRIPT
    }

    /** What a session may do. A browser always has {@link #ALL}. */
    public enum Scope {
        /** GET and HEAD only. */
        READ,
        /** Read, plus creating, planning and steering missions. */
        MISSIONS_WRITE,
        /** Everything the director can do. */
        ALL;

        public String wire() {
            return switch (this) {
                case READ -> "read";
                case MISSIONS_WRITE -> "missions:write";
                case ALL -> "all";
            };
        }

        public static Optional<Scope> parse(String wire) {
            for (Scope s : values()) if (s.wire().equals(wire)) return Optional.of(s);
            return Optional.empty();
        }
    }

    /**
     * @param expires nullable for a browser: its idle and absolute limits apply instead
     * @param label nullable: what the director named a script token
     * @param breakGlass opened with the console link ({@code /auth}), journaled as {@code BreakGlassSignIn} when IAM mode is on; in IAM mode such a session holds
     *     no Keycloak tokens, so it cannot delegate
     */
    public record Session(
            String id,
            Kind kind,
            Scope scope,
            Instant created,
            Instant lastUsed,
            Instant expires,
            String userAgent,
            String label,
            String hash,
            boolean breakGlass) {

        Session used(Instant at) {
            return new Session(id, kind, scope, created, at, expires, userAgent, label, hash, breakGlass);
        }
    }

    /** A new session and its secret, which the caller hands out once. */
    public record Issued(Session session, String secret) {}

    public static final int MAX_SCRIPT_DAYS = 30;

    @ConfigProperty(name = "floor42.sessions.idle-hours", defaultValue = "8")
    long idleHours;

    @ConfigProperty(name = "floor42.sessions.absolute-hours", defaultValue = "24")
    long absoluteHours;

    Clock clock = Clock.systemUTC();

    private final Map<String, Session> byHash = new ConcurrentHashMap<>();
    private int issued;

    public synchronized Issued openBrowser(String userAgent) {
        return add(Kind.BROWSER, Scope.ALL, null, userAgent, null, false);
    }

    /** A browser session opened with the console link: the break-glass way in when IAM mode is on. */
    public synchronized Issued openBreakGlass(String userAgent) {
        return add(Kind.BROWSER, Scope.ALL, null, userAgent, null, true);
    }

    /** A script token valid {@code days} days (1 to {@value #MAX_SCRIPT_DAYS}). */
    public synchronized Issued issueScript(Scope scope, int days, String label, String userAgent) {
        if (days < 1 || days > MAX_SCRIPT_DAYS) {
            throw new IllegalArgumentException("A script token lasts 1 to " + MAX_SCRIPT_DAYS + " days");
        }
        return add(Kind.SCRIPT, scope, now().plus(Duration.ofDays(days)), userAgent, label, false);
    }

    private Issued add(Kind kind, Scope scope, Instant expires, String userAgent, String label, boolean breakGlass) {
        String secret = SessionToken.random(32);
        Instant now = now();
        // Random, not a counter: the journal outlives this registry, and an id must not name two tokens.
        String id = (kind == Kind.BROWSER ? "B-" : "S-")
                + sha256(secret + (++issued)).substring(0, 10);
        Session s = new Session(
                id, kind, scope, now, now, expires, clip(userAgent), clip(label), sha256(secret), breakGlass);
        byHash.put(s.hash(), s);
        return new Issued(s, secret);
    }

    /** The live session this secret opens, marked as used now; empty when unknown, expired or idle too long. */
    public Optional<Session> authenticate(String secret) {
        if (secret == null || secret.isEmpty()) return Optional.empty();
        String hash = sha256(secret);
        Session s = byHash.get(hash);
        if (s == null) return Optional.empty();
        Instant now = now();
        if (expired(s, now)) {
            byHash.remove(hash);
            return Optional.empty();
        }
        Session used = s.used(now);
        byHash.replace(hash, s, used);
        return Optional.of(used);
    }

    /** The live session this secret opens, without marking it used: for a check that must not keep a session alive. */
    public Optional<Session> peek(String secret) {
        if (secret == null || secret.isEmpty()) return Optional.empty();
        Session s = byHash.get(sha256(secret));
        return s == null || expired(s, now()) ? Optional.empty() : Optional.of(s);
    }

    /** Test seam: the clock the limits are read on. */
    public void useClock(Clock clock) {
        this.clock = clock;
    }

    /** Live sessions, oldest first; expired ones are dropped on the way. */
    public List<Session> list() {
        Instant now = now();
        byHash.values().removeIf(s -> expired(s, now));
        return byHash.values().stream()
                .sorted(Comparator.comparing(Session::created).thenComparing(Session::id))
                .toList();
    }

    public Optional<Session> find(String id) {
        return list().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** Ends one session; the removed session, if it was live. */
    public Optional<Session> revoke(String id) {
        Optional<Session> s = find(id);
        s.ifPresent(x -> byHash.remove(x.hash()));
        return s;
    }

    /** Ends the session this secret opens, if any. */
    public Optional<Session> revokeSecret(String secret) {
        if (secret == null) return Optional.empty();
        return Optional.ofNullable(byHash.remove(sha256(secret)));
    }

    /** Ends every session; those that were live. */
    public synchronized List<Session> clear() {
        List<Session> all = list();
        byHash.clear();
        return all;
    }

    private boolean expired(Session s, Instant now) {
        if (s.expires() != null) return !now.isBefore(s.expires());
        return !now.isBefore(s.lastUsed().plus(Duration.ofHours(idleHours)))
                || !now.isBefore(s.created().plus(Duration.ofHours(absoluteHours)));
    }

    private Instant now() {
        return clock.instant();
    }

    private static String clip(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.strip();
        return t.length() <= 200 ? t : t.substring(0, 200);
    }

    public static String sha256(String secret) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
