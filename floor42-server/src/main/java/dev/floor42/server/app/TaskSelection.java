package dev.floor42.server.app;

import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Committee;
import dev.floor42.domain.Company;
import dev.floor42.domain.ConsultationKind;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.NodePlan;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.workflow.MissionInterpreter;
import dev.floor42.domain.workflow.WorkflowInterpreter.Position;
import dev.floor42.domain.workflow.WorkflowInterpreter.TicketStep;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The dispatcher's rule for one mission, read under the office monitor: the next task that can start, or null. The
 * workflow interpreter says which step the mission is at and what each ticket needs (ADR-0046); this class picks the
 * agents. Nothing starts while the mission waits for the director. Reviews first (they free a developer), then tickets
 * under way, then approved tickets whose dependencies are done, in order. The stall diagnosis asks the same rule, so
 * both always agree.
 */
final class TaskSelection {

    private final Company c;
    private final MissionId m;
    private final Instant restingUntil;
    private final MissionInterpreter workflow;

    /** @param restingUntil until when the director rests the mission, or null */
    TaskSelection(Company c, MissionId m, Instant restingUntil, MissionInterpreter workflow) {
        this.c = c;
        this.m = m;
        this.restingUntil = restingUntil;
        this.workflow = workflow;
    }

    /**
     * @param mine the tickets a task already runs on here
     * @param held the developers whose worktree holds finished work not accepted yet
     * @param busy the agents at work now, across missions
     */
    Task next(Set<TicketId> mine, Set<AgentId> held, Set<AgentId> busy) {
        return switch (workflow.position(c, m)) {
            case Position.Deliberating d ->
                waiting()
                        ? null
                        : committeeTask(c.committees().find(d.committee()).orElseThrow(), mine, busy);
            case Position.WorkingTickets w ->
                waiting() ? null : ticketTask(candidates(w.committeeTickets(), mine), held, busy);
            case Position.AwaitingRuling r ->
                waiting() ? null : ticketTask(candidates(r.committeeTickets(), mine), held, busy);
            case Position.Drafting _,
                    Position.AwaitingApproval _,
                    Position.Suspended _,
                    Position.Ended _,
                    Position.Refused _ -> null;
        };
    }

    /** The mission waits for the director, or the director rests it. */
    private boolean waiting() {
        return MissionHolds.waiting(c, m) || (restingUntil != null && restingUntil.isAfter(Instant.now()));
    }

    private Task ticketTask(List<Ticket> all, Set<AgentId> held, Set<AgentId> busy) {
        Task task = reviewTask(all, busy);
        if (task == null) task = resumedTask(all, busy);
        if (task == null) task = readyTask(all, held, busy);
        return task;
    }

    /** Every member sits in turn: none may be running a session elsewhere. */
    private static Task committeeTask(Committee k, Set<TicketId> mine, Set<AgentId> busy) {
        List<AgentId> members = new ArrayList<>(k.memberIds());
        if (k.analyst() != null) members.add(k.analyst());
        if (!mine.isEmpty() || members.stream().anyMatch(busy::contains) || busy.contains(k.chair())) return null;
        return Task.committee(k.ref(), k.ticket(), k.chair(), members);
    }

    private List<Ticket> candidates(List<TicketId> committeeTickets, Set<TicketId> mine) {
        return c.tickets().of(m).stream()
                .filter(t -> !committeeTickets.contains(t.id()) && !mine.contains(t.id()))
                // A ticket waits for the director's other tasks and tickets (ADR-0061).
                .filter(t -> !workflow.held(c, t))
                // The planner's staffing review runs on its own, never through the dispatcher.
                .filter(t -> !c.staff().isStaffingTicket(t.id()))
                // A ticket the director was asked about (rejected too often, for instance) waits for the answer, and
                // one the director chose to leave stays put until something new happens to it.
                .filter(t -> c.desk().of(m).stream()
                        .noneMatch(q -> q.status() == QuestionStatus.OPEN && t.id().equals(q.ticket())))
                .filter(t -> !leftAlone(t))
                .toList();
    }

    private Task reviewTask(List<Ticket> all, Set<AgentId> busy) {
        for (Ticket t : all) {
            // The review, or one of the director's checks after it (ADR-0052): a reviewer runs both.
            TicketStep s = step(t);
            if (s != TicketStep.REVIEW && !(s instanceof TicketStep.Check)) continue;
            Ticket.Consultation last =
                    t.consultations().isEmpty() ? null : t.consultations().getLast();
            if (last != null && last.pending()) {
                if (busy.contains(last.reviewer())) continue;
                Optional<AgentId> helper = helperFor(t, last, busy);
                if (helper == null) continue; // the one who must answer is busy: the consultation waits for them
                return new Task(Work.CONSULT, t.id(), last.reviewer(), helper.orElse(null), List.of());
            }
            // A light node is reviewed on a cheaper model when one is free; the review is as independent as ever.
            AgentId reviewer = Staff.active(c, WorkRole.REVIEWER).stream()
                    .sorted(java.util.Comparator.comparingInt(a -> light(t) ? ModelTiers.rank(a.model()) : 0))
                    .map(Agent::id)
                    .filter(r -> !r.equals(t.assignee()) && !busy.contains(r))
                    .findFirst()
                    .orElse(null);
            if (reviewer != null) return new Task(Work.REVIEW, t.id(), reviewer);
        }
        return null;
    }

