package dev.floor42.server.app;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Committee;
import dev.floor42.domain.Decision;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketKind;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.workflow.CompanyWorkflow;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import dev.floor42.domain.workflow.WorkflowInterpreter;
import dev.floor42.domain.workflow.WorkflowInterpreter.Opening;
import dev.floor42.domain.workflow.WorkflowInterpreter.Preparation;
import dev.floor42.server.runtime.ClaudeCodeProbe;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates real missions. Floor42 proposes a plan (one ticket to deliver or to plan, and a conservative permission
 * table) and submits it; with committee roles the mission is a decision, analysed and debated read only before any
 * implementation. Everything is checked before anything is recorded: a mission is never left half created.
 */
@ApplicationScoped
public class MissionFactory {

    static final String PLAN_PREFIX = "Plan: ";

    private final OfficeService office;
    private final ClaudeCodeProbe probe;
    private final Workspaces workspaces;
    private final StaffProvisioner staff;
    private final MissionPermissions permissions;
    private final DeliverablesHome home;

    @Inject
    MissionFactory(
            OfficeService office,
            ClaudeCodeProbe probe,
            Workspaces workspaces,
            StaffProvisioner staff,
            MissionPermissions permissions,
            DeliverablesHome home) {
        this.office = office;
        this.probe = probe;
        this.workspaces = workspaces;
        this.staff = staff;
        this.permissions = permissions;
        this.home = home;
    }

    /** Who will work the mission, hired on first need before anything of the mission is recorded. */
    private record Staffed(AgentId developer, List<AgentId> reviewers) {}

    /**
     * @param panel {@code architecture}, or {@code idea} for a committee convened before a new project: moderated,
     *     debating until its members agree (at most {@link CommitteeRoles#IDEA_ROUNDS} rounds), and able to meet
     *     without a repository yet
     * @param deliverTo the folder for the accepted deliverables (ADR-0051), relative to the project or absolute; null
     *     or blank for none
     */
    MissionId create(
            String title,
            String objective,
            String repository,
            List<String> committee,
            boolean plan,
            String panel,
            String workflowName,
            String deliverTo) {
        return create(title, objective, repository, committee, plan, panel, workflowName, deliverTo, false);
    }

    /** As above; {@code graph} pins the workflow in graph mode (ADR-0062), which needs the planner. */
    MissionId create(
            String title,
            String objective,
            String repository,
            List<String> committee,
            boolean plan,
            String panel,
            String workflowName,
            String deliverTo,
            boolean graph) {
        // Resolved once: the opening is asked of this value and this value is pinned (ADR-0050).
        Workflow chosen = office.query(c -> chosen(c, workflowName));
        Workflow w = graph ? chosen.asGraph() : chosen;
        Opening opening = opening(new WorkflowInterpreter(w), repository, committee, plan, panel);
        if (graph && !(opening instanceof Opening.PlanWithPlanner)) {
            throw new DomainException("GRAPH_NEEDS_PLAN", "The graph mode needs the planner: ask for the plan");
        }
        return switch (opening) {
            case Opening.ConveneCommittee c ->
                createDecision(title, objective, repository, committee, c.panel(), w, deliverTo);
            case Opening.PlanWithPlanner p ->
                createPlanned(title, objective, repository, w, home.destination(repository, deliverTo));
            case Opening.DeliverInOneTicket d ->
                createDelivery(title, objective, repository, w, home.destination(repository, deliverTo));
            case Opening.AwaitStepApproval a ->
                createAwaiting(title, objective, repository, w, home.destination(repository, deliverTo));
        };
    }

    /** The company workflow of this name, the built-in one without a name; refused when this build cannot run it. */
    static Workflow chosen(dev.floor42.domain.Company c, String name) {
        if (name == null || name.isBlank() || CompanyWorkflow.key(name).equals(CompanyWorkflow.BUILT_IN)) {
            return Workflow.BUILT_IN;
        }
        CompanyWorkflow w = c.workflows()
                .named(name)
                .orElseThrow(() -> new DomainException("UNKNOWN_WORKFLOW", "No workflow named \"" + name + "\""));
        return w.workflow()
                .orElseThrow(() -> new DomainException(
                        "WORKFLOW_UNREADABLE",
                        "The workflow \"" + name + "\" cannot run here: "
                                + w.refusal().orElse("")));
    }

