package dev.floor42.server.app;

import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.WorkRole;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who does what (ADR-0019). The role recorded at hiring decides it; staff hired before roles were recorded keep the
 * work Floor42 always gave them.
 */
public final class Staff {

    private static final Map<AgentId, WorkRole> FIRST_STAFF = Map.of(
            StaffProvisioner.PRODUCER, WorkRole.DEVELOPER,
            StaffProvisioner.REVIEWER, WorkRole.REVIEWER,
            StaffProvisioner.PLANNER, WorkRole.PLANNER,
            StaffProvisioner.RESEARCHER, WorkRole.RESEARCHER,
            StaffProvisioner.ANALYST, WorkRole.ANALYST);

    private Staff() {}

    /** The agent's work role; null for committee members hired only to sit on committees. */
    public static WorkRole roleOf(Agent a) {
        return a.workRole() != null ? a.workRole() : FIRST_STAFF.get(a.id());
    }

    /** Agents of this role who can take work: present, not suspended. */
    static List<Agent> active(Company c, WorkRole role) {
        return c.staff().all().stream()
                .filter(a -> !a.dismissed() && !a.suspended() && roleOf(a) == role)
                .toList();
    }

    /**
     * A developer is free when no session of theirs runs and they hold no ticket in progress or in review on an active
     * mission: their worktree carries one ticket at a time, so a review never reads changes of the next ticket.
     */
    static boolean free(Company c, AgentId agent, MissionId mission, TicketId ticket, Set<AgentId> busy) {
        if (agent == null || busy.contains(agent)) return false;
        if (c.meetings().attending(agent).isPresent()) return false;
        // A worktree belongs to one mission and one agent: what it holds elsewhere does not matter here. The
        // committee's own ticket is not worktree work.
        List<TicketId> committees = c.committees().of(mission).stream()
                .map(dev.floor42.domain.Committee::ticket)
                .toList();
        return c.tickets().of(mission).stream()
                .filter(t -> !t.id().equals(ticket) && !committees.contains(t.id()))
                .noneMatch(t -> agent.equals(t.assignee())
                        && (t.status() == TicketStatus.IN_PROGRESS || t.status() == TicketStatus.IN_REVIEW));
    }

    /** A free name for a new agent's identifier: its first name, then with a number. */
    public static AgentId newId(Company c, String name) {
        String base = name.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(?:^-)|(?:-$)", "");
        if (base.isEmpty()) base = "agent";
        // The first staff's and committee members' ids stay theirs, even before Floor42 hires them.
        Set<String> reserved = new java.util.HashSet<>();
        FIRST_STAFF.keySet().forEach(a -> reserved.add(a.value()));
        CommitteeRoles.all(c).forEach(r -> reserved.add(r.agent().value()));
        String id = base;
        for (int i = 2; c.staff().find(new AgentId(id)).isPresent() || reserved.contains(id); i++) id = base + "-" + i;
        return new AgentId(id);
    }
}
