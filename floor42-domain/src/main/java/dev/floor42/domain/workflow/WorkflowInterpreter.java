package dev.floor42.domain.workflow;

import dev.floor42.domain.Committee;
import dev.floor42.domain.CommitteeRef;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketKind;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.event.CommitteeEvent.CommitteeArbitrated;
import dev.floor42.domain.workflow.Workflow.Step;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads a mission's state against a {@link Workflow} and says which step comes next (ADR-0046). It decides; the
 * daemon acts: the dispatcher picks the agents, the runners hold the sessions. It reads the company, never changes it,
 * and holds nothing but its workflow: the same state always gives the same answer.
 */
public final class WorkflowInterpreter {

    /** How the director asked for a new mission to be prepared. */
    public enum Preparation {
        /** The planner splits the objective into tickets the director approves one by one (ADR-0017). */
        BY_PLANNER,
        /** One ticket delivers the objective. */
        ONE_TICKET
    }

    /** How a new mission opens. */
    public sealed interface Opening {
        /** A decision mission: a committee of this panel analyses and debates, read only. */
        record ConveneCommittee(String panel) implements Opening {}

        /** The planner's ticket first. */
        record PlanWithPlanner() implements Opening {}

        /** One ticket for a developer. */
        record DeliverInOneTicket() implements Opening {}

        /** Tasks and nothing to plan or produce (ADR-0061): the mission waits for the approval with no ticket. */
        record AwaitStepApproval() implements Opening {}
    }

    /** Where a mission stands in its workflow. */
    public sealed interface Position {
        /** Its plan is being drafted. */
        record Drafting() implements Position {}

        /** Its plan waits for the director's approval. */
        record AwaitingApproval() implements Position {}

        /** Approved, and this committee is in session: it is the mission's work now. */
        record Deliberating(CommitteeRef committee) implements Position {}

        /**
         * Approved: its tickets are worked, each produced then reviewed. A committee's decision ticket is never worked
         * as a ticket: {@code committeeTickets} lists them, to leave out.
         */
        record WorkingTickets(List<TicketId> committeeTickets) implements Position {
            public WorkingTickets {
                committeeTickets = List.copyOf(committeeTickets);
            }
        }

        /**
         * Approved, and this committee waits for the director's ruling. Its tickets are worked meanwhile, as in
         * {@link WorkingTickets}, the committees' decision tickets left out.
         */
        record AwaitingRuling(CommitteeRef committee, List<TicketId> committeeTickets) implements Position {
            public AwaitingRuling {
                committeeTickets = List.copyOf(committeeTickets);
            }
        }

        /** Suspended after an incident: nothing runs until the director resumes it. */
        record Suspended() implements Position {}

        /** Completed or cancelled. */
        record Ended(MissionStatus status) implements Position {}

        /**
         * Its pinned workflow cannot be run by this build (ADR-0050): nothing runs, the reason is shown. Only
         * {@link MissionInterpreter} answers it; a {@code WorkflowInterpreter} always holds a workflow it runs.
         */
        record Refused(String reason) implements Position {}
    }

    /** What a ticket needs now: one of Floor42's steps, or one of the director's checks or tasks. */
    public sealed interface TicketStep {
        /** The planner's session proposes the mission's tickets. */
        TicketStep PLAN = Basic.PLAN;
        /** A developer's session produces it. */
        TicketStep PRODUCE = Basic.PRODUCE;
        /** An independent reviewer reads it. */
        TicketStep REVIEW = Basic.REVIEW;
        /** Nothing: done, cancelled, waiting for approval. */
        TicketStep NONE = Basic.NONE;

        /** {@code PLAN}, {@code PRODUCE}, {@code REVIEW}, {@code NONE}, {@code CHECK} or {@code TASK}. */
        String name();

        /** Floor42's own steps. */
        enum Basic implements TicketStep {
            PLAN,
            PRODUCE,
            REVIEW,
            NONE
        }

        /**
         * One of the director's checks (ADR-0052): {@code CHECK(key)}, at this position of this submission. The
         * dispatcher hands the pair to the session, and its verdict is recorded only while the ticket still stands there.
         */
        record Check(Step.Check check, StepAt at) implements TicketStep {
            public String key() {
                return check.key();
            }

            @Override
            public String name() {
                return "CHECK";
            }
        }

        /** One of the director's tasks (ADR-0061): the ticket the plan's approval opened for it is worked as a whole. */
        record Task(Step.Task task) implements TicketStep {
            public String key() {
                return task.key();
            }

            @Override
            public String name() {
                return "TASK";
            }
        }
    }

    /** What follows the director's ruling on a committee. */
    public sealed interface AfterRuling {
        /** Adopted for implementation: Floor42 plans the implementation ticket. */
        record Implement(CommitteeRef committee) implements AfterRuling {}

        /** Adopted as a decision only, or rejected: the decision ticket was the work. */
        record Decided(CommitteeRef committee) implements AfterRuling {}
    }

    /** What follows a mission's end. */
    public sealed interface AfterEnd {
        record HoldRetro(MissionId mission) implements AfterEnd {}

        record NoRetro(MissionId mission) implements AfterEnd {}
    }

    private final Workflow workflow;

