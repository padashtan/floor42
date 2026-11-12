package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A shell command never reaches Floor42 itself; the commands below are those an agent really ran on M-10. */
class ShellGuardTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path DATA = Path.of("C:/Users/alice/.floor42");
    static final String WORKTREE = "C:/Users/alice/.floor42/workspaces/M-10-bobi";
    static final long DAEMON = 14244;

    Optional<String> bash(String command) {
        return ShellGuard.violation("Bash", JSON.valueToTree(Map.of("command", command)), WORKTREE, DATA, DAEMON);
    }

    @Test
    void theAgentWorksInItsOwnWorktreeInAnySpelling() {
        assertThat(bash("cd C:/Users/alice/.floor42/workspaces/M-10-bobi; git log --oneline -3"))
                .isEmpty();
        assertThat(bash("cd C:\\Users\\alice\\.floor42\\workspaces\\M-10-bobi && ./mvnw verify"))
                .isEmpty();
        assertThat(bash("ls ~/.floor42/workspaces/M-10-bobi/floor42-server/target"))
                .isEmpty();
        assertThat(bash("npm test && git status --short")).isEmpty();
    }

    @Test
    void theDirectorsTokenIsNeverNamed() {
        assertThat(bash("T=$(tr -d '\\r\\n' < .floor42/session.token); curl -H \"Authorization: Bearer $T\" ..."))
                .hasValueSatisfying(v -> assertThat(v).contains("director's token"));
        assertThat(bash("cat session.token")).isPresent();
    }

    @Test
    void anAgentNeverExportsAReplay() {
        assertThat(bash("curl -X POST http://127.0.0.1:7420/api/missions/M-1/replay-export -d '{}'"))
                .hasValueSatisfying(v -> assertThat(v).contains("only the director exports"));
    }

    @Test
    void theDataDirectoryIsOffLimits() {
        assertThat(bash("cd ~/.floor42; cp floor42.db office-before-1.1.0.db")).isPresent();
        assertThat(bash("sqlite3 \"file:C:/Users/alice/.floor42/floor42.db?mode=ro\" .tables"))
                .isPresent();
        assertThat(bash("Copy-Item -Recurse build $HOME\\.floor42\\app")).isPresent();
        assertThat(bash("ls ~/.floor42/workspaces/M-10-thibaud")).isPresent();
        assertThat(bash("cat ~/.floor42/workspaces/M-10-bobi2/notes.txt")).isPresent();
        assertThat(bash("cd C:/Users/alice/.floor42/workspaces/M-10-bobi/../../app"))
                .isPresent();
    }

    /** After 1.5.0, Office's former folder holds the old journal, the old daemon and the earlier missions' worktrees. */
    @Test
    void officesFormerDataFolderIsOffLimitsToo() {
        Path office = Path.of("C:/Users/alice/.office");
        java.util.List<Path> both = java.util.List.of(DATA, office);
        java.util.function.BiFunction<String, String, Optional<String>> run = (workdir, command) ->
                ShellGuard.violation("Bash", JSON.valueToTree(Map.of("command", command)), workdir, both, DAEMON);

        assertThat(run.apply(WORKTREE, "cp ~/.office/office.db /tmp/x")).isPresent();
        assertThat(run.apply(WORKTREE, "ls ~/.floor42/workspaces/M-10-thibaud")).isPresent();
        // An earlier mission's agent still works in its worktree, kept in the former folder.
        String old = "C:/Users/alice/.office/workspaces/M-9-bobi";
        assertThat(run.apply(old, "cd C:/Users/alice/.office/workspaces/M-9-bobi && ./mvnw verify"))
                .isEmpty();
        assertThat(run.apply(old, "cat ~/.office/workspaces/M-9-chris/notes.txt"))
                .isPresent();
    }

    @Test
    void theDaemonIsNeverStopped() {
        assertThat(bash("powershell -Command \"Stop-Process -Id 14244 -Force\""))
                .isPresent();
        assertThat(bash("taskkill /F /IM java.exe")).isPresent();
        assertThat(bash("Get-Process java | Stop-Process")).isPresent();
        assertThat(bash("pkill -f java")).isPresent();
        // Another process, or the number in another sense, is fine.
        assertThat(bash("kill 999")).isEmpty();
        assertThat(bash("echo 14244")).isEmpty();
    }

    @Test
    void anAgentNeverPushesNorDrivesGitHub() {
        assertThat(bash("git push origin floor42/m-10-bobi"))
                .hasValueSatisfying(v -> assertThat(v).contains("pull requests"));
        assertThat(bash("cd C:/Users/alice/.floor42/workspaces/M-10-bobi && git -C . push --force"))
                .isPresent();
        assertThat(bash("git -c credential.helper= push")).isPresent();
        assertThat(bash("gh pr create --fill")).isPresent();
        assertThat(bash("npm test; gh api repos/acme/app")).isPresent();
        assertThat(bash("\"C:/Program Files/GitHub CLI/gh.exe\" auth token")).isPresent();
        // A word that contains them, or a message that names them, is fine.
        assertThat(bash("git commit -m \"fix the push button\"")).isEmpty();
        assertThat(bash("git log --oneline --grep push")).isEmpty();
        assertThat(bash("echo high; ls ghost")).isEmpty();
    }

    @Test
    void anAgentNeverInstallsAPluginNorAnMcpServer() {
        assertThat(bash("claude plugin install superpowers@claude-plugins-official"))
                .hasValueSatisfying(v -> assertThat(v).contains("Skills panel"));
        assertThat(bash("npm test && claude --verbose plugins marketplace add evil/repo"))
                .isPresent();
        assertThat(bash("\"C:/Users/a/.local/bin/claude.exe\" mcp add x -- npx y"))
                .isPresent();
        assertThat(bash("CLAUDE_CONFIG_DIR=/tmp/c claude -p hi")).isPresent();
        // Naming Claude Code otherwise is fine.
        assertThat(bash("claude --version")).isEmpty();
        assertThat(bash("grep -r plugin docs/claude-notes.md")).isEmpty();
    }

    @Test
    void onlyShellCommandsAreRead() {
        assertThat(ShellGuard.violation(
                        "Read", JSON.valueToTree(Map.of("file_path", "session.token")), WORKTREE, DATA, DAEMON))
                .isEmpty();
    }
}
