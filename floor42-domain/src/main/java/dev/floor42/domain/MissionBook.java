package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireDirectorOrFloor42;
import static dev.floor42.domain.Rules.requireOpen;
import static dev.floor42.domain.Rules.requireText;
import static dev.floor42.domain.Rules.requireTransition;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent;
import dev.floor42.domain.event.MissionEvent.BudgetSet;
import dev.floor42.domain.event.MissionEvent.ChecksConfigured;
import dev.floor42.domain.event.MissionEvent.MissionArchived;
import dev.floor42.domain.event.MissionEvent.MissionCancelled;
import dev.floor42.domain.event.MissionEvent.MissionCompleted;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.MissionEvent.MissionPlanApproved;
import dev.floor42.domain.event.MissionEvent.MissionPlanRejected;
import dev.floor42.domain.event.MissionEvent.MissionPlanSubmitted;
import dev.floor42.domain.event.MissionEvent.MissionResumed;
import dev.floor42.domain.event.MissionEvent.MissionSuspended;
import dev.floor42.domain.event.MissionEvent.MissionUnarchived;
import dev.floor42.domain.event.MissionEvent.PermissionRuleAdded;
import dev.floor42.domain.event.MissionEvent.PermissionRuleRemoved;
import dev.floor42.domain.event.MissionEvent.TicketValidationEnabled;
import dev.floor42.domain.event.WorkflowEvent.WorkflowPinned;
import dev.floor42.domain.workflow.MissionWorkflow;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.WorkflowDocument;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The missions and their life: planned, approved, run, suspended, ended; with their permission table and budget. */
final class MissionBook {

    private final CompanyView view;
    private final MissionClosure closure;
    private final Map<MissionId, Mission> missions = new LinkedHashMap<>();
    /**
     * The highest mission number the journal holds, read or not: a mission whose creation another version wrote (and
     * this one sets aside) keeps its number, so no new mission ever takes it (ADR-0051).
     */
    private int highest;

    MissionBook(CompanyView view) {
        this.view = view;
        this.closure = new MissionClosure(view);
    }

    Collection<Mission> missions() {
        return List.copyOf(missions.values());
    }

    Collection<Mission> missionsView() {
        return Collections.unmodifiableCollection(missions.values());
    }

    Optional<Mission> mission(MissionId id) {
        return Optional.ofNullable(missions.get(id));
    }

    /** The mission, checked to exist and to share the command's origin. */
    Mission missionFor(CommandContext ctx, MissionId id) {
        Mission m = missions.get(id);
        require(m != null, Refusal.UNKNOWN_MISSION, "No mission " + id);
        require(
                m.origin() == ctx.origin(),
                Refusal.ORIGIN_MISMATCH,
                id + " is a " + m.origin() + " mission; it cannot receive " + ctx.origin() + " events");
        return m;
    }

    /** A real mission must name the repository its agents work on; a simulated one has none. */
    List<EventEnvelope> create(
            CommandContext ctx, String title, String objective, String workspace, Workflow workflow) {
        requireDirector(ctx);
        requireText(title, "title");
        requireText(objective, "objective");
        if (ctx.origin() == Origin.REAL) requireText(workspace, "workspace");
        else require(workspace == null, Refusal.SIMULATED_WORKSPACE, "A simulated mission works on no repository");
        require(
                workflow == null || ctx.origin() == Origin.REAL,
                Refusal.ORIGIN_MISMATCH,
                "A simulated mission follows Floor42's own workflow");
        if (workflow == null) return open(ctx, title, objective, workspace);
        WorkflowDocument.Written pin = WorkflowDocument.forJournal(workflow);
        MissionId id = nextId();
        return view.record(ctx, id)
                .emit(new MissionCreated(id, title.strip(), objective.strip(), workspace))
                .emit(new WorkflowPinned(id, pin.document(), pin.schemaVersion()))
                .events();
    }

    private MissionId nextId() {
        return new MissionId("M-" + (Math.max(highest, missions.size()) + 1));
    }

    /** A mission number the journal holds in a row this version cannot read: never given to a new mission. */
    void reserve(MissionId id) {
        highest = Math.max(highest, number(id));
    }

    private static int number(MissionId id) {
        String v = id.value();
        if (!v.matches("M-\\d{1,9}")) return 0;
        return Integer.parseInt(v.substring(2));
    }

