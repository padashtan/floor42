package dev.floor42.server.app;

import dev.floor42.domain.AgentSession;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCancelled;
import dev.floor42.domain.event.TicketEvent.TicketCancelled;
import dev.floor42.server.runtime.RunningSessions;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * A cancelled ticket stops its session the same way: the session carries the reason in its notes.
 * A cancelled mission stops working at once: every Claude Code session still running on it is stopped, children too,
 * and one launched in the same instant is stopped before its brief is written. What such a session reports afterwards
 * changes nothing: the domain refuses a delivery, a review or a consultation on a closed mission.
 */
@ApplicationScoped
public class CancelledMissions {

    private static final Logger LOG = Logger.getLogger(CancelledMissions.class);

    /** The note each stopped session carries. */
    static final String REASON = "the director cancelled the mission: the session was stopped";

    /** The note of a session stopped because its ticket was cancelled. */
    static final String TICKET_REASON = "the director cancelled the ticket: the session was stopped";

    private final OfficeService office;
    private final MissionScheduler scheduler;
    private final RunningSessions sessions;

    CancelledMissions(OfficeService office, MissionScheduler scheduler, RunningSessions sessions) {
        this.office = office;
        this.scheduler = scheduler;
        this.sessions = sessions;
    }

    void listen(@Observes StartupEvent ignored) {
        // Subscribers run under the service lock: only start threads here, never wait for a process.
        office.subscribe(this::react);
    }

    void react(List<EventEnvelope> events) {
        // A mission cancelled in this batch stops all its sessions with its own note: its tickets add nothing.
        Set<MissionId> whole = events.stream()
                .filter(e -> e.event() instanceof MissionCancelled)
                .map(e -> ((MissionCancelled) e.event()).mission())
                .collect(Collectors.toSet());
        for (EventEnvelope e : events) {
            if (e.origin() == Origin.REAL && e.event() instanceof MissionCancelled c) {
                MissionId m = c.mission();
                scheduler.fork("cancel-" + m, () -> stop(m));
            } else if (e.origin() == Origin.REAL
                    && e.event() instanceof TicketCancelled c
                    && e.mission() != null
                    && !whole.contains(e.mission())) {
                scheduler.fork("cancel-" + c.ticket(), () -> stop(e.mission(), c.ticket()));
            }
        }
    }

    void stop(MissionId m) {
        int stopped = sessions.closeMission(m, REASON);
        if (stopped > 0) LOG.infof("Mission %s cancelled: %d running session(s) stopped", m, stopped);
    }

    void stop(MissionId m, TicketId ticket) {
        List<AgentSession> working = office.query(c -> c.sessions().of(m).stream()
                .filter(s -> s.isOpen() && s.ticket().equals(ticket))
                .toList());
        for (AgentSession s : working) {
            if (sessions.stop(s.agent(), TICKET_REASON)) {
                LOG.infof("Ticket %s cancelled: the session of %s was stopped", ticket, s.agent());
            }
        }
    }
}
