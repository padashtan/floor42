package dev.floor42.server.api;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TrustLevel;
import dev.floor42.server.app.core.OfficeService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The staff's trust levels as a test found them. A real mission whose agent probes the workspace raises a risk, which
 * narrows that agent's level for good (ADR-0082): every class after it in the same app would meet an agent whose writes
 * ask the director. A test that provokes it restores the levels when it ends.
 */
public final class TrustSnapshot {

    private final Map<AgentId, TrustLevel> levels = new HashMap<>();

    public static TrustSnapshot take(OfficeService office) {
        TrustSnapshot s = new TrustSnapshot();
        office.query(c -> {
            c.staff().all().forEach(a -> s.levels.put(a.id(), a.trust()));
            return null;
        });
        return s;
    }

    /** Raises back what fell since the snapshot; the domain's step-up proof is given, the test being the director. */
    public void restore(OfficeService office) {
        levels.forEach((id, was) -> office.execute(c -> {
            var now = c.staff().find(id).map(a -> a.trust());
            if (now.isEmpty() || now.get().compareTo(was) >= 0) return List.of();
            return c.staff().raiseTrust(office.context(Actor.director(), Origin.REAL), id, was, true);
        }));
    }
}
