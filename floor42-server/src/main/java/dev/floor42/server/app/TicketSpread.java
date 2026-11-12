package dev.floor42.server.app;

import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Who each planned ticket goes to (ADR-0019): a chain of dependent tickets stays with one developer, who builds on
 * their own worktree; independent tickets go to the developers in turn, so every developer hired has work. The
 * dispatcher still gives a ready ticket to another free developer when its assignee is busy.
 */
final class TicketSpread {

    private TicketSpread() {}

    /** The planned tickets' assignees, in plan order; empty without a developer. */
    static Map<TicketId, AgentId> of(List<TicketCreated> planned, List<AgentId> developers) {
        Map<TicketId, AgentId> to = new LinkedHashMap<>();
        if (developers.isEmpty()) return to;
        int next = 0;
        for (TicketCreated t : planned) {
            AgentId follows = t.dependsOn().stream()
                    .map(to::get)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            to.put(t.ticket(), follows != null ? follows : developers.get(next++ % developers.size()));
        }
        return to;
    }

    /** The developers who can take work, {@code first} (the one the planner read the worktree of) first. */
    static List<AgentId> developers(Company c, AgentId first) {
        List<AgentId> all = new ArrayList<>(List.of(first));
        Staff.active(c, WorkRole.DEVELOPER).stream()
                .map(Agent::id)
                .filter(a -> !a.equals(first))
                .forEach(all::add);
        return all;
    }

    /** The team the planner plans for, by role: "Chris (developer), Ken (developer), Darius (reviewer)". */
    static String team(Company c) {
        List<String> who = new ArrayList<>();
        for (WorkRole role : List.of(WorkRole.DEVELOPER, WorkRole.REVIEWER, WorkRole.RESEARCHER)) {
            Staff.active(c, role).stream()
                    .map(a -> a.displayName() + " (" + role.name().toLowerCase(java.util.Locale.ROOT) + ")")
                    .forEach(who::add);
        }
        return who.isEmpty() ? "none hired yet" : who.stream().collect(Collectors.joining(", "));
    }
}