    /**
     * A rehearsal (the guided first mission, ADR-0045): a simulated mission on Floor42's own sample repository, never one
     * of the director's. Nothing runs a model; it keeps the rules of a mission on a repository, its cap decision first.
     */
    List<EventEnvelope> createRehearsal(CommandContext ctx, String title, String objective, String sample) {
        requireDirector(ctx);
        require(
                ctx.origin() == Origin.SIMULATED,
                Refusal.ORIGIN_MISMATCH,
                "A rehearsal is simulated: a real mission is created on the director's repository");
        requireText(title, "title");
        requireText(objective, "objective");
        requireText(sample, "workspace");
        return open(ctx, title, objective, sample);
    }

    private List<EventEnvelope> open(CommandContext ctx, String title, String objective, String workspace) {
        MissionId id = nextId();
        return view.record(ctx, id)
                .emit(new MissionCreated(id, title.strip(), objective.strip(), workspace))
                .events();
    }

    List<EventEnvelope> submitPlan(CommandContext ctx, MissionId id) {
        requireTransition(missionFor(ctx, id), MissionStatus.AWAITING_APPROVAL);
        require(
                view.tickets(id).stream().anyMatch(t -> t.status() != TicketStatus.CANCELLED)
                        || WorkflowTasks.stepApproval(view, id),
                Refusal.EMPTY_PLAN,
                "A plan needs at least one ticket");
        return view.record(ctx, id).emit(new MissionPlanSubmitted(id)).events();
    }

    /**
     * Paid sessions are capped unless the director said otherwise: a mission on a repository (a real one, or a rehearsal
     * on the sample) needs a budget, or none chosen. The demo, simulated and on no repository, needs neither.
     */
    List<EventEnvelope> approvePlan(CommandContext ctx, MissionId id) {
        requireDirector(ctx);
        Mission m = awaitingApproval(ctx, id);
        require(
                (m.origin() != Origin.REAL && m.workspace() == null) || m.capDecided,
                Refusal.CAP_NEEDED,
                id + " has no budget: set one, or choose no cap explicitly");
        // A workflow this build cannot read is never started (ADR-0050).
        if (view.workflowOf(id) instanceof MissionWorkflow.Unrunnable u) {
            throw new DomainException(Refusal.WORKFLOW_UNREADABLE, id + " cannot run here: " + u.reason());
        }
        return WorkflowTasks.approved(view, view.record(ctx, id).emit(new MissionPlanApproved(id)), id);
    }

    List<EventEnvelope> rejectPlan(CommandContext ctx, MissionId id, String reason) {
        requireDirector(ctx);
        awaitingApproval(ctx, id);
        require(!WorkflowTasks.stepApproval(view, id), Refusal.NOTHING_TO_REPLAN, id + " has no plan to rework");
        requireText(reason, "reason");
        return view.record(ctx, id).emit(new MissionPlanRejected(id, reason)).events();
    }

    private Mission awaitingApproval(CommandContext ctx, MissionId id) {
        Mission m = missionFor(ctx, id);
        require(
                m.status == MissionStatus.AWAITING_APPROVAL,
                Refusal.INVALID_TRANSITION,
                id + " has no plan awaiting approval");
        return m;
    }

    List<EventEnvelope> suspend(CommandContext ctx, MissionId id, String reason) {
        requireDirector(ctx);
        requireTransition(missionFor(ctx, id), MissionStatus.SUSPENDED);
        return view.record(ctx, id).emit(new MissionSuspended(id, reason)).events();
    }

    List<EventEnvelope> resume(CommandContext ctx, MissionId id) {
        requireDirector(ctx);
        Mission m = missionFor(ctx, id);
        require(m.status == MissionStatus.SUSPENDED, Refusal.INVALID_TRANSITION, id + " is not suspended");
        return view.record(ctx, id).emit(new MissionResumed(id)).events();
    }

    List<EventEnvelope> complete(CommandContext ctx, MissionId id, String summary) {
        checkComplete(ctx, id);
        requireText(summary, "summary");
        return view.record(ctx, id).emit(new MissionCompleted(id, summary)).events();
    }

