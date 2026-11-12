package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.ProposedTicket;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The planner reads the repository and proposes tickets; Floor42 records them as proposals for the director. Nothing
 * proposed runs before the director approves it (ADR-0017).
 */
@ApplicationScoped
public class PlanRunner implements TaskRunner {

    private static final Logger LOG = Logger.getLogger(PlanRunner.class);

    private final OfficeService office;
    private final AgentWorktrees worktrees;
    private final SessionGate gate;
    private final RetryDesk retry;
    private final StaffProvisioner staff;
    private final MissingTools missingTools;
    private final DockerQuestion docker;

    @Inject
    PlanRunner(
            OfficeService office,
            AgentWorktrees worktrees,
            SessionGate gate,
            RetryDesk retry,
            StaffProvisioner staff,
            MissingTools missingTools,
            DockerQuestion docker) {
        this.office = office;
        this.worktrees = worktrees;
        this.gate = gate;
        this.retry = retry;
        this.staff = staff;
        this.missingTools = missingTools;
        this.docker = docker;
    }

    @Override
    public Work work() {
        return Work.PLAN;
    }

    @Override
    public void run(MissionId m, Task task) {
        // Values only leave the service lock, never the live ticket.
        record Planning(TicketId id, AgentId assignee, TicketStatus status) {}
        Planning t = office.query(c -> {
            Ticket x = c.tickets().find(task.ticket()).orElseThrow();
            return new Planning(x.id(), x.assignee(), x.status());
        });
        AgentId planner = t.assignee();
        if (t.status() == TicketStatus.TODO) {
            office.execute(c -> c.tickets().start(office.context(Actor.agent(planner), Origin.REAL), t.id()));
        }
        // The planner reads the developer's worktree: the one the planned tickets will change.
        AgentId developer = staff.staff(WorkRole.DEVELOPER);
        Path worktree = worktrees.ready(m, developer);
        SessionSpec spec = office.query(c -> {
            Agent a = c.staff().find(planner).orElseThrow();
            Mission mission = c.missions().find(m).orElseThrow();
            String brief = Briefs.planner(c, mission, c.tickets().find(t.id()).orElseThrow(), a);
            return SessionGate.spec(c, m, t.id(), a, worktree, brief, ReviewRunner.REVIEW_TOOLS);
        });
        Optional<String> report = gate.report(spec);
        if (report.isPresent()) {
            // A plan sent back for a retry proposes no workflow: the retry's report will, once.
            if (propose(m, t.id(), planner, developer, worktree, report.get()))
                proposeWorkflow(m, planner, report.get());
        }
    }

    /**
     * The planner may propose a workflow for the company's list (ADR-0050): recorded only once Floor42 has built it;
     * a line it refuses is left out, its reason logged. It never changes the mission being planned.
     */
    private void proposeWorkflow(MissionId m, AgentId planner, String report) {
        switch (WorkflowProposals.read(report)) {
            case WorkflowProposals.Proposal.None n -> {}
            case WorkflowProposals.Proposal.Refused r ->
                LOG.warnf("Mission %s: the planner's workflow proposal is left out: %s", m, r.reason());
            case WorkflowProposals.Proposal.Proposed p ->
                office.execute(
                        c -> c.workflows().propose(office.context(Actor.agent(planner), Origin.REAL), m, p.workflow()));
        }
    }

    /** A plan in the expected form, within the limit, becomes proposals; otherwise the director may retry. */
    private boolean propose(
            MissionId m, TicketId planning, AgentId planner, AgentId developer, Path worktree, String report) {
        boolean graph = office.query(
                c -> c.workflows().of(m).runnable().map(Workflow::graph).orElse(false));
        boolean extension =
                office.query(c -> GraphExtensions.is(c.tickets().find(planning).orElseThrow()));
        List<ProposedTicket> proposals =
                extension ? Briefs.extension(report) : graph ? Briefs.graph(report) : Briefs.plan(report);
        int limit = office.query(c -> PlannerBrief.maxTickets(c, m));
        if (proposals.isEmpty()) {
            retry.ask(
                    m,
                    planning,
                    planner,
                    "the plan has no ticket in the expected form (TICKET, WHY, DONE WHEN, DEPENDS ON, SIZE)");
            return false;
        } else if (proposals.size() > limit) {
            retry.ask(m, planning, planner, "the plan has " + proposals.size() + " tickets, more than " + limit);
            return false;
        }
        List<List<String>> needs = BriefParsers.toolNeeds(report);
        try {
            record(m, planning, planner, developer, proposals, needs, worktree);
        } catch (DomainException e) {
            // An extension that cites a node that is not there is refused whole: the director is asked, not a paid loop
            if (!extension) throw e;
            retry.ask(m, planning, planner, "the extension is refused: " + e.getMessage());
            return false;
        }
        return true;
    }

