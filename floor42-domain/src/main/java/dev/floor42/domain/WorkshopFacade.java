package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import java.util.Optional;

/**
 * The workshop: the agents' workspaces, what Floor42 merges into them, what the director edits, gives, accepts or
 * rejects there, and the directives that send a ticket back to work. Reached by {@link Company#workshop()}; the rules
 * live in the workbench.
 */
public final class WorkshopFacade {

    private final Workbench workbench;

    WorkshopFacade(Workbench workbench) {
        this.workbench = workbench;
    }

    public List<Workspace> workspaces(MissionId mission) {
        return workbench.workspaces(mission);
    }

    public Optional<Workspace> workspace(MissionId mission, AgentId agent) {
        return workbench.workspace(mission, agent);
    }

    /** The workshop's trace for a mission, oldest first. */
    public List<Intervention> interventions(MissionId mission) {
        return workbench.interventions(mission);
    }

    /** Whether Floor42 merged the branches a ticket depends on: empty when it did not try, else clean or not. */
    public Optional<Boolean> merge(TicketId ticket) {
        return workbench.merge(ticket);
    }

    /** The files the director gave a mission (paths in its project), in the order they were committed. */
    public List<String> directorFiles(MissionId mission) {
        return workbench.directorFiles(mission);
    }

    public List<EventEnvelope> prepareWorkspace(
            CommandContext ctx, MissionId id, AgentId agent, String path, String branch, String baseCommit) {
        return workbench.prepare(ctx, id, agent, path, branch, baseCommit);
    }

    /** Floor42 records the merge of other developers' branches into the worktree of the developer taking a ticket. */
    public List<EventEnvelope> recordMerge(
            CommandContext ctx, TicketId ticket, AgentId into, List<String> branches, boolean clean, String detail) {
        return workbench.recordMerge(ctx, ticket, into, branches, clean, detail);
    }

    /**
     * Records the commit Floor42 made on the director's branch for a mission, on the director's click: files they gave,
     * or what they had put in the project folder.
     */
    public List<EventEnvelope> recordDirectorFiles(
            CommandContext ctx, MissionId id, String commit, List<String> files) {
        return workbench.recordDirectorFiles(ctx, id, commit, files);
    }

    /**
     * Records that the director's branch, at {@code commit}, was merged into an agent's worktree, cleanly or not. Like
     * every action of the director on workspaces, never while an agent of the mission is at work.
     */
    public List<EventEnvelope> recordProjectMerged(
            CommandContext ctx, MissionId id, AgentId agent, String commit, boolean clean, String detail) {
        return workbench.recordProjectMerged(ctx, id, agent, commit, clean, detail);
    }

    public List<EventEnvelope> recordDirectorEdit(
            CommandContext ctx, MissionId id, AgentId agent, String path, String note, String diff) {
        return workbench.recordDirectorEdit(ctx, id, agent, path, note, diff);
    }

    /**
     * A directive sends the ticket back to work with the director's words, which every later brief restates. A ticket
     * already done or in review is reopened: the work and its independent review start again.
     */
    public List<EventEnvelope> giveDirective(CommandContext ctx, TicketId ticketId, String directive) {
        return workbench.giveDirective(ctx, ticketId, directive);
    }

    /**
     * What {@link #giveDirective} would refuse, without recording anything, and the ticket the directive would go to:
     * the ticket itself, or for a decided committee's ticket the implementation its ruling started (ADR-0039).
     */
    public TicketId checkDirective(CommandContext ctx, TicketId ticketId) {
        return workbench.checkDirective(ctx, ticketId);
    }

    /**
     * What {@link #giveDirective} would refuse now, as a value; empty when a directive can be given, {@link
     * #checkDirective} then names its ticket. Records nothing.
     */
    public Optional<Refused> refusalToDirect(CommandContext ctx, TicketId ticketId) {
        return Refused.by(() -> workbench.checkDirective(ctx, ticketId));
    }

    public List<EventEnvelope> acceptPatch(
            CommandContext ctx, MissionId id, AgentId agent, String commit, String message, List<String> files) {
        return workbench.acceptPatch(ctx, id, agent, commit, message, files);
    }

    public List<EventEnvelope> rejectPatch(
            CommandContext ctx, MissionId id, AgentId agent, String reason, String archive, List<String> files) {
        return workbench.rejectPatch(ctx, id, agent, reason, archive, files);
    }

    /** Checks done before Floor42 touches a workspace for the director: call it, then do the file work, then record. */
    public void checkAccess(CommandContext ctx, MissionId id, AgentId agent) {
        workbench.target(ctx, id, agent);
    }
}