    /** What {@link #complete} would refuse, without recording anything (ADR-0039). */
    void checkComplete(CommandContext ctx, MissionId id) {
        require(
                ctx.actor().kind() != Actor.Kind.AGENT,
                Refusal.FORBIDDEN,
                "Only the director or Floor42 can close a mission");
        requireTransition(missionFor(ctx, id), MissionStatus.COMPLETED);
        closure.requireFinished(id);
    }

    List<EventEnvelope> cancel(CommandContext ctx, MissionId id, String reason) {
        requireDirector(ctx);
        requireTransition(missionFor(ctx, id), MissionStatus.CANCELLED);
        requireText(reason, "reason");
        Recorder r = view.record(ctx, id);
        closure.closeDependents(r, id);
        return r.emit(new MissionCancelled(id, reason)).events();
    }

    /**
     * The director puts a closed mission away (ADR-0054). Only once it is over: completed or cancelled, no session of it
     * still running, its retrospective concluded. Archiving it again records nothing. The check and the event are one
     * command, so a session start or a retrospective, also commands, comes either before it (and is seen) or after it
     * (and is refused).
     */
    List<EventEnvelope> archive(CommandContext ctx, MissionId id) {
        Mission m = checkArchive(ctx, id);
        if (m.archived) return List.of();
        return view.record(ctx, id).emit(new MissionArchived(id)).events();
    }

    /** What {@link #archive} would refuse, without recording anything. */
    Mission checkArchive(CommandContext ctx, MissionId id) {
        requireDirector(ctx);
        Mission m = missionFor(ctx, id);
        require(m.status.isTerminal(), Refusal.MISSION_STILL_OPEN, id + " is " + m.status + ": close it first");
        require(
                view.sessions(id).stream().noneMatch(AgentSession::isOpen),
                Refusal.SESSION_RUNNING,
                "A session of " + id + " is still running");
        require(
                view.retro(id).map(Retro::concluded).orElse(true),
                Refusal.RETRO_UNDER_WAY,
                "The retrospective of " + id + " is under way");
        return m;
    }

    /** The director brings an archived mission back to the lists; one not archived records nothing. */
    List<EventEnvelope> unarchive(CommandContext ctx, MissionId id) {
        requireDirector(ctx);
        Mission m = missionFor(ctx, id);
        if (!m.archived) return List.of();
        return view.record(ctx, id).emit(new MissionUnarchived(id)).events();
    }

    /** Agents never change permissions, even their own: the director does, or Floor42 on the director's behalf. */
    List<EventEnvelope> addPermission(CommandContext ctx, MissionId id, PermissionRule rule) {
        requireDirectorOrFloor42(ctx);
        requireOpen(missionFor(ctx, id));
        if (rule.agent() != null) view.existingAgent(rule.agent());
        // A ticket's rule is the mission's own ticket's, never another mission's (D15).
        require(
                rule.ticket() == null
                        || view.existingTicket(rule.ticket()).mission().equals(id),
                Refusal.UNKNOWN_TICKET,
                rule.ticket() + " is not part of " + id);
        return view.record(ctx, id).emit(new PermissionRuleAdded(id, rule)).events();
    }

    /**
     * One mission-wide line per tool, replaced atomically. Set by the director, it replaces the tickets' lines of that
     * tool (they would outrank it unseen) and makes Floor42's same line theirs. Agent and command rules stay.
     */
    List<EventEnvelope> setToolPermission(
            CommandContext ctx, MissionId id, String tool, ActionCategory category, Decision decision) {
        requireDirectorOrFloor42(ctx);
        Mission m = missionFor(ctx, id);
        requireOpen(m);
        requireText(tool, "tool");
        boolean director = ctx.actor().isDirector();
        PermissionRule wanted = PermissionRule.forTool(tool, category, decision);
        List<PermissionRule> previous = m.toolLines(tool, director);
        if (previous.equals(List.of(wanted)) && (!director || m.directorsOwn(wanted))) return List.of();
        Recorder r = view.record(ctx, id);
        previous.forEach(p -> r.emit(new PermissionRuleRemoved(id, p)));
        return r.emit(new PermissionRuleAdded(id, wanted)).events();
    }

    List<EventEnvelope> removePermission(CommandContext ctx, MissionId id, PermissionRule rule) {
        requireDirectorOrFloor42(ctx);
        Mission m = missionFor(ctx, id);
        require(m.policy().rules().contains(rule), Refusal.UNKNOWN_RULE, "No such rule on " + id);
        return view.record(ctx, id).emit(new PermissionRuleRemoved(id, rule)).events();
    }

