package dev.floor42.server.api.gateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Tool calls whose hook authorisation a broker call already used: one authorisation, one call. Aged out and bounded, but never while the action is still
 * authorized: the action's state is the authority, this set is the fast path. */
final class SpentToolUses {

    private final long ttlMillis;
    private final int bound;
    private final LongSupplier clock;
    private final Map<String, Spent> spentAt = new LinkedHashMap<>();

    private record Spent(long at, BooleanSupplier live) {}

    SpentToolUses(Duration ttl, int bound, LongSupplier clock) {
        this.ttlMillis = ttl.toMillis();
        this.bound = bound;
        this.clock = clock;
    }

    /** True the first time an id is spent; {@code live} says whether its action is still authorized. */
    synchronized boolean spend(String id, BooleanSupplier live) {
        long now = clock.getAsLong();
        // Insertion order is age order: the eldest entries expire first.
        var it = spentAt.values().iterator();
        while (it.hasNext()) {
            Spent e = it.next();
            if (now - e.at() <= ttlMillis) break;
            if (!e.live().getAsBoolean()) it.remove();
        }
        if (spentAt.containsKey(id)) return false;
        if (spentAt.size() >= bound) {
            // shortcut: grows past the bound while every entry is live, bounded by the authorized actions.
            var eldest = spentAt.values().iterator();
            while (eldest.hasNext())
                if (!eldest.next().live().getAsBoolean()) {
                    eldest.remove();
                    break;
                }
        }
        spentAt.put(id, new Spent(now, live));
        return true;
    }
}
