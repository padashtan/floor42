package dev.floor42.server.app;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.workflow.MissionInterpreter;
import dev.floor42.domain.workflow.WorkflowInterpreter.Position;
import dev.floor42.domain.workflow.WorkflowInterpreter.TicketStep;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Whether a mission is stuck, by the dispatcher's own rule ({@link TaskSelection}): STUCK only when it would start a
 * task now, with the agents busy and held as they are, yet nothing of the mission runs and nothing has happened to it
 * for the stall setting. What rightly holds work back is named instead (WAITING). Read only: nothing starts here.
 */
@ApplicationScoped
public class StallDiagnosis {

    // Sets that accept a null lookup (Set.of() does not): a question task has no agent.
    private static final Set<TicketId> NONE = Collections.emptySet();
    private static final Set<AgentId> NOBODY = Collections.emptySet();

    private final OfficeService office;
    private final AgentReservations reservations;
    private final MissionHolds holds;
    private final HeldWork heldWork;
    private final MissionSigns signs;
    private final MissionInterpreter workflow;

    @Inject
    StallDiagnosis(
            OfficeService office,
            AgentReservations reservations,
            MissionHolds holds,
            HeldWork heldWork,
            MissionSigns signs,
            MissionInterpreter workflow) {
        this.office = office;
        this.reservations = reservations;
        this.holds = holds;
        this.heldWork = heldWork;
        this.signs = signs;
        this.workflow = workflow;
    }

    /** What the board knows of the mission now, measured outside the office monitor (git), as the dispatcher does. */
    private record Seen(Set<AgentId> held, Set<AgentId> busy, Instant since, Duration patience, Instant now) {}

    /**
     * WAITING names an open question, a sandbox measure after "I install it", work held in the workshop, a committee's
     * ruling, proposed tickets, a rest until a usage window resets, or an agent at work on another mission.
     */
    public Stall stall(MissionId m, Instant now) {
        if (reservations.running(m)) return Stall.of("RUNNING", null);
        // Only real missions in progress are driven by the dispatcher; the demo plays its own script.
        boolean driven = office.query(c -> c.missions()
                .find(m)
                .map(x -> x.origin() == Origin.REAL && x.status() == MissionStatus.IN_PROGRESS)
                .orElse(false));
        if (!driven) return Stall.of("IDLE", "NOT_DISPATCHED");
        Instant rest = holds.restingUntil(m);
        if (rest != null && rest.isAfter(now))
            return new Stall("WAITING", "RESTING", null, null, null, null, null, rest);
        Seen seen = new Seen(
                heldWork.held(m).keySet(),
                new HashSet<>(reservations.busyAgents()),
                signs.since(m),
                signs.patience(),
                now);
        return office.query(c -> diagnose(c, m, seen));
    }

