package dev.floor42.server.app;

import dev.floor42.domain.MissionId;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Mission creations keyed by the client's {@code Idempotency-Key}: a request sent twice with one key creates one
 * mission, and the second answer names it. A second request that arrives while the first runs waits for it. A key
 * reused for another mission is refused. Keys live in memory for {@link #KEPT}: a daemon restart forgets them, which
 * only matters for a retry sent across the restart.
 */
@ApplicationScoped
public class IdempotentCreations {

    /** How long a key is remembered after its creation finished. */
    public static final Duration KEPT = Duration.ofMinutes(10);

    /** The longest key accepted: a UUID needs 36 characters. */
    public static final int KEY_MAX = 100;

    /** The mission created under a key, and whether this answer is a replay of an earlier one. */
    public record Outcome(MissionId mission, boolean replayed) {}

    /** {@code done} is when the creation finished, null while it runs: a key is kept {@link #KEPT} from then. */
    private record Entry(String request, CompletableFuture<MissionId> created, Instant done) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    IdempotentCreations() {
        this(Clock.systemUTC());
    }

    IdempotentCreations(Clock clock) {
        this.clock = clock;
    }

    /**
     * Creates once per key. {@code request} is a fingerprint of what was asked: the same key with another request is
     * refused (409 IDEMPOTENCY_KEY_REUSED). A creation that failed frees its key, so a corrected retry may use it.
     */
    public Outcome create(String key, String request, Supplier<MissionId> create) {
        if (key.length() > KEY_MAX) {
            throw RefusedException.invalid(
                    "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key is at most " + KEY_MAX + " characters");
        }
        Instant now = clock.instant();
        entries.values().removeIf(e -> e.done() != null && e.done().plus(KEPT).isBefore(now));
        Entry mine = new Entry(request, new CompletableFuture<>(), null);
        Entry found = entries.putIfAbsent(key, mine);
        if (found != null) return replay(found, request);
        try {
            MissionId m = create.get();
            entries.replace(key, mine, new Entry(request, mine.created(), clock.instant()));
            mine.created().complete(m);
            return new Outcome(m, false);
        } catch (Throwable e) {
            // Handled: the key is freed for a corrected retry, the twin waiting on it gets the same failure
            entries.remove(key, mine);
            mine.created().completeExceptionally(e);
            throw e;
        }
    }

    private static Outcome replay(Entry found, String request) {
        if (!found.request().equals(request)) {
            throw RefusedException.conflict(
                    "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for another mission");
        }
        try {
            return new Outcome(found.created().join(), true);
        } catch (CompletionException e) {
            // Handled: the first request's own refusal is the answer to its twin
            if (e.getCause() instanceof Error err) throw err;
            throw e.getCause() instanceof RuntimeException r ? r : e;
        }
    }
}
