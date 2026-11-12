package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TicketEvent.TicketApproved;
import dev.floor42.domain.event.TicketEvent.TicketEdited;
import dev.floor42.domain.event.WorkflowEvent.NodePlanned;
import dev.floor42.domain.workflow.Workflow;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The director approves proposed tickets. In a graph mission (ADR-0062) each approved node is also planned: its notes
 * leave the description and {@code NodePlanned} settles its risk, tier and tests, with the risk the plan gave it or the
 * one the director chose.
 */
final class TicketApprovals {

    private final CompanyView view;
    private final TicketBoard board;

    TicketApprovals(CompanyView view, TicketBoard board) {
        this.view = view;
        this.board = board;
    }

    /** Every ticket is checked before the first event: a refusal half-way would leave events never journaled. */
    List<EventEnvelope> approve(
            CommandContext ctx, MissionId id, List<TicketId> selection, Map<TicketId, String> risks) {
        requireDirector(ctx);
        Mission m = view.missionFor(ctx, id);
        require(!m.status.isTerminal(), Refusal.MISSION_CLOSED, id + " is closed");
        require(selection != null && !selection.isEmpty(), Refusal.INVALID_ARGUMENT, "Select at least one ticket");
        List<TicketId> approved = selection.stream().distinct().toList();
        for (TicketId tid : approved) {
            Ticket t = board.workTicket(tid);
            require(t.mission().equals(id), Refusal.UNKNOWN_TICKET, tid + " is not a ticket of " + id);
            require(
                    t.status == TicketStatus.PROPOSED,
                    Refusal.NOT_PROPOSED,
                    tid + " is " + t.status + ", not proposed");
        }
        Map<TicketId, String> chosen = risks == null ? Map.of() : risks;
        requireRisks(approved, chosen);
        boolean graph = m.origin() == Origin.REAL
                && view.workflowOf(id).runnable().map(Workflow::graph).orElse(false);
        if (graph) requireRunnableTests(approved);
        Recorder r = view.record(ctx, id);
        for (TicketId tid : approved) {
            if (graph) planNode(r, board.existing(tid), chosen.get(tid));
            else r.emit(new TicketApproved(tid));
        }
        return r.events();
    }

    private void requireRunnableTests(List<TicketId> approved) {
        for (TicketId tid : approved) {
            NodePlan.testProblem(board.existing(tid).acceptanceCriteria()).ifPresent(p -> {
                throw new DomainException(
                        Refusal.INVALID_ARGUMENT,
                        tid + " has a test command with a hidden or unsuitable character (" + p.code() + ", position "
                                + p.position() + "): edit the test before approving");
            });
        }
    }

    private static void requireRisks(List<TicketId> approved, Map<TicketId, String> chosen) {
        for (Map.Entry<TicketId, String> e : chosen.entrySet()) {
            require(approved.contains(e.getKey()), Refusal.INVALID_ARGUMENT, e.getKey() + " is not approved here");
            require(
                    "low".equals(e.getValue()) || "high".equals(e.getValue()),
                    Refusal.INVALID_ARGUMENT,
                    "A risk is low or high");
        }
    }

    /** The node's notes leave its description for the journal: edited, approved, then planned. */
    private static void planNode(Recorder r, Ticket t, String risk) {
        NodePlan plan = NodePlan.proposed(t.description, t.acceptanceCriteria());
        if (risk != null) plan = plan.withRisk(NodePlan.Risk.valueOf(risk.toUpperCase(Locale.ROOT)));
        if (NodePlan.hasNotes(t.description)) {
            // Floor42 strips the notes, not the director; the planner's reason for the risk stays as a plain sentence
            String reason = NodePlan.riskReason(t.description);
            String stripped = NodePlan.stripNotes(t.description);
            String kept = reason.isEmpty() ? stripped : stripped + "\n\nWhy this risk: " + reason;
            r.as(Actor.system()).emit(new TicketEdited(t.id(), t.title, kept, t.acceptanceCriteria()));
        }
        r.emit(new TicketApproved(t.id()));
        r.emit(new NodePlanned(t.id(), plan.risk().wire(), plan.tier().wire(), plan.tests()));
    }
}