    private Stall diagnose(Company c, MissionId m, Seen seen) {
        Task task = selection(c, m).next(NONE, seen.held(), seen.busy());
        if (task != null) return wouldStart(task, seen);
        List<Question> open = c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN)
                .toList();
        Optional<Question> holding =
                open.stream().filter(MissionHolds::holdsMission).findFirst();
        if (holding.isPresent()) return waitingOn(holding.get());
        // "I install it" answered, its new measure not journaled yet (in progress, or its command failed): held.
        Optional<Question> install = MissingTools.awaited(c, m);
        if (install.isPresent()) return measuring(install.get());
        Stall waiting = waitingForOthers(c, m, seen);
        if (waiting != null) return waiting;
        Optional<Ticket> proposed = c.tickets().of(m).stream()
                .filter(t -> t.status() == TicketStatus.PROPOSED)
                .findFirst();
        if (proposed.isPresent()) {
            return new Stall("WAITING", "PROPOSED", null, proposed.get().id().value(), null, null, null, null);
        }
        if (!open.isEmpty()) return waitingOn(open.getFirst());
        return untaken(c, m).orElse(Stall.of("IDLE", "NOTHING"));
    }

    /**
     * A refused workflow, or an open ticket no step of the mission's workflow takes (a ticket the director added to a
     * decision workflow): named rather than left waiting in silence (ADR-0050).
     */
    private Optional<Stall> untaken(Company c, MissionId m) {
        if (workflow.position(c, m) instanceof Position.Refused) {
            return Optional.of(Stall.of("WAITING", "WORKFLOW_UNREADABLE"));
        }
        Optional<Stall> unreviewed = awaitingReviewer(c, m);
        if (unreviewed.isPresent()) return unreviewed;
        java.util.Set<TicketId> committees = new HashSet<>();
        c.committees().of(m).forEach(k -> committees.add(k.ticket()));
        return c.tickets().of(m).stream()
                .filter(t -> t.status() == TicketStatus.TODO || t.status() == TicketStatus.IN_PROGRESS)
                .filter(t -> !committees.contains(t.id()))
                .filter(t -> workflow.step(c, t, TaskSelection.roleOf(c, t)) == TicketStep.NONE)
                .findFirst()
                .map(t -> new Stall("WAITING", "NO_STEP_TAKES_IT", null, t.id().value(), null, null, null, null));
    }

    /**
     * A ticket in review whose assignee is the only active reviewer: nobody else may read it (a review is never its
     * producer's), so it waits until the director hires a reviewer (ADR-0061: a reviewed task of a reviewer).
     */
    private Optional<Stall> awaitingReviewer(Company c, MissionId m) {
        boolean other = Staff.active(c, dev.floor42.domain.WorkRole.REVIEWER).size() > 1;
        if (other) return Optional.empty();
        return c.tickets().of(m).stream()
                .filter(t -> t.status() == TicketStatus.IN_REVIEW
                        && t.assignee() != null
                        && Staff.active(c, dev.floor42.domain.WorkRole.REVIEWER).stream()
                                .allMatch(a -> a.id().equals(t.assignee()))
                        && workflow.step(c, t, TaskSelection.roleOf(c, t)) != TicketStep.NONE)
                .findFirst()
                .map(t -> new Stall("WAITING", "NEEDS_SECOND_REVIEWER", null, t.id().value(), null, null, null, null));
    }

    /** The dispatcher would start this task now: due, or stuck once nothing happened for the stall setting. */
    private static Stall wouldStart(Task task, Seen seen) {
        boolean stuck = !seen.now().isBefore(seen.since().plus(seen.patience()));
        String who = task.agent() == null ? null : task.agent().value();
        return new Stall(
                stuck ? "STUCK" : "DUE",
                "WOULD_START",
                task.work().name(),
                task.ticket().value(),
                who,
                null,
                seen.since(),
                null);
    }

    /** The committee's ruling, an agent at work elsewhere, or work held in the workshop; null when none holds it. */
    private Stall waitingForOthers(Company c, MissionId m, Seen seen) {
        // The committee the workflow interpreter says waits for the ruling (ADR-0046).
        if (workflow.position(c, m) instanceof Position.AwaitingRuling r) {
            String ticket =
                    c.committees().find(r.committee()).orElseThrow().ticket().value();
            return new Stall("WAITING", "COMMITTEE_DIRECTOR", null, ticket, null, null, null, null);
        }
        Stall busy = waitingForAgent(c, m, seen);
        if (busy != null) return busy;
        return seen.held().isEmpty() ? null : waitingForPatch(c, m, seen.held());
    }

    /** Work that would start if the agents at work elsewhere were free: the mission waits its turn. */
    private Stall waitingForAgent(Company c, MissionId m, Seen seen) {
        Task free = selection(c, m).next(NONE, seen.held(), NOBODY);
        if (free == null) return null;
        AgentId who = free.reserved().stream()
                .filter(a -> a != null && seen.busy().contains(a))
                .findFirst()
                .orElse(free.agent());
        MissionId where = busyOn(c, who);
        return new Stall(
                "WAITING",
                "AGENT_BUSY",
                free.work().name(),
                free.ticket().value(),
                who == null ? null : who.value(),
                where == null ? null : where.value(),
                null,
                null);
    }

    private Stall waitingForPatch(Company c, MissionId m, Set<AgentId> held) {
        Task unheld = selection(c, m).next(NONE, NOBODY, NOBODY);
        if (unheld == null) return null;
        AgentId who =
                held.contains(unheld.agent()) ? unheld.agent() : held.iterator().next();
        return new Stall(
                "WAITING", "HELD_PATCH", unheld.work().name(), unheld.ticket().value(), who.value(), null, null, null);
    }

    private TaskSelection selection(Company c, MissionId m) {
        return new TaskSelection(c, m, holds.restingUntil(m), workflow);
    }

    private static Stall waitingOn(Question q) {
        return new Stall(
                "WAITING",
                "QUESTION",
                null,
                q.ticket() == null ? null : q.ticket().value(),
                q.askedBy() == null ? null : q.askedBy().value(),
                null,
                q.askedAt(),
                null);
    }

    /** The sandbox's new measure after an "I install it" answer, which the mission waits for. */
    private static Stall measuring(Question q) {
        return new Stall(
                "WAITING",
                "SANDBOX_MEASURE",
                null,
                q.ticket() == null ? null : q.ticket().value(),
                null,
                null,
                q.askedAt(),
                null);
    }

    /** The mission an agent at work belongs to: the dispatcher's reservation, else its open session. */
    private MissionId busyOn(Company c, AgentId agent) {
        if (agent == null) return null;
        MissionId reserved = reservations.reservedOn(agent);
        if (reserved != null) return reserved;
        return c.missions().all().stream()
                .filter(x -> c.sessions().of(x.id()).stream()
                        .anyMatch(s -> s.isOpen() && s.agent().equals(agent)))
                .map(Mission::id)
                .findFirst()
                .orElse(null);
    }
}
