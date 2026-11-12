package dev.floor42.server.app;

import java.nio.file.Path;

/**
 * One agent's branch of a mission as git says it is now, resolved by Floor42 itself, never from a name a caller gave.
 *
 * @param agent the agent whose branch it is
 * @param worktree the worktree's folder, null when Floor42 has no record of one
 * @param gitDir where git is asked: the worktree when it is there, else the repository; null when neither
 * @param present the worktree folder is there
 * @param tip the branch's last commit, full hash; null when git cannot read it
 * @param start the commit the worktree was made from, full hash; null when unknown
 * @param branch the branch's name, null when unknown
 */
record ResolvedBranch(
        String agent, Path worktree, Path gitDir, boolean present, String tip, String start, String branch) {

    /** A worktree Floor42 knows: from the journal, or found on disk under the mission's name. */
    record Known(String agent, Path worktree, String branch, String start) {}

    /** Git can list what the branch committed since its start. */
    boolean readable() {
        return gitDir != null && tip != null && start != null;
    }
}