    public WorkflowInterpreter(Workflow workflow) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
    }

    public Workflow workflow() {
        return workflow;
    }

    /**
     * How a mission opens: convened members make it a decision mission, else the planner plans it when asked, else one
     * ticket delivers it. The checks of what was asked (members of an idea committee, a repository) are the caller's.
     */
    public Opening opening(List<String> members, String panel, Preparation preparation) {
        if (members != null && !members.isEmpty() && workflow.has(Step.Committee.class)) {
            return new Opening.ConveneCommittee(panel);
        }
        if (workflow.opensOnStepApproval()) return new Opening.AwaitStepApproval();
        return switch (preparation) {
            case BY_PLANNER ->
                workflow.has(Step.Plan.class) ? new Opening.PlanWithPlanner() : new Opening.DeliverInOneTicket();
            case ONE_TICKET -> new Opening.DeliverInOneTicket();
        };
    }

    /** Where the mission stands now. */
    public Position position(Company c, MissionId m) {
        Mission mission = c.missions().find(m).orElseThrow();
        return switch (mission.status()) {
            case PLANNING -> new Position.Drafting();
            case AWAITING_APPROVAL -> new Position.AwaitingApproval();
            case IN_PROGRESS -> inProgress(c, m);
            case SUSPENDED -> new Position.Suspended();
            case COMPLETED, CANCELLED -> new Position.Ended(mission.status());
        };
    }

    /** A committee in session holds the mission; otherwise its tickets are the work, a ruling awaited or not. */
    private Position inProgress(Company c, MissionId m) {
        Optional<Committee> latest = c.committees().latest(m);
        List<TicketId> committeeTickets =
                c.committees().of(m).stream().map(Committee::ticket).toList();
        if (latest.isEmpty() || !workflow.has(Step.Committee.class)) {
            return new Position.WorkingTickets(committeeTickets);
        }
        Committee k = latest.get();
        if (k.deliberating()) return new Position.Deliberating(k.ref());
        if (k.stage() == Committee.Stage.AWAITING_DIRECTOR)
            return new Position.AwaitingRuling(k.ref(), committeeTickets);
        return new Position.WorkingTickets(committeeTickets);
    }

    /**
     * What the ticket needs now. A planning ticket (recorded as such, never read from its title) is planned only by the
     * planner, on a mission that validates its tickets and convened no committee.
     *
     * @param assigneeRole the work role of the ticket's assignee, or null when it has none
     */
    public TicketStep step(Company c, Ticket t, WorkRole assigneeRole) {
        Optional<String> linked = c.workflows().stepOf(t);
        if (linked.isPresent()) return taskStep(workflow.task(linked.get()), t);
        return switch (t.status()) {
            case TODO, IN_PROGRESS -> plans(c, t, assigneeRole) ? TicketStep.PLAN : produces();
            case IN_REVIEW -> reviewed(c, t);
            case PROPOSED, DONE, CANCELLED -> TicketStep.NONE;
        };
    }

    /**
     * A ticket a task opened: the task while it is worked, its review when it is reviewed, never a check. A key the
     * pin does not hold is taken by no step: the stall diagnosis names it and nothing starts.
     */
    private static TicketStep taskStep(Optional<Step.Task> task, Ticket t) {
        if (task.isEmpty()) return TicketStep.NONE;
        return switch (t.status()) {
            case TODO, IN_PROGRESS -> new TicketStep.Task(task.get());
            case IN_REVIEW -> task.get().reviewed() ? TicketStep.REVIEW : TicketStep.NONE;
            case PROPOSED, DONE, CANCELLED -> TicketStep.NONE;
        };
    }

    /** In review: the review at position 0 of the submission, then each check in turn (ADR-0052). */
    private TicketStep reviewed(Company c, Ticket t) {
        if (!workflow.has(Step.Review.class)) return TicketStep.NONE;
        if (workflow.forNode(c.workflows().checksRun(t)).checks().isEmpty()) return TicketStep.REVIEW;
        CheckProgress p = progress(c, t);
        return p.check()
                .<TicketStep>map(k -> new TicketStep.Check(k, p.at()))
                .orElse(p.at().position() == 0 ? TicketStep.REVIEW : TicketStep.NONE);
    }

    /** Where the ticket's current submission stands among the review and the checks, derived from the journal. */
    public CheckProgress progress(Company c, Ticket t) {
        return CheckProgress.of(
                workflow.forNode(c.workflows().checksRun(t)),
                t.reviews(),
                c.tickets().reviewRounds(t.id()));
    }

    private TicketStep produces() {
        return workflow.has(Step.Produce.class) ? TicketStep.PRODUCE : TicketStep.NONE;
    }

    private boolean plans(Company c, Ticket t, WorkRole assigneeRole) {
        return workflow.has(Step.Plan.class)
                && t.assignee() != null
                && assigneeRole == WorkRole.PLANNER
                && t.kind() == TicketKind.PLANNING
                && c.missions().find(t.mission()).orElseThrow().ticketValidation()
                && c.committees().of(t.mission()).isEmpty();
    }

    /** After the ruling: implementation when the director asked for it and the workflow produces after committees. */
    public AfterRuling afterRuling(CommitteeArbitrated ruling) {
        CommitteeRef ref = new CommitteeRef(ruling.mission(), ruling.committeeNo());
        boolean produces = workflow.producesAfterCommittees();
        return ruling.adopted() && ruling.implement() && produces
                ? new AfterRuling.Implement(ref)
                : new AfterRuling.Decided(ref);
    }

    /** After the end: the retrospective, when the workflow holds one. Whether anyone worked on it is the caller's. */
    public AfterEnd afterEnd(MissionId m) {
        return workflow.has(Step.Retro.class) ? new AfterEnd.HoldRetro(m) : new AfterEnd.NoRetro(m);
    }
}
