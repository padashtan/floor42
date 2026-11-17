package dev.floor42.server.iam;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.AuthorizationTuple;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.TicketId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** What identity needs to know of the company, owned here so that iam never reaches into the application. */
public interface OfficeFacts {

    /** The agents on staff who have not been dismissed. */
    Collection<AgentId> activeStaff();

    Optional<MissionStatus> missionStatus(MissionId mission);

    /** Whether the ticket is the mission's retrospective ticket. */
    boolean isRetroTicket(MissionId mission, TicketId ticket);

    /** Journals the director's console-link sign-in while IAM mode is on (BreakGlassSignIn), by session id only. */
    void breakGlassSignIn(String session);

    /** One change of the broker authorization, as the journal row says it (ADR-0080). */
    record AuthorizationChange(
            List<AuthorizationTuple> writes,
            List<AuthorizationTuple> deletes,
            String modelId,
            String reason,
            boolean imported,
            boolean reconciled) {}

    /**
     * Journals one {@code AuthorizationChanged} row: by the director for a grant or a tick, by Floor42 on the
     * director's behalf for the rest. Defaults refuse, so a stand-in that does not journal fails loudly.
     */
    default void authorizationChanged(AuthorizationChange change) {
        throw new UnsupportedOperationException("This stand-in does not journal authorization changes");
    }

    /** Whether the journal holds the one-shot import of the 1.2.0 files ({@code imported: true}). */
    default boolean authorizationImported() {
        return false;
    }

    /** The tuples the journal's rows leave standing. */
    default Set<AuthorizationTuple> journaledAuthorization() {
        throw new UnsupportedOperationException("This stand-in does not read the journal's authorization");
    }
}
