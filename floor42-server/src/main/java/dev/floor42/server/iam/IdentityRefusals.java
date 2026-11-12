package dev.floor42.server.iam;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The last refusals of agent tokens, in memory only: a forged token or an unknown caller is not attributable to a
 * session, so it never reaches the journal. No token is kept, only the code, the jti when the signature held, and the
 * launch the token named.
 */
@ApplicationScoped
public class IdentityRefusals {

    static final int SIZE = 100;

    public record Entry(Instant at, String code, String jti, String launch) {}

    private final Deque<Entry> ring = new ArrayDeque<>();

    public synchronized void add(Instant at, String code, String jti, String launch) {
        if (ring.size() == SIZE) ring.removeFirst();
        ring.addLast(new Entry(at, code, jti, launch));
    }

    /** Oldest first. */
    public synchronized List<Entry> recent() {
        return new ArrayList<>(ring);
    }
}