    private static String missionText(String objective, String check, List<ProposedTicket> proposals) {
        return objective + " " + check + " "
                + proposals.stream()
                        .map(p -> p.title() + " " + p.description() + " " + p.acceptanceCriteria())
                        .collect(java.util.stream.Collectors.joining(" "));
    }

    /**
     * Measures the sandbox before the plan exists (nothing can be approved meanwhile), then records the plan and its
     * missing-tools and Docker questions as one command: no moment where the plan is approvable unchecked.
     */
    private List<EventEnvelope> record(
            MissionId m,
            TicketId planning,
            AgentId planner,
            AgentId developer,
            List<ProposedTicket> proposals,
            List<List<String>> toolNeeds,
            Path worktree) {
        java.time.Instant since = java.time.Instant.now();
        prepare(m, toolNeeds);
        String check = office.query(
                c -> c.missions().find(m).map(Mission::checkCommand).orElse(null));
        String objective =
                office.query(c -> c.missions().find(m).map(Mission::objective).orElse(null));
        String missionText = missionText(objective, check, proposals);
        List<String> dockerNeeds = DockerQuestion.because(worktree, check, toolNeeds, missionText);
        docker.prepare(m, dockerNeeds, since);
        return office.execute(c -> {
            List<EventEnvelope> planned = c.tickets()
                    .propose(office.context(Actor.agent(planner), Origin.REAL), planning, developer, proposals);
            List<EventEnvelope> all = new java.util.ArrayList<>(planned);
            // Assigned in the same command as the plan: no moment where the tickets exist unassigned.
            List<TicketCreated> created = planned.stream()
                    .map(EventEnvelope::event)
                    .filter(TicketCreated.class::isInstance)
                    .map(TicketCreated.class::cast)
                    .toList();
            TicketSpread.of(created, TicketSpread.developers(c, developer)).forEach((t, d) -> {
                if (!d.equals(developer))
                    all.addAll(c.tickets().assign(office.context(Actor.system(), Origin.REAL), t, d));
            });
            all.addAll(missingTools.askWithin(c, m, needs(planned, toolNeeds)));
            all.addAll(docker.askWithin(c, m, dockerNeeds, planned, toolNeeds));
            return all;
        });
    }

    /** Measures the sandbox for the tools the plan NEEDS, outside the office lock. */
    private void prepare(MissionId m, List<List<String>> toolNeeds) {
        try {
            missingTools.prepare(toolNeeds);
        } catch (RuntimeException e) {
            // Handled: logged; the question then uses the last measure, and a tool it never saw is not called absent
            LOG.warnf("Mission %s: the sandbox could not be measured for the plan: %s", m, e.getMessage());
        }
    }

    /** The tools each created ticket NEEDS, read from the plan in the same order as its proposals. */
    static Map<TicketId, List<String>> needs(List<EventEnvelope> events, List<List<String>> needs) {
        List<TicketId> created = events.stream()
                .map(EventEnvelope::event)
                .filter(TicketCreated.class::isInstance)
                .map(e -> ((TicketCreated) e).ticket())
                .toList();
        Map<TicketId, List<String>> out = new LinkedHashMap<>();
        for (int i = 0; i < created.size() && i < needs.size(); i++) {
            if (!needs.get(i).isEmpty()) out.put(created.get(i), needs.get(i));
        }
        return out;
    }
}
