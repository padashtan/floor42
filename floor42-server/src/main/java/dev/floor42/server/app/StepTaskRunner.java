package dev.floor42.server.app;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.Actor;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Decision;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.workflow.Workflow.Step;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * One of the director's tasks, worked as a whole by one session of its role (ADR-0061). The session's tools are its
 * role's, filtered by the mission's table and never widened: a developer works in its own worktree, a reviewer reads
 * (the tools that change are denied to it), a researcher has the web tools the table allows and no repository. The
 * step's texts are in the brief as data. The report is submitted as a producer's is: a reviewer reads it when the step
 * is reviewed, and the ticket is done with it otherwise.
 */
@ApplicationScoped
public class StepTaskRunner implements TaskRunner {

    private final OfficeService office;
    private final AgentWorktrees worktrees;
    private final SessionGate gate;
    private final DependencyMerge dependencies;
    private final MissionPermissions permissions;
    private final StaffProvisioner staff;

    @Inject
    StepTaskRunner(
            OfficeService office,
            AgentWorktrees worktrees,
            SessionGate gate,
            DependencyMerge dependencies,
            MissionPermissions permissions,
            StaffProvisioner staff) {
        this.office = office;
        this.worktrees = worktrees;
        this.gate = gate;
        this.dependencies = dependencies;
        this.permissions = permissions;
        this.staff = staff;
    }

    @Override
    public Work work() {
        return Work.TASK;
    }

    @Override
    public void run(MissionId m, Task task) {
        TicketId id = task.ticket();
        AgentId agent = task.agent();
        Step.Task step = office.query(c -> c.workflows()
                .of(m)
                .runnable()
                .flatMap(w ->
                        c.workflows().stepOf(c.tickets().find(id).orElseThrow()).flatMap(w::task))
                .orElseThrow());
        // Before anything starts: a role left with no tool by the mission's table is one clear question, not a started
        // ticket and a hired agent (ADR-0061).
        if (office.query(
                c -> tools(c.missions().find(m).orElseThrow(), id, agent, step).isEmpty())) {
            askNoTool(m, id, agent, step);
            return;
        }
        if (step.runBy() == dev.floor42.domain.WorkRole.DEVELOPER && !dependencies.merge(m, id, agent)) return;
        // Noe is hired on first need, before the ticket is assigned to him.
        if (agent.equals(StaffProvisioner.RESEARCHER)) staff.ensureResearcher();
        SessionGate.take(office, id, agent);
        SessionSpec spec = spec(m, id, agent, step);
        Optional<String> report =
                step.runBy() == dev.floor42.domain.WorkRole.DEVELOPER ? gate.reportContinuing(spec) : gate.report(spec);
        report.ifPresent(r -> office.execute(
                c -> c.tickets().submitForReview(office.context(Actor.agent(agent), Origin.REAL), id, r)));
    }

    private void askNoTool(MissionId m, TicketId id, AgentId agent, Step.Task step) {
        String tools = step.runBy() == dev.floor42.domain.WorkRole.RESEARCHER ? "WebSearch and WebFetch" : "its tools";
        office.execute(c -> c.desk()
                .ask(
                        office.context(Actor.system(), Origin.REAL),
                        m,
                        "The step " + step.title() + " cannot start: the permission table forbids " + tools,
                        "Allow " + tools + " in the mission's permission table, then ask again.",
                        List.of(),
                        null,
                        dev.floor42.domain.Criticality.HIGH,
                        false,
                        id,
                        dev.floor42.domain.Phrase.of("noToolForStep", "step", step.title(), "tools", tools)));
    }

    /** The session's spec; a role left with no tool by the mission's table cannot do the step: the director is asked. */
    private SessionSpec spec(MissionId m, TicketId id, AgentId agent, Step.Task step) {
        switch (step.runBy()) {
            case REVIEWER -> permissions.denyAll(m, agent, MissionPermissions.CHANGES);
            case RESEARCHER -> {
                permissions.denyAll(
                        m, agent, List.of(ActionCategory.READ, ActionCategory.WRITE, ActionCategory.EXECUTE));
            }
            default -> {}
        }
        Path dir = step.runBy() == dev.floor42.domain.WorkRole.RESEARCHER
                ? worktrees.research(m)
                : worktrees.ready(m, agent);
        return office.query(c -> {
            Mission mission = c.missions().find(m).orElseThrow();
            List<String> tools = tools(mission, id, agent, step);
            if (tools.isEmpty()) {
                throw new IllegalStateException(
                        "The mission's permission table leaves " + agent + " no tool for the step");
            }
            Agent a = c.staff().find(agent).orElseThrow();
            Ticket ticket = c.tickets().find(id).orElseThrow();
            return SessionGate.spec(c, m, id, a, dir, TaskBrief.of(c, mission, ticket, a, step), tools);
        });
    }

    /** The role's tools, each kept only when the mission's table does not forbid it to this agent. */
    public static List<String> tools(Mission mission, TicketId id, AgentId agent, Step.Task step) {
        List<String> offered = Briefs.offeredTools(mission, id);
        return switch (step.runBy()) {
            case DEVELOPER -> offered;
            case REVIEWER ->
                ReviewRunner.REVIEW_TOOLS.stream().filter(offered::contains).toList();
            default ->
                HelperSessions.WEB_TOOLS.stream()
                        .filter(t -> mission.policy()
                                        .evaluate(agent, ActionCategory.NETWORK, t, null)
                                        .decision()
                                != Decision.DENY)
                        .toList();
        };
    }
}
