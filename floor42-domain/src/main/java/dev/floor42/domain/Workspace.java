package dev.floor42.domain;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Where one agent works on one mission: a git worktree on a branch of its own, never the director's checkout. Files the
 * director edited there are remembered: agents never overwrite them silently (spec section 11.3).
 */
public final class Workspace {

    private final MissionId mission;
    private final AgentId agent;
    private final String path;
    private final String branch;
    /** The commit the worktree was made from, when the mission started there: it never moves. */
    private final String startCommit;
    /** The commit the agent's work is measured from: the mission's start, then the director's last merged commit. */
    String baseCommit;

    final Set<String> editedByDirector = new LinkedHashSet<>();

    Workspace(MissionId mission, AgentId agent, String path, String branch, String baseCommit) {
        this.mission = mission;
        this.agent = agent;
        this.path = path;
        this.branch = branch;
        this.baseCommit = baseCommit;
        this.startCommit = baseCommit;
    }

    public MissionId mission() {
        return mission;
    }

    public AgentId agent() {
        return agent;
    }

    public String path() {
        return path;
    }

    public String branch() {
        return branch;
    }

    public String baseCommit() {
        return baseCommit;
    }

    /** The commit the worktree was made from; unlike {@link #baseCommit()}, a merge of the project never moves it. */
    public String startCommit() {
        return startCommit;
    }

    /** Paths relative to the workspace, with forward slashes. */
    public Set<String> editedByDirector() {
        return Set.copyOf(editedByDirector);
    }
}
