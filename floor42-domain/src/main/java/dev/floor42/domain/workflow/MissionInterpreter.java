package dev.floor42.domain.workflow;

import dev.floor42.domain.CommitteeRef;
import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketKind;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.event.CommitteeEvent.CommitteeArbitrated;
import dev.floor42.domain.workflow.Workflow.Step;
import dev.floor42.domain.workflow.WorkflowInterpreter.AfterEnd;
import dev.floor42.domain.workflow.WorkflowInterpreter.AfterRuling;
import dev.floor42.domain.workflow.WorkflowInterpreter.Position;
import dev.floor42.domain.workflow.WorkflowInterpreter.TicketStep;
import java.util.Optional;

/**
 * Each mission's own workflow, asked per mission (ADR-0050): the pinned one, the built-in one for a mission never
 * pinned. A mission whose pin this build cannot read is read but never run: its position is {@link Position.Refused}
 * until it ends, its tickets need nothing, no implementation follows a ruling and no retrospective its end. Holds
 * nothing: the same company always gives the same answers.
 */
public final class MissionInterpreter {

    /** The interpreter of the mission's workflow; empty when this build cannot run it. */
    public Optional<WorkflowInterpreter> of(Company c, MissionId m) {
        return c.workflows().of(m).runnable().map(WorkflowInterpreter::new);
    }

    public Position position(Company c, MissionId m) {
        Optional<WorkflowInterpreter> w = of(c, m);
        if (w.isPresent()) return w.get().position(c, m);
        MissionStatus status = c.missions().find(m).orElseThrow().status();
        if (status == MissionStatus.COMPLETED || status == MissionStatus.CANCELLED) {
            return new Position.Ended(status);
        }
        return new Position.Refused(reason(c, m));
    }

    public TicketStep step(Company c, Ticket t, WorkRole assigneeRole) {
        return of(c, t.mission()).map(w -> w.step(c, t, assigneeRole)).orElse(TicketStep.NONE);
    }

    /**
     * Where the ticket stands among the review and the checks, when the mission's workflow holds a check (ADR-0052);
     * empty otherwise: its verdicts are then recorded without a position, as before.
     */
    public Optional<CheckProgress> progress(Company c, Ticket t) {
        return of(c, t.mission())
                .filter(w -> !w.workflow()
                                .forNode(c.workflows().checksRun(t))
                                .checks()
                                .isEmpty()
                        && c.workflows().stepOf(t).isEmpty())
                .map(w -> w.progress(c, t));
    }

    /**
     * Whether the ticket waits for the director's other tasks and tickets (ADR-0061): a ticket of the work waits while a
     * task before production is open, a task waits for the tasks of its slot before it, and a task before acceptance
     * waits until every other work ticket is done or cancelled. The dispatcher and the stall diagnosis ask the same.
     */
    public boolean held(Company c, Ticket t) {
        Optional<Workflow> w = c.workflows().of(t.mission()).runnable();
        if (w.isEmpty() || w.get().tasks().isEmpty()) return false;
        Optional<Step.Task> task = c.workflows().stepOf(t).flatMap(w.get()::task);
        if (task.isPresent())
            return earlierTaskOpen(c, t.mission(), w.get(), task.get()) || waitsForWork(c, t, task.get());
        return t.kind() == TicketKind.WORK
                && w.get().tasks().stream()
                        .filter(k -> k.slot() == Step.Task.Slot.BEFORE_PRODUCTION)
                        .anyMatch(k -> open(c, t.mission(), k));
    }

    private static boolean waitsForWork(Company c, Ticket t, Step.Task task) {
        return task.slot() == Step.Task.Slot.BEFORE_ACCEPTANCE
                && c.tickets().of(t.mission()).stream()
                        .anyMatch(o -> o.kind() == TicketKind.WORK
                                && !o.id().equals(t.id())
                                && !isAcceptanceTask(c, o)
                                && o.status() != TicketStatus.DONE
                                && o.status() != TicketStatus.CANCELLED);
    }

    private static boolean isAcceptanceTask(Company c, Ticket o) {
        return c.workflows()
                .stepOf(o)
                .flatMap(k -> c.workflows().of(o.mission()).runnable().flatMap(w -> w.task(k)))
                .map(k -> k.slot() == Step.Task.Slot.BEFORE_ACCEPTANCE)
                .orElse(false);
    }

    /** The director's order is the run order: a task waits for the tasks before it in its slot. */
    private static boolean earlierTaskOpen(Company c, MissionId m, Workflow w, Step.Task task) {
        for (Step.Task k : w.tasks()) {
            if (k.equals(task)) return false;
            if (k.slot() == task.slot() && open(c, m, k)) return true;
        }
        return false;
    }

    /** Whether the ticket opened for the task exists and is not done or cancelled. */
    private static boolean open(Company c, MissionId m, Step.Task k) {
        return Optional.ofNullable(c.workflows().linked(m).get(k.key()))
                .flatMap(id -> c.tickets().find(id))
                .map(x -> x.status() != TicketStatus.DONE && x.status() != TicketStatus.CANCELLED)
                .orElse(false);
    }

    public AfterRuling afterRuling(Company c, CommitteeArbitrated ruling) {
        return of(c, ruling.mission())
                .map(w -> w.afterRuling(ruling))
                .orElse(new AfterRuling.Decided(new CommitteeRef(ruling.mission(), ruling.committeeNo())));
    }

    public AfterEnd afterEnd(Company c, MissionId m) {
        return of(c, m).map(w -> w.afterEnd(m)).orElse(new AfterEnd.NoRetro(m));
    }

    /** Why the mission does not run here; empty when it does. */
    public static String reason(Company c, MissionId m) {
        return c.workflows().of(m) instanceof MissionWorkflow.Unrunnable u ? u.reason() : "";
    }
}
