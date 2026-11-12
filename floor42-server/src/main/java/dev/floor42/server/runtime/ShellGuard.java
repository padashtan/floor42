package dev.floor42.server.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import dev.floor42.domain.ActionCategory;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Refuses a shell command that reaches Floor42 itself, whatever the mission's permission table says: the data directory
 * (journal, the director's token, the installed daemon, other agents' worktrees) outside the agent's own worktree, and
 * the daemon's process. Seen on M-10: an agent allowed to run Bash read the director's token, called the API as the
 * director, replaced the installed daemon and stopped it. It also refuses {@code git push} and any call of {@code gh}:
 * the only outward action is the director's pull request (ADR-0036).
 *
 * <p>It is a tripwire, not a boundary. It reads the command's text, so a command built to hide what it does gets
 * through: with the sandbox off, {@code git --git-dir .git push}, quoting ({@code g"i"t}), an alias, a variable, an
 * encoded string, a script file or PowerShell with a no-break space all pass. It stops mistakes and plain attempts
 * and leaves a refusal in the journal; nothing more is claimed. The boundary is the operating-system sandbox
 * ({@code SHELL_SANDBOX=wsl}, ADR-0028), where the data directory is not visible at all; and since ADR-0034 the
 * director's token is no longer there to read, while an agent's own credential is refused on the API.
 */
public final class ShellGuard {

    private static final Pattern STOP_VERB = Pattern.compile(
            "(?<![A-Za-z0-9_])(stop-process|taskkill|kill|pkill|killall|spps|terminate)(?![A-Za-z0-9_])");

    // {@code git push} (ADR-0036: only the director pushes, from the workshop), the plugin commands and the kill of
    // Java are tested by ShellPatterns, in linear time on any command line.

    /** Any call of GitHub's command line, which acts with the director's own login. */
    private static final Pattern GH = Pattern.compile("(^|[\\s;|&(`'\"/])gh(\\.exe)?([\\s'\"]|$)");

    /**
     * Claude Code's own commands that change what is installed or configured ({@code claude plugin ...}, {@code claude
     * mcp ...}), and any command naming Claude Code's configuration folder variable: only the director installs, from
     * Floor42's Skills panel (ADR-0040).
     */
    private static boolean claudeConfig(String command) {
        return ShellPatterns.claudeConfig(command)
                || command.contains("claude_config_dir")
                || command.contains("claude_code_plugin");
    }

    private ShellGuard() {}

    /**
     * The same, for each of several data folders: Floor42's, and Office's former one when the journal was copied from
     * it (it still holds the old journal, earlier worktrees and the old installed daemon).
     */
    public static Optional<String> violation(
            String tool, JsonNode input, String workdir, List<Path> dataDirs, long daemonPid) {
        for (Path dir : dataDirs) {
            Optional<String> v = violation(tool, input, workdir, dir, daemonPid);
            if (v.isPresent()) return v;
        }
        return Optional.empty();
    }

    /** Why the command reaches Floor42 itself, or empty when it does not (or is no shell command). */
    public static Optional<String> violation(
            String tool, JsonNode input, String workdir, Path dataDir, long daemonPid) {
        if (ToolCatalog.category(tool) != ActionCategory.EXECUTE || dataDir == null) return Optional.empty();
        String command = normalize(input.path("command").asText(""));
        if (command.isBlank()) return Optional.empty();
        String data =
                normalize(dataDir.toAbsolutePath().normalize().getFileName().toString());

        // The agent's own worktree lives in the data directory: naming it is fine, in any spelling.
        String own = ownWorktree(workdir, dataDir);
        if (own != null) command = command.replaceAll(Pattern.quote(own) + "(?![\\w.-])", "<worktree>");
        if (command.contains("<worktree>/..")) {
            return Optional.of("the command climbs out of the agent's worktree into Floor42's data directory");
        }

        if (command.contains("session.token")) {
            return Optional.of("the command names the director's token: agents never act as the director");
        }
        if (command.contains("replay-export")) {
            return Optional.of("the command names the replay export: only the director exports a mission");
        }
        Pattern segment = Pattern.compile("(^|[/~\\s'\"=:])" + Pattern.quote(data) + "($|[/\\s'\";|&)])");
        if (segment.matcher(command).find()) {
            return Optional.of("the command reaches Floor42's data directory (" + data
                    + "): journal, token, installed daemon and other agents' work are off limits");
        }
        return programs(command, daemonPid);
    }

    /** The programs a command may not run: git push, gh, Claude plugins, a kill of Java or of the daemon. */
    private static Optional<String> programs(String command, long daemonPid) {
        if (ShellPatterns.gitPush(command) || GH.matcher(command).find()) {
            return Optional.of("the command pushes to a remote or drives GitHub with the director's login: only the"
                    + " director opens pull requests, from Floor42's workshop");
        }
        if (claudeConfig(command)) {
            return Optional.of("the command installs or configures Claude Code plugins or MCP servers: only the"
                    + " director installs, from Floor42's Skills panel");
        }
        if (ShellPatterns.stopsJava(command)) {
            return Optional.of("the command stops Java processes, and Floor42's daemon is one of them");
        }
        if (STOP_VERB.matcher(command).find()
                && Pattern.compile("(?<![A-Za-z0-9_])" + daemonPid + "(?![A-Za-z0-9_])")
                        .matcher(command)
                        .find()) {
            return Optional.of("the command stops Floor42's daemon (process " + daemonPid + ")");
        }
        return Optional.empty();
    }

    /** The worktree's path from the data directory on ("/.floor42/workspaces/m-10-bobi"), or null when not inside it. */
    static String ownWorktree(String workdir, Path dataDir) {
        if (workdir == null || workdir.isBlank()) return null;
        Path root = dataDir.toAbsolutePath().normalize();
        Path work = Path.of(workdir).toAbsolutePath().normalize();
        if (!work.startsWith(root) || work.equals(root)) return null;
        return normalize("/" + root.getFileName() + "/" + root.relativize(work));
    }

    /** Lower case, forward slashes, no doubled slash: the spellings of one path on Windows and in Git Bash agree. */
    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replace('\\', '/').replaceAll("/{2,}", "/");
    }
}