    /**
     * Back in progress (rejected, or a directive): only once its developer holds nothing else in review here, so a
     * review never reads changes of another ticket.
     */
    private Task resumedTask(List<Ticket> all, Set<AgentId> busy) {
        for (Ticket t : all) {
            TicketStep s = step(t);
            // A ticket no step of the mission's workflow takes is never started (ADR-0050): the stall names it.
            if (t.status() != TicketStatus.IN_PROGRESS || s == TicketStep.NONE) continue;
            if (Staff.free(c, t.assignee(), m, t.id(), busy)) {
                return new Task(workOf(s), t.id(), t.assignee());
            }
        }
        return null;
    }

    private Task readyTask(List<Ticket> all, Set<AgentId> held, Set<AgentId> busy) {
        for (Ticket t : all) {
            TicketStep s = step(t);
            if (t.status() != TicketStatus.TODO || s == TicketStep.NONE || !dependenciesDone(t)) continue;
            if (s == TicketStep.PLAN) {
                if (Staff.free(c, t.assignee(), m, t.id(), busy)) return new Task(Work.PLAN, t.id(), t.assignee());
                continue;
            }
            if (s instanceof TicketStep.Task k && k.task().runBy() != WorkRole.DEVELOPER) {
                AgentId agent = agentFor(k.task().runBy(), t, busy);
                if (agent != null) return new Task(Work.TASK, t.id(), agent);
                continue;
            }
            if (mustAskAboutDependencies(t, held)) return new Task(Work.ASK_DEPENDENCY, t.id(), null);
            AgentId developer = developerFor(t, held, busy);
            if (developer != null) return new Task(workOf(s), t.id(), developer);
        }
        return null;
    }

    private static Work workOf(TicketStep s) {
        return s == TicketStep.PLAN ? Work.PLAN : s instanceof TicketStep.Task ? Work.TASK : Work.PRODUCE;
    }

    /**
     * Who does a reviewer's or a researcher's task: the first of that role who is free. A researcher is hired on first
     * need, as the consultations' rule does.
     */
    private AgentId agentFor(WorkRole role, Ticket t, Set<AgentId> busy) {
        List<AgentId> staffed = Staff.active(c, role).stream().map(Agent::id).toList();
        if (staffed.isEmpty()
                && role == WorkRole.RESEARCHER
                && c.staff().find(StaffProvisioner.RESEARCHER).isEmpty()) {
            return StaffProvisioner.RESEARCHER;
        }
        return staffed.stream()
                .filter(a -> Staff.free(c, a, m, t.id(), busy))
                .findFirst()
                .orElse(null);
    }

    private boolean dependenciesDone(Ticket t) {
        return t.dependsOn().stream()
                .allMatch(d -> c.tickets()
                        .find(d)
                        .map(y -> y.status() == TicketStatus.DONE || y.status() == TicketStatus.CANCELLED)
                        .orElse(false));
    }

    /**
     * Floor42 merges the other developers' branches itself (D10); the director is asked only when that failed. Work
     * left unaccepted by a developer who has gone will never be accepted: no merge can bring it either.
     */
    private boolean mustAskAboutDependencies(Ticket t, Set<AgentId> held) {
        return !Dependencies.producersElsewhere(c, t).isEmpty()
                && (c.workshop().merge(t.id()).equals(Optional.of(false))
                        || !Dependencies.strandedWork(c, t, held).isEmpty())
                && !Dependencies.wentOnWithout(c, t);
    }

    /**
     * Who works a ready ticket: the developer who produced its dependencies, so it builds on their worktree; else its
     * assignee when free; else any free developer. Null when the right one is busy: the ticket waits for them.
     */
    private AgentId developerFor(Ticket t, Set<AgentId> held, Set<AgentId> busy) {
        // A developer whose worktree still holds a finished ticket the director has not accepted takes no new one:
        // its review would read both, and a rejection would undo both.
        List<AgentId> developers = Staff.active(c, WorkRole.DEVELOPER).stream()
                .map(Agent::id)
                .filter(d -> !held.contains(d))
                .toList();
        if (affinityHeld(t, held)) return null;
        AgentId affinity = t.dependsOn().stream()
                .map(d -> c.tickets().find(d).map(Ticket::assignee).orElse(null))
                .filter(a -> a != null && developers.contains(a))
                .findFirst()
                .orElse(null);
        if (affinity != null) return Staff.free(c, affinity, m, t.id(), busy) ? affinity : null;
        Optional<AgentId> cheaper = cheaperDeveloper(t, developers, busy);
        if (cheaper.isPresent()) return cheaper.get();
        if (t.assignee() != null && developers.contains(t.assignee()) && Staff.free(c, t.assignee(), m, t.id(), busy)) {
            return t.assignee();
        }
        // An assignee that cannot take it now (busy, suspended, gone) does not hold the queue: another developer does.
        return developers.stream()
                .filter(d -> Staff.free(c, d, m, t.id(), busy))
                .findFirst()
                .orElse(null);
    }

