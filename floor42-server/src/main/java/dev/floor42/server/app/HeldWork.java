package dev.floor42.server.app;

import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * The developers whose worktree holds the work of a finished ticket the director has not accepted or rejected yet.
 * Measured on the worktrees (git, up to a minute per call: never under the dispatch lock), not guessed.
 */
@ApplicationScoped
public class HeldWork {

    private static final Logger LOG = Logger.getLogger(HeldWork.class);

    private final OfficeService office;
    private final Workshop workshop;

    @Inject
    HeldWork(OfficeService office, Workshop workshop) {
        this.office = office;
        this.workshop = workshop;
    }

    /** Each held developer with the ticket it finished: a ticket that changed no file holds nobody. */
    public Map<AgentId, TicketId> held(MissionId m) {
        Map<AgentId, Workspace> spaces = new LinkedHashMap<>();
        Map<AgentId, TicketId> finished = new LinkedHashMap<>();
        office.query(c -> {
            for (Agent a : c.staff().all()) {
                if (Staff.roleOf(a) == WorkRole.DEVELOPER && !atWork(c, m, a.id()))
                    note(c, m, a.id(), spaces, finished);
            }
            return null;
        });
        Map<AgentId, TicketId> held = new LinkedHashMap<>();
        for (var e : spaces.entrySet()) {
            TicketId done = finished.get(e.getKey());
            if (done == null) continue;
            try {
                if (!workshop.changes(e.getValue()).isEmpty()) held.put(e.getKey(), done);
            } catch (RuntimeException ex) {
                // Handled: logged; that developer is not listed as holding work
                LOG.warnf("Mission %s: cannot read the worktree of %s: %s", m, e.getKey(), ex.getMessage());
            }
        }
        return held;
    }

    /** Changes in the worktree of a developer at work belong to the ticket under way, not to a finished one. */
    private static boolean atWork(Company c, MissionId m, AgentId a) {
        return c.tickets().of(m).stream()
                .anyMatch(t -> a.equals(t.assignee())
                        && (t.status() == TicketStatus.IN_PROGRESS || t.status() == TicketStatus.IN_REVIEW));
    }

    private static void note(
            Company c, MissionId m, AgentId a, Map<AgentId, Workspace> spaces, Map<AgentId, TicketId> finished) {
        c.workshop().workspace(m, a).ifPresent(w -> spaces.put(a, w));
        c.tickets().of(m).stream()
                .filter(t -> a.equals(t.assignee()) && t.status() == TicketStatus.DONE)
                .filter(t -> t.endedAt() != null)
                .max(Comparator.comparing(Ticket::endedAt))
                .ifPresent(t -> finished.put(a, t.id()));
    }
}