    /**
     * How the mission opens, asked of the workflow (ADR-0046), after the checks of what was asked: an idea committee
     * needs its members, and anything but a committee works on a repository (a committee may meet before it exists).
     * Records nothing.
     */
    static Opening opening(
            WorkflowInterpreter workflow, String repository, List<String> committee, boolean plan, String panel) {
        String kind = panel == null || panel.isBlank() ? CommitteeRoles.ARCHITECTURE : panel;
        if (kind.equals(CommitteeRoles.IDEA) && (committee == null || committee.isEmpty())) {
            throw new DomainException("INVALID_ARGUMENT", "An idea committee needs its members");
        }
        requireSteps(workflow.workflow(), committee, plan);
        Opening opening = workflow.opening(committee, kind, plan ? Preparation.BY_PLANNER : Preparation.ONE_TICKET);
        if (!(opening instanceof Opening.ConveneCommittee) && (repository == null || repository.isBlank())) {
            throw new DomainException("INVALID_ARGUMENT", "A mission works on a repository");
        }
        if (!(opening instanceof Opening.ConveneCommittee)
                && !(opening instanceof Opening.AwaitStepApproval)
                && !workflow.workflow().has(Step.Produce.class)) {
            throw new DomainException(
                    "WORKFLOW_PRODUCES_NOTHING",
                    "The workflow \"" + workflow.workflow().name() + "\" produces nothing: it runs committees only");
        }
        return opening;
    }

    /** What the director asked for must be a step of the chosen workflow, never dropped in silence (ADR-0050). */
    private static void requireSteps(Workflow w, List<String> committee, boolean plan) {
        if (committee != null && !committee.isEmpty() && !w.has(Step.Committee.class)) {
            throw new DomainException("INVALID_ARGUMENT", "The workflow \"" + w.name() + "\" has no committee step");
        }
        if (plan && !w.has(Step.Plan.class)) {
            throw new DomainException("INVALID_ARGUMENT", "The workflow \"" + w.name() + "\" has no plan step");
        }
    }

    private Staffed prepare(String repository) {
        requireRuntime();
        workspaces.requireRepository(repository);
        AgentId developer = staff.staff(WorkRole.DEVELOPER);
        return new Staffed(developer, staff.reviewers());
    }

    private void requireRuntime() {
        if (!probe.availability().available()) {
            throw new DomainException(
                    "RUNTIME_UNAVAILABLE",
                    "Claude Code is not available: " + probe.availability().reason());
        }
    }

    /** The planner first plans the mission into tickets the director validates (ADR-0017). */
    private MissionId createPlanned(String title, String objective, String repository, Workflow w, Path dest) {
        Staffed staffed = prepare(repository);
        staff.requirePresent(StaffProvisioner.PLANNER, "Geoffrey, the planner");
        MissionId m = mission(title, objective, repository, w, dest);
        staff.ensurePlanner();
        List<String> criteria =
                List.of("At most " + w.maxTickets() + " tickets, in order", "Each ticket has a checkable result");
        TicketId t = ticket(m, PLAN_PREFIX + title, objective, criteria, TicketKind.PLANNING);
        office.execute(c -> c.tickets().assign(ctx(Actor.system()), t, StaffProvisioner.PLANNER));
        // From here on, tickets Floor42 or an agent adds wait for the director (ADR-0017).
        office.execute(c -> c.missions().enableTicketValidation(ctx(Actor.director()), m));
        permissions.setDefaults(m);
        // The planner reads to plan; it changes nothing.
        denyChanges(m, staffed.reviewers(), List.of(StaffProvisioner.PLANNER));
        office.execute(c -> c.missions().submitPlan(ctx(Actor.system()), m));
        return m;
    }

