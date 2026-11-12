package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import java.util.Optional;

/**
 * What leaves the machine or comes into a mission from outside: the pull requests Floor42 opens and their checks, the
 * Claude Code plugins it installs, the skills and agents a mission's sessions receive. Reached by
 * {@link Company#deliveries()}; the rules live in the deliveries area.
 */
public final class DeliveriesFacade {

    /** At most this many skills and agents selected for one mission. */
    public static final int MAX_MISSION_ASSETS = 100;

    private final Deliveries deliveries;

    DeliveriesFacade(Deliveries deliveries) {
        this.deliveries = deliveries;
    }

    /** The pull requests Floor42 opened for a mission (ADR-0036), oldest first. */
    public List<PullRequest> pullRequests(MissionId mission) {
        return deliveries.pullRequests(mission);
    }

    public Optional<PullRequest> pullRequest(MissionId mission, int number) {
        return deliveries.pullRequest(mission, number);
    }

    /** What the director chose to give the mission's sessions (ADR-0040); empty when nothing was chosen. */
    public List<String> missionAssets(MissionId mission) {
        return deliveries.missionAssets(mission);
    }

    /**
     * Checks done before Floor42 pushes anything for a pull request (ADR-0036): the director, and nobody else, asks for
     * it, on a real mission that works on a repository. Pushing is the one action of Floor42 outside the machine.
     */
    public void checkPullRequest(CommandContext ctx, MissionId id) {
        deliveries.checkPullRequest(ctx, id);
    }

    /**
     * Records the pull request that now exists on GitHub for a branch of the mission, after Floor42 pushed it on the
     * director's confirmation; {@code found} when Floor42 found it with {@code gh pr list} rather than from
     * {@code gh pr create}'s answer.
     */
    public List<EventEnvelope> recordPullRequest(
            CommandContext ctx,
            MissionId id,
            AgentId workspace,
            String url,
            int number,
            String branch,
            String base,
            String remote,
            boolean found) {
        return deliveries.recordPullRequest(ctx, id, workspace, url, number, branch, base, remote, found);
    }

    /** Records the checks GitHub reported on a pull request of the mission, read on the director's click. */
    public List<EventEnvelope> recordPullRequestChecks(
            CommandContext ctx, MissionId id, int number, List<String> checks) {
        return deliveries.recordPullRequestChecks(ctx, id, number, checks);
    }

    /**
     * The director records Claude Code plugin commands Floor42 ran on their confirmation (ADR-0040): an install or an
     * uninstall, into Floor42's own folder or the director's Claude Code. Never an agent's, never Floor42's own idea.
     */
    public List<EventEnvelope> recordPluginCommand(
            CommandContext ctx,
            String target,
            String action,
            String plugin,
            String marketplace,
            List<String> commands,
            Integer exitCode,
            boolean present,
            String version) {
        return deliveries.recordPluginCommand(
                ctx, target, action, plugin, marketplace, commands, exitCode, present, version);
    }

    /**
     * The director chooses which installed skills and agents the mission's sessions receive (ADR-0040), replacing any
     * earlier choice. Floor42 checks the items exist before calling; the domain checks their form, the actor, and that
     * the mission is real and not over.
     */
    public List<EventEnvelope> selectMissionAssets(CommandContext ctx, MissionId id, List<String> items) {
        return deliveries.selectMissionAssets(ctx, id, items);
    }

    /** The folder the mission's accepted deliverables are copied into (ADR-0051); empty for none (before 1.10.0). */
    public Optional<String> destination(MissionId mission) {
        return deliveries.destination(mission);
    }

    /** The copy made when the director accepted the mission's deliverables; empty when none was made. */
    public Optional<DeliveryCopy.Done> copy(MissionId mission) {
        return deliveries.copy(mission);
    }

    /**
     * The director's destination for the mission's deliverables, given when the mission is created: an absolute folder
     * Floor42 resolved and checked on disk. Director only, real missions on a repository only, before the mission ends.
     */
    public List<EventEnvelope> setDestination(CommandContext ctx, MissionId id, String folder) {
        return deliveries.setDestination(ctx, id, folder);
    }

    /**
     * Records what Floor42 copied into {@code folder}, the destination's folder for this mission, once, before the
     * mission completes (ADR-0051).
     */
    public List<EventEnvelope> recordCopy(
            CommandContext ctx, MissionId id, String folder, List<DeliveryCopy> files, int leftOut, boolean truncated) {
        return deliveries.recordCopy(ctx, id, folder, files, leftOut, truncated);
    }

    /** A delivery's proof (ADR-0058). */
    public ProofsFacade proofs() {
        return new ProofsFacade(deliveries.proofs);
    }

    /** The worktrees that were not clean when the director accepted the mission anyway; empty for none (ADR-0055). */
    public List<UncommittedWorktree> uncommittedAccepted(MissionId mission) {
        return deliveries.uncommittedAccepted(mission);
    }

    /**
     * The director accepts the mission although its worktrees hold uncommitted work, or Floor42 could not tell
     * (ADR-0055): recorded in the same command as the completion. Director only, before the mission ends.
     */
    public List<EventEnvelope> acceptUncommitted(
            CommandContext ctx, MissionId id, List<UncommittedWorktree> worktrees) {
        return deliveries.acceptUncommitted(ctx, id, worktrees);
    }
}
