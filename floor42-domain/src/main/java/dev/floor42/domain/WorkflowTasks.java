package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TicketEvent.TicketApproved;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.event.TicketEvent.TicketReopened;
import dev.floor42.domain.event.WorkflowEvent.WorkflowStepTicketOpened;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The tickets of a workflow's tasks (ADR-0061): opened once, by the plan's approval, and reopened when the work they
 * come after changes. Both are written inside the command that causes them, so a crash leaves all of it or none.
 */
final class WorkflowTasks {

    /** Why a done task of the slot before acceptance runs again. */
    static final String WORK_CHANGED = "work changed after the step";

    private WorkflowTasks() {}

    /** Tasks and no plan or production: the mission opens on the approval itself. */
    static boolean stepApproval(CompanyView view, MissionId mission) {
        return view.workflowOf(mission)
                .runnable()
                .map(Workflow::opensOnStepApproval)
                .orElse(false);
    }

    /** The plan is approved: the tasks of the workflow open once, here, so a rejected plan creates nothing twice. */
    static List<EventEnvelope> approved(CompanyView view, Recorder r, MissionId mission) {
        view.workflowOf(mission).runnable().ifPresent(w -> open(view, r, mission, w));
        return r.events();
    }

    /**
     * Opens a ticket for each task of the workflow that has none yet: {@code TicketCreated}, {@code TicketApproved}, and
     * the link, in the workflow's order. A task before acceptance depends on every work ticket that exists by then.
     */
    static void open(CompanyView view, Recorder r, MissionId mission, Workflow workflow) {
        Map<String, TicketId> linked = view.workflowLinks(mission);
        for (Step.Task task : workflow.tasks()) {
            if (linked.containsKey(task.key())) continue;
            TicketId id = view.nextTicketId();
            List<TicketId> after =
                    task.slot() == Step.Task.Slot.BEFORE_ACCEPTANCE ? workTickets(view, mission) : List.of();
            r.emit(new TicketCreated(
                    id,
                    mission,
                    task.title(),
                    task.does(),
                    Priority.NORMAL,
                    after,
                    List.of(task.handsOver()),
                    TicketKind.WORK));
            r.emit(new TicketApproved(id));
            r.emit(new WorkflowStepTicketOpened(mission, id, task.key()));
        }
    }

    /** The work tickets of the mission that are not cancelled, the tasks before acceptance left out. */
    private static List<TicketId> workTickets(CompanyView view, MissionId mission) {
        List<TicketId> out = new ArrayList<>();
        for (Ticket t : view.tickets(mission)) {
            if (t.kind() == TicketKind.WORK && t.status() != TicketStatus.CANCELLED && !acceptanceTask(view, t)) {
                out.add(t.id());
            }
        }
        return out;
    }

    /** Whether the ticket is a task of the slot before acceptance. */
    static boolean acceptanceTask(CompanyView view, Ticket t) {
        return view.workflowOf(t.mission())
                .runnable()
                .flatMap(w -> view.workflowStepOf(t).flatMap(w::task))
                .map(k -> k.slot() == Step.Task.Slot.BEFORE_ACCEPTANCE)
                .orElse(false);
    }

    /**
     * The work of a mission changed (a ticket finished or was reopened): each task before acceptance already done
     * runs again, since it judged work that is not the work anymore.
     */
    static void workChanged(CompanyView view, Recorder r, Ticket changed) {
        if (changed.kind() != TicketKind.WORK || view.workflowStepOf(changed).isPresent()) return;
        for (TicketId id : view.workflowLinks(changed.mission()).values()) {
            Ticket task = view.existingTicket(id);
            if (task.status() == TicketStatus.DONE && acceptanceTask(view, task)) {
                r.emit(new TicketReopened(id, WORK_CHANGED));
            }
        }
    }
}
