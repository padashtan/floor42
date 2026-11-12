package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireNoAgentAtWork;
import static dev.floor42.domain.Rules.requireSystem;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TicketEvent.DirectiveGiven;
import dev.floor42.domain.event.TicketEvent.TicketReopened;
import dev.floor42.domain.event.WorkbenchEvent;
import dev.floor42.domain.event.WorkbenchEvent.BranchesMerged;
import dev.floor42.domain.event.WorkbenchEvent.DirectorEditedFile;
import dev.floor42.domain.event.WorkbenchEvent.DirectorFilesCommitted;
import dev.floor42.domain.event.WorkbenchEvent.PatchAccepted;
import dev.floor42.domain.event.WorkbenchEvent.PatchRejected;
import dev.floor42.domain.event.WorkbenchEvent.ProjectMerged;
import dev.floor42.domain.event.WorkbenchEvent.WorkspacePrepared;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The workshop (spec section 11): the agents' workspaces and what the director did in them. Floor42 does the file work;
 * this records what was done and by whom.
 */
final class Workbench {

    private final CompanyView view;
    private final List<Workspace> workspaces = new ArrayList<>();
    private final List<Intervention> interventions = new ArrayList<>();
    /** The last merge of other developers' branches before each ticket: clean or not (D10). */
    private final Map<TicketId, Boolean> merges = new HashMap<>();

    private final Map<MissionId, Set<String>> directorFiles = new HashMap<>();

    Workbench(CompanyView view) {
        this.view = view;
    }

    List<Workspace> workspaces(MissionId mission) {
        return workspaces.stream().filter(w -> w.mission().equals(mission)).toList();
    }

    Optional<Workspace> workspace(MissionId mission, AgentId agent) {
        return workspaces.stream()
                .filter(w -> w.mission().equals(mission) && w.agent().equals(agent))
                .findFirst();
    }

    List<Intervention> interventions(MissionId mission) {
        return interventions.stream().filter(i -> i.mission().equals(mission)).toList();
    }

    Optional<Boolean> merge(TicketId ticket) {
        return Optional.ofNullable(merges.get(ticket));
    }

    List<String> directorFiles(MissionId mission) {
        return List.copyOf(directorFiles.getOrDefault(mission, Set.of()));
    }

    /** The director edited this file of the agent's workspace: a write on it always asks first. */
    boolean editedByDirector(MissionId mission, AgentId agent, String path) {
        if (path == null) return false;
        String normalized = normalizedPath(path);
        return workspace(mission, agent)
                .map(w -> w.editedByDirector.stream()
                        .anyMatch(p -> normalizedPath(p).equals(normalized)))
                .orElse(false);
    }

    private static final boolean CASE_FOLDED =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");

    /** Same file, whatever the spelling: separators, "./" and "a/../", and the case on Windows. */
    static String normalizedPath(String path) {
        String p = path.replace('\\', '/');
        try {
            p = java.nio.file.Path.of(p).normalize().toString().replace('\\', '/');
        } catch (java.nio.file.InvalidPathException e) {
            // Handled: an unparsable path is compared as written, on both sides alike
        }
        return CASE_FOLDED ? p.toLowerCase(java.util.Locale.ROOT) : p;
    }

    List<EventEnvelope> prepare(
            CommandContext ctx, MissionId id, AgentId agent, String path, String branch, String baseCommit) {
        requireSystem(ctx, "Only Floor42 prepares workspaces");
        Mission m = view.missionFor(ctx, id);
        view.existingAgent(agent);
        require(m.workspace() != null, Refusal.NO_REPOSITORY, id + " has no repository");
        require(
                workspace(id, agent).isEmpty(),
                Refusal.DUPLICATE_WORKSPACE,
                agent + " already has a workspace on " + id);
        requireText(path, "path");
        requireText(branch, "branch");
        requireText(baseCommit, "baseCommit");
        return view.record(ctx, id)
                .emit(new WorkspacePrepared(id, agent, path, branch, baseCommit))
                .events();
    }

    List<EventEnvelope> recordDirectorEdit(
            CommandContext ctx, MissionId id, AgentId agent, String path, String note, String diff) {
        Workspace w = target(ctx, id, agent);
        requireText(path, "path");
        requireText(diff, "diff");
        return view.record(ctx, id)
                .emit(new DirectorEditedFile(id, w.agent(), path.replace('\\', '/'), note, diff))
                .events();
    }

