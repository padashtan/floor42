package dev.floor42.server.app;

import dev.floor42.server.runtime.Git;
import dev.floor42.server.runtime.GitProcess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The git side of a pull request (ADR-0036): which branch, which remote and which GitHub repository it names, and the
 * push itself, never forced. A read git answers with an error gives "unknown" (empty, null); a git that cannot run
 * throws a {@code GitFailure}.
 */
@ApplicationScoped
public class PullRequestGit {

    private static final Pattern HTTPS_REMOTE =
            Pattern.compile("^(?:https?|ssh)://(?:[^@/]+@)?([^/:]+)(?::\\d+)?/([^/]+)/([^/]+?)(?:\\.git)?/?$");
    private static final Pattern SAFE_REPOSITORY = Pattern.compile(
            "[a-z0-9][a-z0-9.-]{0,252}/[A-Za-z0-9_.][A-Za-z0-9._-]{0,99}/[A-Za-z0-9_.][A-Za-z0-9._-]{0,99}");
    private static final Pattern SCP_REMOTE = Pattern.compile("^(?:[^@]+@)?([^:/]+):([^/]+)/([^/]+?)(?:\\.git)?/?$");

    private final Git git;

    @Inject
    public PullRequestGit(Git git) {
        this.git = git;
    }

    /** The project's current branch; empty on a detached HEAD or when git cannot tell. */
    String currentBranch(Path project) {
        return git.outIfOk(project, "branch", "--show-current").orElse("").strip();
    }

    /** The branch's upstream remote, else {@code origin}, else the only remote; null when there is none. */
    String remoteFor(Path project, String branch) {
        if (!branch.isEmpty()) {
            String r = git.outIfOk(project, "config", "--get", "branch." + branch + ".remote")
                    .orElse("")
                    .strip();
            if (!r.isEmpty() && !r.equals(".")) return safe(r);
        }
        Optional<String> all = git.outIfOk(project, "remote");
        if (all.isEmpty()) return null;
        List<String> remotes =
                all.get().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (remotes.contains("origin")) return "origin";
        return remotes.size() == 1 ? safe(remotes.getFirst()) : null;
    }

    /** A remote named like an option ({@code --receive-pack=...}) is no remote: git would run what it names. */
    private static String safe(String remote) {
        return remote.startsWith("-") ? null : remote;
    }

    /** The remote's URL as configured (never rewritten), or null. */
    String remoteUrl(Path project, String remote) {
        String url = git.outIfOk(project, "config", "--get", "remote." + remote + ".url")
                .orElse("")
                .strip();
        return url.isEmpty() ? null : url;
    }

    /** {@code host/owner/name} for a remote URL such as https://github.com/o/r.git or git@github.com:o/r.git. */
    static Optional<String> repositoryOf(String url) {
        if (url == null) return Optional.empty();
        for (Pattern p : List.of(HTTPS_REMOTE, SCP_REMOTE)) {
            Matcher m = p.matcher(url.strip());
            if (m.matches() && !m.group(1).isBlank()) {
                String host = m.group(1).toLowerCase(Locale.ROOT);
                if (host.equals("localhost") || host.length() == 1) return Optional.empty();
                String repository = host + "/" + m.group(2) + "/" + m.group(3);
                // gh takes it as a positional argument: no leading dash, only what a host, owner and name hold.
                return SAFE_REPOSITORY.matcher(repository).matches() ? Optional.of(repository) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Never forced: a plain refspec, no '+', no --force. A remote branch that moved elsewhere is refused by git. To the
     * URL the director saw in the preview ({@code previewedUrl}), never the remote's name: a pushurl or a receivepack set
     * under the name is then no part of the push, and a remote.origin.url rewritten since the preview is refused.
     */
    GitProcess.Result push(Path project, String remote, String previewedUrl, String branch) {
        String url = remoteUrl(project, remote);
        if (url == null) return new GitProcess.Result(1, "", "The remote " + remote + " has no URL", false);
        if (!url.equals(previewedUrl))
            return new GitProcess.Result(
                    1, "", "The URL of the remote " + remote + " changed since the preview; nothing was pushed", false);
        // Without a global or system core.hooksPath, every pre-push hook lives in .git/hooks, where an agent can write
        // (sandbox off): a planted hook and the director's look the same, so none runs. With one, git never reads
        // .git/hooks (a local core.hooksPath is refused by NetworkConfigGuard) and the director's hooks stay on.
        boolean directorHooks = List.of("--global", "--system").stream()
                .anyMatch(scope -> git.outIfOk(project, "config", scope, "--get", "core.hooksPath")
                        .map(v -> !v.isBlank())
                        .orElse(false));
        List<String> args = new java.util.ArrayList<>(List.of("push", "--porcelain"));
        if (!directorHooks) args.add("--no-verify");
        args.addAll(List.of("--", url, "refs/heads/" + branch + ":refs/heads/" + branch));
        return git.run(project, args);
    }

    /** Whether the remote has the branch at {@code head}: true, false, or null when Floor42 could not read it. */
    Boolean pushedAt(Path project, String remote, String branch, String head) {
        GitProcess.Result r = git.run(project, "ls-remote", "--", remote, "refs/heads/" + branch);
        if (r.timedOut() || r.code() != 0) return null;
        String line = r.out().strip();
        if (line.isEmpty()) return false;
        return head != null && line.split("\\s+")[0].equals(head);
    }

    /** The files the branch changed since it left {@code base}; empty when git cannot tell. */
    List<String> changedFiles(Path project, String base, String head) {
        return git.outIfOk(project, "diff", "--name-only", base + "..." + head, "--")
                .orElse("")
                .lines()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
