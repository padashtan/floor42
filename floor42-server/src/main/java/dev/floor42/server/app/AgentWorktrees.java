package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import org.jboss.logging.Logger;

/** Each agent's git worktree of a mission, recorded in the journal so the workshop shows it. */
@ApplicationScoped
public class AgentWorktrees {

    private static final Logger LOG = Logger.getLogger(AgentWorktrees.class);

    private final OfficeService office;
    private final Workspaces workspaces;

    @Inject
    AgentWorktrees(OfficeService office, Workspaces workspaces) {
        this.office = office;
        this.workspaces = workspaces;
    }

    /** The agent's worktree, without recording it: for those who only read another's work. */
    Path path(MissionId m, AgentId agent) {
        Mission mission = office.query(c -> c.missions().find(m).orElseThrow());
        // A folder the journal does not know for this mission is not reused: it comes from another timeline.
        java.util.Optional<String> known =
                office.query(c -> c.workshop().workspace(m, agent)).map(dev.floor42.domain.Workspace::path);
        return workspaces.worktree(
                mission.workspace(),
                m,
                agent,
                p -> known.isPresent()
                        && Path.of(known.get())
                                .toAbsolutePath()
                                .normalize()
                                .equals(p.toAbsolutePath().normalize()));
    }

    /** The agent's worktree, created and recorded with its branch and base commit the first time. */
    Path ready(MissionId m, AgentId agent) {
        Path worktree = path(m, agent);
        if (office.query(c -> c.workshop().workspace(m, agent)).isEmpty()) {
            String branch = workspaces.branch(worktree);
            String base = workspaces.head(worktree);
            office.execute(c -> c.workshop().prepareWorkspace(ctx(), m, agent, worktree.toString(), branch, base));
        }
        return worktree;
    }

    /** An empty folder for a research session, which never sees the repository. */
    Path research(MissionId m) {
        return workspaces.research(m);
    }

    /**
     * Worktrees created before the journal recorded workspaces (before I5) are recorded once, with their current
     * commit as base: the workshop then shows them like any other.
     */
    void recordKnown() {
        List<Mission> real = office.query(c -> c.missions().all().stream()
                .filter(m -> m.origin() == Origin.REAL
                        && m.workspace() != null
                        && !m.status().isTerminal())
                .toList());
        for (Mission m : real) {
            List<AgentId> producers = office.query(c -> c.tickets().of(m.id()).stream()
                    .map(Ticket::assignee)
                    .filter(a -> a != null && c.workshop().workspace(m.id(), a).isEmpty())
                    .distinct()
                    .toList());
            for (AgentId agent : producers) recordExisting(m.id(), agent);
        }
    }

    private void recordExisting(MissionId m, AgentId agent) {
        workspaces.existing(m, agent).ifPresent(path -> {
            try {
                String branch = workspaces.branch(path);
                String head = workspaces.head(path);
                office.execute(c -> c.workshop().prepareWorkspace(ctx(), m, agent, path.toString(), branch, head));
            } catch (RuntimeException e) {
                // Handled: logged; the worktree is there, only its record is missing
                LOG.warnf("Workspace %s of %s not recorded: %s", agent, m, e.getMessage());
            }
        });
    }

    private CommandContext ctx() {
        return office.context(Actor.system(), Origin.REAL);
    }
}
