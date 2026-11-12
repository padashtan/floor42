package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Origin;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * A process was launched but failed before Claude Code named its session: it may have spent up to its cap, and a missing
 * record is no proof that it spent nothing. The launch is recorded as a failed session of its own (its id says so) with
 * the cap as its bound, whatever the mission's state now (cancelled meanwhile included).
 */
final class UnnamedLaunch {

    private static final Logger LOG = Logger.getLogger(UnnamedLaunch.class);

    private UnnamedLaunch() {}

    /** The journal's id of a launch: one per session folder, whichever daemon records it. */
    static String idOf(String mission, String folder) {
        return mission + ":launch-" + folder;
    }

    /**
     * Nothing when no process was launched (no cap given) or for a replay. The record is named after the session
     * folder, as a restart names it: a daemon that stops before the folder is cleared finds it journaled already.
     */
    static void record(OfficeService office, SessionSpec spec, Double capUsd, String folder) {
        if (capUsd == null || spec.origin() != Origin.REAL) return;
        String record =
                idOf(spec.mission().value(), folder == null ? UUID.randomUUID().toString() : folder);
        try {
            office.execute(c -> c.sessions()
                    .recordUnnamedLaunch(
                            office.context(Actor.system(), spec.origin()),
                            record,
                            spec.ticket(),
                            spec.agent(),
                            spec.model(),
                            capUsd));
        } catch (DomainException refused) {
            // Handled: only an unknown ticket or agent refuses it (removed from the journal?): logged, the cap with it
            LOG.warnf(
                    "A launch for %s on %s that never named its session, cap %.2f USD, could not be recorded: %s",
                    spec.agent(), spec.ticket(), capUsd, refused.getMessage());
        }
    }
}
