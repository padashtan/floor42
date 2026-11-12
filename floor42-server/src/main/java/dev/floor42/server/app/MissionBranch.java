package dev.floor42.server.app;

import dev.floor42.domain.DomainException;
import dev.floor42.domain.Workspace;
import dev.floor42.server.runtime.Git;
import java.nio.file.Path;

/**
 * A mission's work is committed on its own branch only (ADR-0028, amended 2026-11-03): an agent's command that moved
 * the worktree's HEAD onto another branch, the project's main for one, must not have the director's acceptance land
 * there.
 */
final class MissionBranch {

    private MissionBranch() {}

    /** The workspace, when its worktree is on the mission's branch; refused otherwise. */
    static Workspace checked(Git git, Workspace w) {
        String current = git.outIfOk(Path.of(w.path()), "symbolic-ref", "--quiet", "HEAD")
                .map(String::strip)
                .orElse("");
        if (!current.equals("refs/heads/" + w.branch())) {
            throw new DomainException(
                    "WRONG_BRANCH",
                    "The worktree is on " + (current.isEmpty() ? "no branch" : current) + ", not on the mission's "
                            + w.branch() + ": nothing was committed");
        }
        return w;
    }
}