    /** One developer delivers the mission in one ticket. The director changes any line of its table at any time. */
    private MissionId createDelivery(String title, String objective, String repository, Workflow w, Path dest) {
        Staffed staffed = prepare(repository);
        MissionId m = mission(title, objective, repository, w, dest);
        List<String> criteria = List.of(
                "The report answers the objective",
                "Every claim is backed by a file or a command output, or marked as an assumption",
                "Every file changed is listed in the report");
        TicketId t = ticket(m, "Deliver: " + title, objective, criteria, TicketKind.WORK);
        office.execute(c -> c.tickets().assign(ctx(Actor.system()), t, staffed.developer()));
        permissions.setDefaults(m);
        denyChanges(m, staffed.reviewers(), List.of());
        office.execute(c -> c.missions().submitPlan(ctx(Actor.system()), m));
        return m;
    }

    /**
     * A workflow of tasks only (ADR-0061): the plan is submitted with no ticket, the approval opens the tasks. The
     * reviewers read only, as in every mission; a developer's task writes in its own worktree.
     */
    private MissionId createAwaiting(String title, String objective, String repository, Workflow w, Path dest) {
        Staffed staffed = prepare(repository);
        MissionId m = mission(title, objective, repository, w, dest);
        permissions.setDefaults(m);
        denyChanges(m, staffed.reviewers(), List.of());
        office.execute(c -> c.missions().submitPlan(ctx(Actor.system()), m));
        return m;
    }

    /** The review is independent only if the reviewer cannot change what it reviews: enforced, not just unoffered. */
    private void denyChanges(MissionId m, List<AgentId> reviewers, List<AgentId> readers) {
        for (ActionCategory category : MissionPermissions.CHANGES) {
            for (AgentId reviewer : reviewers) permissions.deny(m, reviewer, category);
            for (AgentId reader : readers) permissions.deny(m, reader, category);
        }
    }

    /** A decision mission: the committee analyses and debates read only, the director rules on its record. */
    private MissionId createDecision(
            String title,
            String objective,
            String repository,
            List<String> roleKeys,
            String panel,
            Workflow w,
            String deliverTo) {
        requireRuntime();
        List<CommitteeRoles.Role> roles = rolesOf(roleKeys, panel);
        boolean idea = panel.equals(CommitteeRoles.IDEA);
        // An idea may come before its project: the committee then meets in an empty repository of its own.
        String where = idea && (repository == null || repository.isBlank())
                ? workspaces.newProject(title).toString()
                : repository;
        workspaces.requireRepository(where);
        Path dest = home.destination(where, deliverTo);
        for (CommitteeRoles.Role r : roles) staff.hireIfMissing(r.agent(), r.name(), r.title(), r.model(), r.effort());
        MissionId m = mission(title, objective, where, w, dest);
        TicketId t = decisionTicket(m, title, objective);
        office.execute(
                c -> c.tickets().assign(ctx(Actor.system()), t, roles.getFirst().agent()));
        // The implementation Floor42 plans after the ruling waits for the director's approval (ADR-0017).
        office.execute(c -> c.missions().enableTicketValidation(ctx(Actor.director()), m));
        convene(m, t, objective, roles, idea ? ideaFormat(roles) : Committee.Format.ARCHITECTURE);
        readOnlyTable(m);
        office.execute(c -> c.missions().submitPlan(ctx(Actor.system()), m));
        return m;
    }

    /** Moderated, debating until its members agree, at most {@link CommitteeRoles#IDEA_ROUNDS} rounds. */
    private static Committee.Format ideaFormat(List<CommitteeRoles.Role> roles) {
        return new Committee.Format(CommitteeRoles.IDEA, true, CommitteeRoles.IDEA_ROUNDS, directorSeat(roles));
    }

    private void convene(
            MissionId m, TicketId t, String objective, List<CommitteeRoles.Role> roles, Committee.Format format) {
        staff.ensureAnalyst();
        office.execute(c -> c.committees()
                .convene(
                        ctx(Actor.director()),
                        m,
                        t,
                        objective,
                        roles.stream().map(CommitteeRoles.Role::agent).toList(),
                        roles.stream().map(CommitteeRoles.Role::title).toList(),
                        format));
    }