    /** From now on, tickets the director does not create wait for the director's approval (ADR-0017). */
    List<EventEnvelope> enableTicketValidation(CommandContext ctx, MissionId id) {
        requireDirector(ctx);
        Mission m = missionFor(ctx, id);
        requireOpen(m);
        if (m.ticketValidation) return List.of();
        return view.record(ctx, id).emit(new TicketValidationEnabled(id)).events();
    }

    /** The only way this area's state changes. */
    void apply(MissionEvent event, EventEnvelope envelope) {
        switch (event) {
            case MissionCreated e -> on(e, envelope);
            case MissionPlanSubmitted e -> on(e);
            case MissionPlanApproved e -> on(e);
            case MissionPlanRejected e -> on(e);
            case MissionSuspended e -> on(e, envelope);
            case MissionResumed e -> on(e);
            case MissionCompleted e -> on(e, envelope);
            case MissionCancelled e -> on(e, envelope);
            case PermissionRuleAdded e -> on(e, envelope);
            case PermissionRuleRemoved e -> on(e);
            case TicketValidationEnabled e -> on(e);
            case ChecksConfigured e -> on(e);
            case BudgetSet e -> on(e);
            case MissionArchived e -> missions.get(e.mission()).archived = true;
            case MissionUnarchived e -> missions.get(e.mission()).archived = false;
        }
    }

    void on(MissionCreated e, EventEnvelope envelope) {
        // Two creations of one id would merge two missions: the replay stops instead (ADR-0042, ADR-0051).
        if (missions.containsKey(e.mission())) throw new IllegalStateException(e.mission() + " is created twice");
        highest = Math.max(highest, number(e.mission()));
        missions.put(
                e.mission(),
                new Mission(
                        e.mission(),
                        e.title(),
                        e.objective(),
                        envelope.origin(),
                        envelope.occurredAt(),
                        e.workspace()));
    }

    void on(MissionPlanSubmitted e) {
        missions.get(e.mission()).status = MissionStatus.AWAITING_APPROVAL;
        missions.get(e.mission()).planSubmitted = true;
    }

    void on(MissionPlanApproved e) {
        missions.get(e.mission()).status = MissionStatus.IN_PROGRESS;
    }

    void on(MissionPlanRejected e) {
        missions.get(e.mission()).status = MissionStatus.PLANNING;
    }

    void on(MissionSuspended e, EventEnvelope envelope) {
        missions.get(e.mission()).status = MissionStatus.SUSPENDED;
        missions.get(e.mission()).suspendedAt = envelope.occurredAt();
    }

    void on(MissionResumed e) {
        missions.get(e.mission()).status = MissionStatus.IN_PROGRESS;
    }

    void on(MissionCompleted e, EventEnvelope envelope) {
        close(e.mission(), MissionStatus.COMPLETED, e.summary(), envelope);
    }

    void on(MissionCancelled e, EventEnvelope envelope) {
        close(e.mission(), MissionStatus.CANCELLED, e.reason(), envelope);
    }

    private void close(MissionId id, MissionStatus status, String outcome, EventEnvelope envelope) {
        Mission m = missions.get(id);
        m.status = status;
        m.outcome = outcome;
        m.closedAt = envelope.occurredAt();
    }

    void on(PermissionRuleAdded e, EventEnvelope envelope) {
        Mission m = missions.get(e.mission());
        // Only Floor42 writes a table before the first plan: older journals named the director for those lines.
        m.policy().add(e.rule(), m.planSubmitted ? envelope.actor() : Actor.system());
    }

    void on(PermissionRuleRemoved e) {
        missions.get(e.mission()).policy().remove(e.rule());
    }

    void on(TicketValidationEnabled e) {
        missions.get(e.mission()).ticketValidation = true;
    }

    void on(ChecksConfigured e) {
        missions.get(e.mission()).checkCommand = e.command();
        missions.get(e.mission()).checksAutomatic = e.automatic();
    }

    void on(BudgetSet e) {
        missions.get(e.mission()).budgetUsd = e.usd();
        missions.get(e.mission()).capDecided = true;
    }
}