    /** A ticket already done or in review is reopened: the work and its independent review start again. */
    List<EventEnvelope> giveDirective(CommandContext ctx, TicketId ticketId, String directive) {
        TicketId to = checkDirective(ctx, ticketId);
        requireText(directive, "directive");
        Ticket t = view.existingTicket(to);
        Recorder r = view.record(ctx, t.mission()).emit(new DirectiveGiven(t.mission(), to, directive));
        if (t.status.canBeReopened()) {
            r.emit(new TicketReopened(to, "director directive"));
            WorkflowTasks.workChanged(view, r, t);
        }
        return r.events();
    }

    /** The ticket the directive goes to: itself, or for a ruled committee's ticket the implementation it started. */
    TicketId checkDirective(CommandContext ctx, TicketId ticketId) {
        requireDirector(ctx);
        Ticket t = view.existingTicket(ticketId);
        Rules.requireWorkTicket(t);
        Mission m = view.missionFor(ctx, t.mission());
        require(m.status == MissionStatus.IN_PROGRESS, Refusal.MISSION_NOT_ACTIVE, m.id() + " is " + m.status);
        require(t.status != TicketStatus.CANCELLED, Refusal.INVALID_TRANSITION, ticketId + " is cancelled");
        requireNoAgentAtWork(view, m.id());
        if (view.committees(m.id()).stream().noneMatch(c -> c.ruledOn(t.id()))) return ticketId;
        Ticket implementation = implementation(m.id(), t).orElse(null);
        require(
                implementation != null,
                Refusal.DECIDED_TICKET,
                ticketId + " is the committee's decision, already ruled: no one works on it any more. Adopt it with"
                        + " implementation, or give the directive to a ticket of the mission");
        return checkDirective(ctx, implementation.id());
    }

    /** The latest ticket someone works on besides the decision, the staffing reviews and the retrospective. */
    private Optional<Ticket> implementation(MissionId mission, Ticket decision) {
        return view.tickets(mission).stream()
                .filter(x -> !x.id().equals(decision.id())
                        && x.status != TicketStatus.CANCELLED
                        && x.assignee != null
                        && !view.isStaffingTicket(x.id())
                        && view.retro(mission)
                                .map(rt -> !rt.ticket().equals(x.id()))
                                .orElse(true))
                .reduce((first, last) -> last);
    }

    List<EventEnvelope> acceptPatch(
            CommandContext ctx, MissionId id, AgentId agent, String commit, String message, List<String> files) {
        Workspace w = target(ctx, id, agent);
        requireText(commit, "commit");
        requireText(message, "message");
        require(files != null && !files.isEmpty(), Refusal.EMPTY_PATCH, "Nothing to accept");
        return view.record(ctx, id)
                .emit(new PatchAccepted(id, w.agent(), commit, message, List.copyOf(files)))
                .events();
    }

    List<EventEnvelope> rejectPatch(
            CommandContext ctx, MissionId id, AgentId agent, String reason, String archive, List<String> files) {
        Workspace w = target(ctx, id, agent);
        requireText(reason, "reason");
        requireText(archive, "archive");
        require(files != null && !files.isEmpty(), Refusal.EMPTY_PATCH, "Nothing to reject");
        return view.record(ctx, id)
                .emit(new PatchRejected(id, w.agent(), reason, archive, List.copyOf(files)))
                .events();
    }

    /** The director acts on a workspace of an open mission, never under a running agent's feet. */
    Workspace target(CommandContext ctx, MissionId id, AgentId agent) {
        requireDirector(ctx);
        Mission m = view.missionFor(ctx, id);
        require(!m.status.isTerminal(), Refusal.MISSION_CLOSED, id + " is closed");
        Workspace w = workspace(id, agent).orElse(null);
        require(w != null, Refusal.UNKNOWN_WORKSPACE, agent + " has no workspace on " + id);
        requireNoAgentAtWork(view, id);
        return w;
    }