    private List<CommitteeRoles.Role> rolesOf(List<String> roleKeys, String panel) {
        try {
            return office.query(c -> CommitteeRoles.select(roleKeys, panel, c));
        } catch (IllegalArgumentException e) {
            throw new DomainException("INVALID_ARGUMENT", e.getMessage(), e);
        }
    }

    /** The agent the director's double sits as, whose seat the director may take in person; null without one. */
    private static AgentId directorSeat(List<CommitteeRoles.Role> roles) {
        return roles.stream()
                .filter(r -> r.key().equals(CommitteeRoles.DOUBLE))
                .map(CommitteeRoles.Role::agent)
                .findFirst()
                .orElse(null);
    }

    private TicketId decisionTicket(MissionId m, String title, String objective) {
        List<String> criteria = List.of(
                "The decision record answers the objective",
                "Alternatives, risks, assumptions and unresolved disagreements are stated",
                "Every claim is backed by a file or a command output, or marked as an assumption");
        return ((TicketCreated) office.execute(c -> c.tickets()
                                .create(
                                        ctx(Actor.system()),
                                        m,
                                        "Decide: " + title,
                                        objective,
                                        Priority.NORMAL,
                                        List.of(),
                                        criteria))
                        .getFirst()
                        .event())
                .ticket();
    }

    /** A committee decides, it does not change code: reading is free, the shell asks, nothing is written. */
    private void readOnlyTable(MissionId m) {
        permissions.set(m, "Read", ActionCategory.READ, Decision.ALLOW);
        permissions.set(m, "Glob", ActionCategory.READ, Decision.ALLOW);
        permissions.set(m, "Grep", ActionCategory.READ, Decision.ALLOW);
        permissions.set(m, "Bash", ActionCategory.EXECUTE, Decision.ASK);
        permissions.set(m, "Write", ActionCategory.WRITE, Decision.DENY);
        permissions.set(m, "Edit", ActionCategory.WRITE, Decision.DENY);
        permissions.set(m, "WebFetch", ActionCategory.NETWORK, Decision.DENY);
        permissions.set(m, "WebSearch", ActionCategory.NETWORK, Decision.DENY);
        // Skills and subagents are open (the director's choice, 2026-10-29): a subagent's calls follow this table too.
        permissions.set(m, "Skill", ActionCategory.OTHER, Decision.ALLOW);
        permissions.set(m, "Agent", ActionCategory.OTHER, Decision.ALLOW);
    }

    /**
     * The mission, its pinned workflow (ADR-0050) and the folder for its deliverables (ADR-0051), in one command,
     * before its first ticket.
     */
    private MissionId mission(String title, String objective, String repository, Workflow w, Path dest) {
        return ((MissionCreated) office.execute(c -> {
                            CommandContext ctx = ctx(Actor.director());
                            String repo = Path.of(repository).toAbsolutePath().toString();
                            List<EventEnvelope> events =
                                    new ArrayList<>(c.missions().create(ctx, title, objective, repo, w));
                            MissionId id = ((MissionCreated) events.getFirst().event()).mission();
                            if (dest != null) events.addAll(c.deliveries().setDestination(ctx, id, dest.toString()));
                            return events;
                        })
                        .getFirst()
                        .event())
                .mission();
    }

    private TicketId ticket(MissionId m, String title, String objective, List<String> criteria, TicketKind kind) {
        return ((TicketCreated) office.execute(c -> c.tickets()
                                .create(
                                        ctx(Actor.system()),
                                        m,
                                        title,
                                        objective,
                                        Priority.NORMAL,
                                        List.of(),
                                        criteria,
                                        kind))
                        .getFirst()
                        .event())
                .ticket();
    }

    private CommandContext ctx(Actor actor) {
        return office.context(actor, Origin.REAL);
    }
}
