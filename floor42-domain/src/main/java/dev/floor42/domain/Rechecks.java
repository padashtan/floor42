package dev.floor42.domain;

import dev.floor42.domain.workflow.Workflow;

/**
 * "To recheck" (ADR-0062): in a graph mission, a done node one of whose dependencies was reopened after it was done.
 * Derived from the tickets as they stand, never written, and never acted on: the director decides whether to reopen it.
 */
final class Rechecks {

    private Rechecks() {}

    static boolean toRecheck(CompanyView view, TicketBoard board, TicketId id) {
        Ticket x = board.ticket(id).orElse(null);
        return x != null
                && x.status == TicketStatus.DONE
                && view.workflowOf(x.mission()).runnable().map(Workflow::graph).orElse(false)
                && x.dependsOn().stream()
                        .map(board::ticket)
                        .flatMap(java.util.Optional::stream)
                        .anyMatch(d -> reopenedSince(d, x));
    }

    /** A dependency is open again, or was done again after the ticket: it was reopened since (a done ticket needs it done). */
    private static boolean reopenedSince(Ticket dependency, Ticket done) {
        return dependency.status == TicketStatus.IN_PROGRESS
                || dependency.status == TicketStatus.IN_REVIEW
                || (dependency.endedAt != null && done.endedAt != null && dependency.endedAt.isAfter(done.endedAt));
    }
}
