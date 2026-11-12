package dev.floor42.server.iam;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.TicketId;
import java.util.Collection;
import java.util.Optional;

/** What identity needs to know of the company, owned here so that iam never reaches into the application. */
public interface OfficeFacts {

    /** The agents on staff who have not been dismissed. */
    Collection<AgentId> activeStaff();

    Optional<MissionStatus> missionStatus(MissionId mission);

    /** Whether the ticket is the mission's retrospective ticket. */
    boolean isRetroTicket(MissionId mission, TicketId ticket);

    /** Journals the director's console-link sign-in while IAM mode is on (BreakGlassSignIn), by session id only. */
    void breakGlassSignIn(String session);
}
