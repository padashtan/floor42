package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.workflow.MissionInterpreter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the dispatcher picks its next task: chosen and claimed under the {@link DispatchLock}, from what the journal
 * says now, so a request made while another pass runs is never lost and never doubled.
 */
@ApplicationScoped
public class DispatchBoard {

    private final OfficeService office;
    private final DispatchLock lock;
    private final AgentReservations reservations;
    private final HeldWork heldWork;
    private final MissionHolds holds;
    private final MissionInterpreter workflow;

    /** Tickets whose dependency question is being asked, outside the lock: asked once, not by two passes. */
    private final Set<TicketId> askingDependency = ConcurrentHashMap.newKeySet();

    @Inject
    DispatchBoard(
            OfficeService office,
            DispatchLock lock,
            AgentReservations reservations,
            HeldWork heldWork,
            MissionHolds holds,
            MissionInterpreter workflow) {
        this.office = office;
        this.lock = lock;
        this.reservations = reservations;
        this.heldWork = heldWork;
        this.holds = holds;
        this.workflow = workflow;
    }

    /** The real missions in progress: the only ones the dispatcher drives (the demo plays its own script). */
    List<MissionId> inProgress() {
        return office.query(c -> c.missions().all().stream()
                .filter(m -> m.origin() == Origin.REAL && m.status() == MissionStatus.IN_PROGRESS)
                .map(Mission::id)
                .toList());
    }

    /** Missions a session may wait for: planning (the plan's session) or in progress. Approval waits on the director. */
    List<MissionId> workable() {
        return office.query(c -> c.missions().all().stream()
                .filter(m -> m.origin() == Origin.REAL
                        && (m.status() == MissionStatus.IN_PROGRESS || m.status() == MissionStatus.PLANNING))
                .map(Mission::id)
                .toList());
    }

    /**
     * A ticket back in progress whose developer has left (a review rejected their delivery after the dismissal) goes
     * back to the queue: otherwise it would wait for someone who never comes.
     */
    void requeueLeftBehind(MissionId m) {
        List<TicketId> left = office.query(c -> c.tickets().of(m).stream()
                .filter(t -> t.status() == TicketStatus.IN_PROGRESS
                        && t.assignee() != null
                        && c.staff().find(t.assignee()).map(Agent::dismissed).orElse(false))
                .map(Ticket::id)
                .toList());
        for (TicketId t : left) {
            office.execute(c -> c.tickets().release(office.context(Actor.system(), Origin.REAL), t));
        }
    }

    /**
     * The next task of the mission, its ticket and agents claimed; a dependency question is claimed by its ticket
     * instead, until {@link #asked}. Empty when nothing may start now. The held worktrees are measured first, with git,
     * outside the lock every mission shares.
     */
    Optional<Task> pick(MissionId m) {
        // Never a simulated mission (the demo, a rehearsal), whoever asks: it plays its own script, no model runs.
        if (!real(m)) return Optional.empty();
        Set<AgentId> held = heldWork.held(m).keySet();
        return lock.holding(() -> {
            if (reservations.full(m)) return Optional.empty();
            Task task = office.query(c -> new TaskSelection(c, m, holds.restingUntil(m), workflow)
                    .next(reservations.tickets(m), held, reservations.busyAgents()));
            if (task == null) return Optional.empty();
            if (task.work() == Work.ASK_DEPENDENCY) {
                // Another pass is asking already: it goes on looking once the question is on the desk.
                return askingDependency.add(task.ticket()) ? Optional.of(task) : Optional.empty();
            }
            reservations.claim(m, task);
            return Optional.of(task);
        });
    }

    private boolean real(MissionId m) {
        return office.query(
                c -> c.missions().find(m).map(x -> x.origin() == Origin.REAL).orElse(false));
    }

    /** The dependency question of this ticket is on the desk, or could not be asked. */
    void asked(TicketId ticket) {
        askingDependency.remove(ticket);
    }

    /** The task ended: its ticket and agents are free again. */
    void free(MissionId m, Task task) {
        reservations.free(m, task);
    }
}
