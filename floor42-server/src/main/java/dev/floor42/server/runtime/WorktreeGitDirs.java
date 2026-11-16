package dev.floor42.server.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** What the sandbox reads of a worktree's git setup before it mounts it: its git directories and the settings git needs. */
final class WorktreeGitDirs {

    private WorktreeGitDirs() {}

    /**
     * The repository settings git needs inside the sandbox, as "key=value" lines: the sandbox hides the repository's
     * config (it names remotes, tokens in their URLs, credential helpers) and passes only these. Read on the host from
     * the director's git, filtered by name and by value; core.bare is always false (the sandbox sets a work tree).
     * Empty when git cannot read the folder's settings.
     */
    static List<String> gitConfig(Path worktree) {
        // Through Floor42's one git runner: its timeout applies (the output is not read before the wait) and its
        // environment too.
        try {
            GitProcess.Result r = GitProcess.git(worktree, List.of("config", "--list"));
            if (r.timedOut() || r.code() != 0) return List.of();
            var keys = new java.util.LinkedHashMap<String, String>();
            for (String line : r.out().lines().toList()) {
                int eq = line.indexOf('=');
                if (eq < 1) continue;
                String k = line.substring(0, eq).toLowerCase(Locale.ROOT);
                String v = line.substring(eq + 1).strip();
                if (!allowedKey(k)) continue;
                // The last value wins, as in git: a later one git would refuse drops the earlier one too.
                if (allowedValue(k, v)) keys.put(k, k.equals("core.autocrlf") ? v.toLowerCase(Locale.ROOT) : v);
                else keys.remove(k);
            }
            keys.put("core.bare", "false");
            return keys.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .toList();
        } catch (IOException e) {
            // Handled: unreadable settings pass nothing to the sandbox
            return List.of();
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller; nothing is passed
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /** Only settings that change how git reads the work tree and the repository format: never a remote, URL or program. */
    static boolean allowedKey(String key) {
        return KEYS.contains(key) || key.matches("extensions[.][a-z0-9]+");
    }

    /** A plain word, nothing a shell or git would read as more; core.autocrlf as git accepts it. */
    static boolean allowedValue(String key, String value) {
        if (key.equals("core.autocrlf")) return value.matches("(?i)true|false|input");
        return value.matches("[A-Za-z0-9._-]{1,64}");
    }

    private static final java.util.Set<String> KEYS = java.util.Set.of(
            "core.filemode", "core.autocrlf", "core.ignorecase", "core.symlinks", "core.repositoryformatversion");

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
