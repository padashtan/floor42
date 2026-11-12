package dev.floor42.server.app;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.WorkRole;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Reads, under the office monitor, about tickets that build on work done on other branches (D10). */
final class Dependencies {

    private Dependencies() {}

    /**
     * The developers who did a dependency of this ticket on a branch its taker will not have: there are several of
     * them, or the only one is no longer on staff. Empty when the ticket can build on what it depends on.
     */
    static Set<AgentId> producersElsewhere(Company c, Ticket t) {
        Set<AgentId> producers = new LinkedHashSet<>();
        for (TicketId d : t.dependsOn()) {
            c.tickets()
                    .find(d)
                    .map(Ticket::assignee)
                    .filter(a -> c.staff()
                            .find(a)
                            .map(x -> Staff.roleOf(x) == WorkRole.DEVELOPER)
                            .orElse(false))
                    .ifPresent(producers::add);
        }
        if (producers.size() > 1) return producers;
        boolean gone = producers.stream().anyMatch(a -> !onStaff(c, a));
        return gone ? producers : Set.of();
    }

    /** The producers of this ticket's dependencies who left with work in their worktree the director never accepted. */
    static List<AgentId> strandedWork(Company c, Ticket t, Set<AgentId> held) {
        if (c.workshop().merge(t.id()).isPresent()) return List.of();
        return producersElsewhere(c, t).stream()
                .filter(held::contains)
                .filter(a -> !onStaff(c, a))
                .toList();
    }

    /**
     * The director answered "Go on without it" to the question about this ticket's dependencies: never another
     * question's answer, which says nothing of the other developers' branches.
     */
    static boolean wentOnWithout(Company c, Ticket t) {
        return c.desk().of(t.mission()).stream()
                .anyMatch(q -> t.id().equals(q.ticket())
                        && q.status() == QuestionStatus.ANSWERED
                        && (q.phrase() == null
                                || DependencyDesk.CODE.equals(q.phrase().code()))
                        && q.answered(OptionCode.GO_ON_WITHOUT));
    }

    /** An active developer, as opposed to one who left. */
    static boolean onStaff(Company c, AgentId a) {
        return Staff.active(c, WorkRole.DEVELOPER).stream().anyMatch(x -> x.id().equals(a));
    }
}
