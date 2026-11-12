package dev.floor42.server.app;

import dev.floor42.domain.AgentId;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live sessions a director's meeting may hold (ADR-0047): those {@link SessionGate} runs, each under its {@link
 * MeetingHold}. Only these are ever stopped for a meeting, and only while none of their calls runs; a retrospective
 * turn or a staffing review is not here, so it is never cut: it ends, then the meeting reserves its agent.
 */
@ApplicationScoped
public class HeldSessions {

    private final Map<AgentId, MeetingHold> live = new ConcurrentHashMap<>();

    void started(MeetingHold hold) {
        live.put(hold.agent(), hold);
    }

    void ended(MeetingHold hold) {
        live.remove(hold.agent(), hold);
    }

    /** The hold of the agent's session running now through the gate, if any. */
    Optional<MeetingHold> of(AgentId agent) {
        return Optional.ofNullable(live.get(agent));
    }
}
