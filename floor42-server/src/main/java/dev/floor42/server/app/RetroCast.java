package dev.floor42.server.app;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.WorkRole;
import java.util.ArrayList;
import java.util.List;

/** Who takes part in a mission's retrospective, and who chairs it. */
final class RetroCast {

    private RetroCast() {}

    /** Agents who had a session on the mission, in the order they first worked, still in the company. */
    static List<AgentId> participants(Company c, MissionId m) {
        List<AgentId> found = new ArrayList<>();
        c.sessions().of(m).stream()
                .sorted(java.util.Comparator.comparing(AgentSession::startedAt))
                .map(AgentSession::agent)
                .filter(a -> !found.contains(a))
                .filter(a -> c.staff().find(a).map(x -> !x.dismissed()).orElse(false))
                .forEach(found::add);
        return found;
    }

    /** The planner chairs when he took part, else the first reviewer, else whoever worked first. */
    static AgentId chairOf(Company c, List<AgentId> participants) {
        for (WorkRole role : List.of(WorkRole.PLANNER, WorkRole.REVIEWER)) {
            for (AgentId a : participants) {
                if (c.staff().find(a).map(x -> Staff.roleOf(x) == role).orElse(false)) return a;
            }
        }
        return participants.getFirst();
    }
}
