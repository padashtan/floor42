package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The path forms an agent's file tools may use between Windows and the WSL sandbox: what the permission rules match
 * (the summary) and what the confinement says when a sandbox path reaches a file tool.
 */
class ToolPathFormsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String WORKDIR = "C:\\Users\\dev\\worktrees\\ada";

    static String summary(String tool, Map<String, String> input, String workdir) {
        return ToolCatalog.summary(tool, JSON.valueToTree(input), workdir);
    }

    @Test
    void aWindowsPathInsideTheWorktreeIsMatchedWhateverItsCase() {
        // Windows ignores case: a rule naming secrets.env must apply to the path written in another case.
        assertThat(summary("Edit", Map.of("file_path", "c:/users/dev/worktrees/ada/secrets.env"), WORKDIR))
                .isEqualTo("secrets.env");
        assertThat(summary("Edit", Map.of("file_path", "C:\\Users\\dev\\worktrees\\ada\\docs\\a.md"), WORKDIR))
                .isEqualTo("docs/a.md");
        assertThat(summary("Grep", Map.of("pattern", "TODO", "path", "c:\\USERS\\dev\\worktrees\\ada\\src"), WORKDIR))
                .isEqualTo("TODO in src");
    }

    @Test
    void aNeighbourFolderOrAPosixPathInAnotherCaseIsNotInside() {
        assertThat(summary("Edit", Map.of("file_path", "c:/users/dev/worktrees/adam/x.txt"), WORKDIR))
                .isEqualTo("c:/users/dev/worktrees/adam/x.txt");
        assertThat(summary("Edit", Map.of("file_path", "/HOME/dev/wt/x.txt"), "/home/dev/wt"))
                .isEqualTo("/HOME/dev/wt/x.txt");
        assertThat(summary("Edit", Map.of("file_path", "/home/dev/wt/x.txt"), "/home/dev/wt/"))
                .isEqualTo("x.txt");
    }

    @Test
    void aSandboxPathGivenToAFileToolIsRefusedWithThePathFormItTakes() {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "floor42-path-forms")
                .toAbsolutePath();
        String why = Confinement.violation(
                        "Edit", JSON.valueToTree(Map.of("file_path", "/media/work/report.md")), root.toString())
                .orElseThrow();
        assertThat(why).contains("outside the agent's worktree");
        // The hint follows the host: on Windows it names the form the file tools take (theSandboxHint... below).
        if (java.io.File.separatorChar == '\\')
            assertThat(why).contains("sandbox's path form").contains("relative to your working directory");
        assertThat(Confinement.violation("Read", JSON.valueToTree(Map.of("file_path", "/etc/passwd")), root.toString())
                        .orElseThrow())
                .doesNotContain("sandbox's path form");
    }

    @Test
    void theSandboxHintIsGivenOnlyOnAWindowsHost() {
        assertThat(Confinement.sandboxHint("/mnt/c/Users/x", true)).contains("sandbox's path form");
        assertThat(Confinement.sandboxHint("/media/work/a.md", true)).contains("sandbox's path form");
        // On Linux or macOS without the sandbox, /mnt/c is a real folder: no hint about a sandbox.
        assertThat(Confinement.sandboxHint("/mnt/c/Users/x", false)).isEmpty();
        assertThat(Confinement.sandboxHint("/etc/passwd", true)).isEmpty();
    }

    @Test
    void aLineBreakInABashCommandShowsAsACommandSeparator() {
        assertThat(ToolCatalog.summary("Bash", JSON.valueToTree(Map.of("command", "git status\n  rm -rf x")), "C:/wt"))
                .isEqualTo("git status; rm -rf x");
    }

    @Test
    void aShellCommandIsNeverShownRelativeToTheWorktree() {
        // "<worktree>/git status" runs another program than "git status": the rule and the director must see the first.
        assertThat(summary("Bash", Map.of("command", "C:/w/git status"), "C:/w"))
                .isEqualTo("C:/w/git status");
        assertThat(summary("Bash", Map.of("command", "/home/dev/wt/rm -rf x"), "/home/dev/wt"))
                .isEqualTo("/home/dev/wt/rm -rf x");
    }
}
