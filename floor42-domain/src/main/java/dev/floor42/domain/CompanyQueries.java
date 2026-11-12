package dev.floor42.domain;

import java.util.List;

/** Read queries whose answer spans several areas of the company. */
final class CompanyQueries {

    private final CompanyView view;
    private final AgentStatusRule status;

    CompanyQueries(CompanyView view) {
        this.view = view;
        this.status = new AgentStatusRule(view);
    }

    AgentStatus statusOf(AgentId id) {
        return status.statusOf(id);
    }

    /** True while the agent runs a session: the one time it cannot leave. */
    boolean atWork(AgentId id) {
        return view.sessions().stream().anyMatch(s -> s.isOpen() && s.agent().equals(id));
    }

    /** The tickets a dismissal of this agent would give back to the queue: those it had started, missions still open. */
    List<TicketId> handedBackOnDismissal(AgentId id) {
        return view.tickets().stream()
                .filter(t -> id.equals(t.assignee())
                        && t.status() == TicketStatus.IN_PROGRESS
                        && !view.mission(t.mission()).orElseThrow().status().isTerminal())
                .map(Ticket::id)
                .toList();
    }

    /**
     * The commit the director accepted in the workshop for a ticket's work: the latest one on its developer's workspace
     * of that mission, accepted after the ticket started. Null when the work changed no file.
     */
    String commitFor(TicketId id) {
        Ticket t = view.existingTicket(id);
        if (t.assignee() == null || t.startedAt() == null) return null;
        return view.interventions(t.mission()).stream()
                .filter(i -> i.kind() == Intervention.Kind.PATCH_ACCEPTED
                        && t.assignee().equals(i.workspace())
                        && !i.at().isBefore(t.startedAt()))
                .reduce((a, b) -> b)
                .map(Intervention::detail)
                .orElse(null);
    }
}
