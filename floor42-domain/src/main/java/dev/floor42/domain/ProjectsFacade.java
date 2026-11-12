package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import java.util.Optional;

/**
 * The director's list of recent projects: pinned at the top, or taken off the list. Reached by
 * {@link Company#projects()}; the rules live in the recent projects area.
 */
public final class ProjectsFacade {

    private final RecentProjects recentProjects;

    ProjectsFacade(RecentProjects recentProjects) {
        this.recentProjects = recentProjects;
    }

    /** The director's mark on a project of the recent list, if any. */
    public Optional<ProjectMark> mark(String path) {
        return recentProjects.projectMark(path);
    }

    /**
     * Whether the director took this project off the recent list: forgotten, not pinned since, and no mission created
     * on it since.
     */
    public boolean forgotten(String path) {
        return recentProjects.projectForgotten(path);
    }

    /** The director pins a project a real mission worked on at the top of the recent list, or unpins it. */
    public List<EventEnvelope> pin(CommandContext ctx, String path, boolean pinned) {
        return pinned ? recentProjects.pin(ctx, path) : recentProjects.unpin(ctx, path);
    }

    /** The director takes a project off the recent list; its missions are untouched. */
    public List<EventEnvelope> forget(CommandContext ctx, String path) {
        return recentProjects.forgetProject(ctx, path);
    }
}
