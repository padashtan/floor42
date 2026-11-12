package dev.floor42.server.runtime;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which repository settings a git that talks to a remote must not honour. Without the shell sandbox an agent can write
 * the repository's own configuration, and git reads from it where an outgoing push goes ({@code remote.*.pushurl},
 * {@code url.*.insteadOf}), which program carries it ({@code core.sshCommand}, {@code core.gitProxy}, {@code
 * remote.*.receivepack}, {@code remote.*.vcs}, a credential helper) and more files of settings ({@code include}). The
 * director's own (global and system) settings are untouched: only the repository's and the worktree's are read. A
 * repository that sets one is refused, never silently overridden (an empty {@code sshCommand} would break pushes).
 */
final class NetworkConfigGuard {

    /** The commands that open a connection. "fetch" and "pull" have no caller today: kept as a defensive default. */
    static final Set<String> COMMANDS = Set.of("push", "ls-remote", "fetch", "pull");

    /**
     * {@code git config --list -z --show-scope}: every setting with where it comes from. Records are NUL separated:
     * {@code scope NUL key LF value NUL}. A key cannot hold a line feed, so it ends at the first one (an {@code =} can
     * sit in a quoted subsection name, which is why the plain listing is not parsed).
     */
    static final java.util.List<String> LIST = java.util.List.of("config", "--list", "-z", "--show-scope");

    /**
     * Keys that steer a connection, whole families where the project writes none of them: the program and the proxy
     * that carry it, where it goes, what it trusts, which hooks and signing program run around it, which transports are
     * allowed, and other files of settings. Floor42 writes nothing into a repository's configuration (it passes
     * {@code -c} on the command line), so a repository that has one of these was written by someone else.
     */
    private static final Pattern RISKY = Pattern.compile(
            "core\\.(sshcommand|gitproxy|askpass|hookspath)"
                    + "|credential\\..*"
                    + "|https?\\..*"
                    + "|protocol\\..*"
                    + "|gpg\\..*"
                    // A remote named like a URL is what git picks for `git push <url>`: its settings win over the URL.
                    + "|remote\\..*[:/\\\\].*"
                    + "|remote\\..*\\.(receivepack|uploadpack|pushurl|proxy|proxyauthmethod|vcs|receive-pack|upload-pack)"
                    + "|url\\..*"
                    + "|include\\..*|includeif\\..*",
            Pattern.DOTALL);

    private NetworkConfigGuard() {}

    /** The first risky setting the repository or the worktree holds in {@code configList}, if any. */
    static Optional<String> risky(String configList) {
        String[] t = configList.split("\u0000", -1);
        for (int i = 0; i + 1 < t.length; i += 2) {
            if (!t[i].equals("local") && !t[i].equals("worktree")) continue;
            int lf = t[i + 1].indexOf('\n');
            String key = (lf < 0 ? t[i + 1] : t[i + 1].substring(0, lf)).toLowerCase(Locale.ROOT);
            if (RISKY.matcher(key).matches()) return Optional.of(key);
        }
        return Optional.empty();
    }
}
