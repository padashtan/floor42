package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;

import dev.floor42.domain.event.ActionEvent.ActionDenied;
import dev.floor42.domain.event.DeskEvent.QuestionWithdrawn;
import dev.floor42.domain.event.TicketEvent.TicketCancelled;
import dev.floor42.domain.event.WorkflowEvent.WorkflowProposalClosed;
import dev.floor42.domain.workflow.WorkflowProposal;

/**
 * What a mission's end requires of the other areas: it completes only when all its tickets are closed, no blocking
 * question is open and no tool is running; a cancellation closes everything that depends on it (tickets, questions, actions).
 */
final class MissionClosure {

    private final CompanyView view;

    MissionClosure(CompanyView view) {
        this.view = view;
    }

    void requireFinished(MissionId id) {
        require(
                view.tickets(id).stream().allMatch(t -> t.status().isTerminal()),
                Refusal.MISSION_NOT_FINISHED,
                "Some tickets of " + id + " are still open");
        require(
                view.questions(id).stream().noneMatch(q -> q.status() == QuestionStatus.OPEN && q.blocking()),
                Refusal.MISSION_NOT_FINISHED,
                "A blocking question of " + id + " is still open");
        require(
                view.actions(id).stream()
                        .noneMatch(a -> a.stage() == ActionStage.REQUESTED || a.stage() == ActionStage.AUTHORIZED),
                Refusal.ACTIONS_RUNNING,
                "Some actions of " + id + " are still pending or running");
    }

    /** Open tickets are cancelled, open questions withdrawn, pending actions denied. */
    void closeDependents(Recorder r, MissionId id) {
        for (Ticket t : view.tickets(id)) {
            if (!t.status().isTerminal()) r.emit(new TicketCancelled(t.id(), RecordedReason.missionCancelled()));
        }
        for (WorkflowProposal p : view.proposals()) {
            if (p.mission().equals(id) && p.status() == WorkflowProposal.Status.OPEN) {
                r.emit(new WorkflowProposalClosed(p.number(), false));
            }
        }
        for (Question q : view.questions(id)) {
            if (q.status() == QuestionStatus.OPEN)
                r.emit(new QuestionWithdrawn(q.id(), RecordedReason.missionCancelled()));
        }
        for (Action a : view.actions(id)) {
            if (a.stage() == ActionStage.REQUESTED) {
                r.emit(new ActionDenied(a.id(), DenialReason.MISSION_NOT_ACTIVE, DecisionBasis.missionCancelled()));
            }
        }
    }
}