    /** Whether the ticket is a light-tier node of a graph mission (ADR-0062). */
    private boolean light(Ticket t) {
        return c.workflows().of(m).runnable().map(w -> w.graph()).orElse(false)
                && c.workflows()
                        .nodeOf(t.id())
                        .map(n -> n.tier() == NodePlan.Tier.LIGHT)
                        .orElse(false);
    }

    private int rank(AgentId agent) {
        return ModelTiers.rank(c.staff().find(agent).map(Agent::model).orElse(null));
    }

    /**
     * A light node goes to the cheapest developer who is free, when one costs less than the dearest on the team; the
     * team's permissions are the same, only the model differs. Empty otherwise: the usual choice stands.
     */
    private Optional<AgentId> cheaperDeveloper(Ticket t, List<AgentId> developers, Set<AgentId> busy) {
        if (!light(t)) return Optional.empty();
        int dearest = developers.stream().mapToInt(this::rank).max().orElse(0);
        return developers.stream()
                .filter(d -> Staff.free(c, d, m, t.id(), busy) && rank(d) < dearest)
                .min(java.util.Comparator.comparingInt(this::rank));
    }

    /** Work held by a developer who has left is never waited for: the director was asked about it (strandedWork). */
    private boolean affinityHeld(Ticket t, Set<AgentId> held) {
        Set<AgentId> onStaff =
                Staff.active(c, WorkRole.DEVELOPER).stream().map(Agent::id).collect(Collectors.toSet());
        return t.dependsOn().stream()
                .map(d -> c.tickets().find(d).map(Ticket::assignee).orElse(null))
                .anyMatch(a -> held.contains(a) && onStaff.contains(a));
    }

    /**
     * The agent who answers a pending consultation: present means a session runs (reserve that agent), empty means
     * Floor42 answers itself (no such role, the producer, nobody on staff), null means the right agent is busy.
     */
    private Optional<AgentId> helperFor(Ticket t, Ticket.Consultation h, Set<AgentId> busy) {
        if (h.kind() == ConsultationKind.CONSULT) {
            CommitteeRoles.Role role = CommitteeRoles.all().stream()
                    .filter(r -> r.key().equals(h.role()))
                    .findFirst()
                    .orElse(null);
            if (role == null || role.agent().equals(t.assignee())) return Optional.empty();
            if (c.staff().find(role.agent()).map(Agent::dismissed).orElse(false)) return Optional.empty();
            return busy.contains(role.agent()) ? null : Optional.of(role.agent());
        }
        List<AgentId> researchers =
                Staff.active(c, WorkRole.RESEARCHER).stream().map(Agent::id).toList();
        if (researchers.isEmpty()) {
            // Noe is hired on first need; once he has left, there is no researcher until one is hired.
            return c.staff().find(StaffProvisioner.RESEARCHER).isPresent()
                    ? Optional.empty()
                    : Optional.of(StaffProvisioner.RESEARCHER);
        }
        return researchers.stream()
                .filter(r -> !busy.contains(r))
                .findFirst()
                .map(Optional::of)
                .orElse(null);
    }

    /** The director answered "Leave it" about this ticket, and nothing ran on it since. */
    private boolean leftAlone(Ticket t) {
        Question last = c.desk().of(m).stream()
                .filter(q -> t.id().equals(q.ticket()) && q.status() == QuestionStatus.ANSWERED)
                .reduce((a, b) -> b)
                .orElse(null);
        if (last == null || !last.offers(OptionCode.RETRY) || last.answered(OptionCode.RETRY)) return false;
        Instant asked = last.askedAt();
        return c.sessions().of(m).stream()
                .noneMatch(s -> s.ticket().equals(t.id()) && s.startedAt().isAfter(asked));
    }

    /** What the workflow says the ticket needs, given its assignee's role. */
    private TicketStep step(Ticket t) {
        return workflow.step(c, t, roleOf(c, t));
    }

    /** The work role of the ticket's assignee, the first staff's fallback included; null without an assignee. */
    static WorkRole roleOf(Company c, Ticket t) {
        return t.assignee() == null
                ? null
                : c.staff().find(t.assignee()).map(Staff::roleOf).orElse(null);
    }
}