    List<EventEnvelope> recordMerge(
            CommandContext ctx, TicketId ticket, AgentId into, List<String> branches, boolean clean, String detail) {
        requireSystem(ctx, "Only Floor42 merges branches");
        Ticket t = view.existingTicket(ticket);
        view.missionFor(ctx, t.mission());
        require(branches != null && !branches.isEmpty(), Refusal.INVALID_ARGUMENT, "Which branches?");
        return view.record(ctx, t.mission())
                .emit(new BranchesMerged(t.mission(), ticket, into, List.copyOf(branches), clean, detail))
                .events();
    }

    List<EventEnvelope> recordDirectorFiles(CommandContext ctx, MissionId id, String commit, List<String> files) {
        requireDirector(ctx);
        Mission m = view.missionFor(ctx, id);
        require(!m.status.isTerminal(), Refusal.MISSION_CLOSED, id + " is closed");
        requireText(commit, "commit");
        require(files != null && !files.isEmpty(), Refusal.INVALID_ARGUMENT, "Which files?");
        return view.record(ctx, id)
                .emit(new DirectorFilesCommitted(id, commit, List.copyOf(files)))
                .events();
    }

    List<EventEnvelope> recordProjectMerged(
            CommandContext ctx, MissionId id, AgentId agent, String commit, boolean clean, String detail) {
        requireDirector(ctx);
        Mission m = view.missionFor(ctx, id);
        require(!m.status.isTerminal(), Refusal.MISSION_CLOSED, id + " is closed");
        require(workspace(id, agent).isPresent(), Refusal.UNKNOWN_WORKSPACE, agent + " has no workspace on " + id);
        requireNoAgentAtWork(view, id);
        requireText(commit, "commit");
        return view.record(ctx, id)
                .emit(new ProjectMerged(id, agent, commit, clean, detail))
                .events();
    }

    /** The only way this area's state changes. */
    void apply(WorkbenchEvent event, EventEnvelope envelope) {
        switch (event) {
            case WorkspacePrepared e -> on(e);
            case DirectorEditedFile e -> on(e, envelope);
            case PatchAccepted e -> on(e, envelope);
            case PatchRejected e -> on(e, envelope);
            case BranchesMerged e -> on(e);
            case DirectorFilesCommitted e -> on(e);
            case ProjectMerged e -> on(e);
        }
    }

    void on(WorkspacePrepared e) {
        workspaces.add(new Workspace(e.mission(), e.agent(), e.path(), e.branch(), e.baseCommit()));
    }

    void on(DirectorEditedFile e, EventEnvelope envelope) {
        workspace(e.mission(), e.workspace()).orElseThrow().editedByDirector.add(e.path());
        interventions.add(new Intervention(
                Intervention.Kind.DIRECTOR_EDIT,
                e.mission(),
                envelope.occurredAt(),
                envelope.actor(),
                e.workspace(),
                null,
                e.note() == null ? e.path() : e.path() + ": " + e.note(),
                e.diff(),
                List.of(e.path())));
    }

    /** The directive's trace; the directive itself is the ticket's, recorded just before. */
    void on(DirectiveGiven e, EventEnvelope envelope) {
        interventions.add(new Intervention(
                Intervention.Kind.DIRECTIVE,
                e.mission(),
                envelope.occurredAt(),
                envelope.actor(),
                view.ticket(e.ticket()).orElseThrow().assignee(),
                e.ticket(),
                e.directive(),
                null,
                List.of()));
    }

    void on(PatchAccepted e, EventEnvelope envelope) {
        interventions.add(new Intervention(
                Intervention.Kind.PATCH_ACCEPTED,
                e.mission(),
                envelope.occurredAt(),
                envelope.actor(),
                e.workspace(),
                null,
                e.message(),
                e.commit(),
                e.files()));
    }

    void on(PatchRejected e, EventEnvelope envelope) {
        interventions.add(new Intervention(
                Intervention.Kind.PATCH_REJECTED,
                e.mission(),
                envelope.occurredAt(),
                envelope.actor(),
                e.workspace(),
                null,
                e.reason(),
                e.archive(),
                e.files()));
    }

    void on(BranchesMerged e) {
        merges.put(e.ticket(), e.clean());
    }

    void on(DirectorFilesCommitted e) {
        directorFiles.computeIfAbsent(e.mission(), k -> new LinkedHashSet<>()).addAll(e.files());
    }

    void on(ProjectMerged e) {
        if (e.clean()) workspace(e.mission(), e.agent()).ifPresent(w -> w.baseCommit = e.commit());
    }
}
