package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.jboss.logging.Logger;

/**
 * Before a ticket whose dependencies were done by other developers starts, Floor42 merges their branches into the
 * worktree of the developer taking it, and records the merge (D10). Done once per ticket.
 */
@ApplicationScoped
public class DependencyMerge {

    private static final Logger LOG = Logger.getLogger(DependencyMerge.class);

    private final OfficeService office;
    private final Workshop workshop;
    private final AgentWorktrees worktrees;
    private final Workspaces workspaces;

    @Inject
    DependencyMerge(OfficeService office, Workshop workshop, AgentWorktrees worktrees, Workspaces workspaces) {
        this.office = office;
        this.workshop = workshop;
        this.worktrees = worktrees;
        this.workspaces = workspaces;
    }

    /**
     * False when the merge failed: the ticket does not start, and the dispatcher asks the director (go on without it,
     * or drop it).
     */
    boolean merge(MissionId m, TicketId id, AgentId agent) {
        List<Workspace> plan = office.query(c -> {
            Ticket t = c.tickets().find(id).orElseThrow();
            if (t.status() != TicketStatus.TODO
                    || c.workshop().merge(id).isPresent()
                    || Dependencies.wentOnWithout(c, t)) return null;
            return Dependencies.producersElsewhere(c, t).stream()
                    .filter(p -> !p.equals(agent))
                    .map(p -> c.workshop().workspace(m, p).orElse(null))
                    .filter(Objects::nonNull)
                    .toList();
        });
        if (plan == null || plan.isEmpty()) return true;
        List<String> branches = plan.stream().map(Workspace::branch).distinct().toList();
        if (!nothingPending(id, agent, plan, branches)) return false;
        Path worktree = worktrees.ready(m, agent);
        Workspaces.Merge merged = workspaces.merge(worktree, branches);
        office.execute(c -> c.workshop().recordMerge(ctx(), id, agent, branches, merged.clean(), merged.detail()));
        LOG.infof(
                "Mission %s: %s merged into %s for %s: %s",
                m, branches, agent, id, merged.clean() ? "clean" : merged.detail());
        return merged.clean();
    }

    /**
     * A merge brings only what is committed, that is what the director accepted: work still pending in a worktree
     * would be silently left behind, so the director decides instead: the refused merge is recorded.
     */
    private boolean nothingPending(TicketId id, AgentId agent, List<Workspace> plan, List<String> branches) {
        List<String> pending =
                plan.stream().filter(this::pending).map(Workspace::branch).toList();
        if (pending.isEmpty()) return true;
        String detail = "work not accepted yet on " + String.join(", ", pending) + ": a merge would leave it behind";
        office.execute(c -> c.workshop().recordMerge(ctx(), id, agent, branches, false, detail));
        return false;
    }

    private boolean pending(Workspace w) {
        try {
            return !workshop.changes(w).isEmpty();
        } catch (RuntimeException e) {
            // Handled: changes that cannot be read count as changes: nothing is skipped on a guess
            return true;
        }
    }

    private CommandContext ctx() {
        return office.context(Actor.system(), Origin.REAL);
    }
}
