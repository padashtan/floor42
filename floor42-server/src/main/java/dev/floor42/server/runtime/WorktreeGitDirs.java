package dev.floor42.server.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** What the sandbox reads of a worktree's git setup before it mounts it: its git directories and core.autocrlf. */
final class WorktreeGitDirs {

    private WorktreeGitDirs() {}

    /** The director's core.autocrlf for this worktree: true, false, input, or "none" when unset or unreadable. */
    static String autocrlf(Path worktree) {
        // Through Floor42's one git runner: its timeout applies (the output is not read before the wait) and its
        // environment too.
        try {
            GitProcess.Result r = GitProcess.git(worktree, List.of("config", "--get", "core.autocrlf"));
            if (r.timedOut() || r.code() != 0) return "none";
            String v = r.out().strip().toLowerCase(Locale.ROOT);
            return v.matches("true|false|input") ? v : "none";
        } catch (IOException e) {
            // Handled: unknown WSL setting reads as none
            return "none";
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller; the setting reads as none
            Thread.currentThread().interrupt();
            return "none";
        }
    }

    /** A linked worktree's git directories: the repository's, and the worktree's name in it. */
    record GitDirs(Path common, String name) {}

    /**
     * Reads the worktree's {@code .git} file ("gitdir: <repo>/.git/worktrees/<name>"); empty for a plain repository or
     * a folder outside git.
     */
    static java.util.Optional<GitDirs> gitDirs(Path worktree) {
        Path dotGit = worktree.resolve(".git");
        if (!java.nio.file.Files.isRegularFile(dotGit)) return java.util.Optional.empty();
        try {
            String line = java.nio.file.Files.readString(dotGit).strip();
            if (!line.startsWith("gitdir:")) return java.util.Optional.empty();
            Path own = Path.of(line.substring("gitdir:".length()).strip()).normalize();
            Path worktrees = own.getParent();
            if (worktrees == null || !worktrees.getFileName().toString().equals("worktrees")) {
                return java.util.Optional.empty();
            }
            String name = own.getFileName().toString();
            if (name.equals("-") || !name.matches("[A-Za-z0-9._-]+")) return java.util.Optional.empty();
            return java.util.Optional.of(new GitDirs(worktrees.getParent(), name));
        } catch (IOException | RuntimeException e) {
            // Handled: not a linked worktree Floor42 can map: no extra git folder is shared
            return java.util.Optional.empty();
        }
    }
}
