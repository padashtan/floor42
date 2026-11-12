package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.Optional;
import org.jboss.logging.Logger;

/** One developer session on a ticket, in that developer's worktree; the review is the dispatcher's next task. */
@ApplicationScoped
public class ProduceRunner implements TaskRunner {

    private static final Logger LOG = Logger.getLogger(ProduceRunner.class);

    private final OfficeService office;
    private final AgentWorktrees worktrees;
    private final SessionGate gate;
    private final DependencyMerge dependencies;
    private final Checks checks;
    private final NodeTests nodeTests;

    @Inject
    ProduceRunner(
            OfficeService office,
            AgentWorktrees worktrees,
            SessionGate gate,
            DependencyMerge dependencies,
            Checks checks,
            NodeTests nodeTests) {
        this.office = office;
        this.worktrees = worktrees;
        this.gate = gate;
        this.dependencies = dependencies;
        this.checks = checks;
        this.nodeTests = nodeTests;
    }

    @Override
    public Work work() {
        return Work.PRODUCE;
    }

    @Override
    public void run(MissionId m, Task task) {
        TicketId id = task.ticket();
        AgentId agent = task.agent();
        if (!dependencies.merge(m, id, agent)) return;
        SessionGate.take(office, id, agent);
        Path worktree = worktrees.ready(m, agent);
        SessionSpec spec = office.query(c -> {
            Ticket ticket = c.tickets().find(id).orElseThrow();
            Agent a = c.staff().find(agent).orElseThrow();
            Mission mission = c.missions().find(m).orElseThrow();
            return SessionGate.spec(
                    c, m, id, a, worktree, Briefs.producer(c, mission, ticket, a), Briefs.offeredTools(mission, id));
        });
        Optional<String> report = gate.reportContinuing(spec);
        if (report.isPresent()) deliver(m, id, agent, report.get());
    }

    /** The report goes to review; the automatic checks run after it, when the mission asks for them. */
    private void deliver(MissionId m, TicketId id, AgentId agent, String report) {
        // A graph node's tests run before the review (ADR-0062): a failure is a rejection, so it is not submitted.
        if (!nodeTests.mayBeReviewed(m, id, agent)) return;
        office.execute(c -> c.tickets().submitForReview(office.context(Actor.agent(agent), Origin.REAL), id, report));
        if (!office.query(c -> c.missions().find(m).orElseThrow().checksAutomatic())) return;
        try {
            checks.run(m, agent, "after delivery");
        } catch (RuntimeException e) {
            // Handled: The review goes on without them; the reviewer is told no checks ran.
            LOG.warnf("Mission %s: automatic checks did not run: %s", m, e.getMessage());
        }
    }
}
