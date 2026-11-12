package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File tools stay in the agent's worktree, checked on the real file system (ADR-0012). */
class ConfinementTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    Path worktree;
    Path outside;

    @BeforeEach
    void layout() throws IOException {
        worktree = Files.createDirectories(tmp.resolve("worktree"));
        outside = Files.createDirectories(tmp.resolve("secrets"));
        Files.writeString(outside.resolve("id_ed25519"), "key");
        Files.writeString(worktree.resolve("README.md"), "hello");
        Files.writeString(worktree.resolve(".git"), "gitdir: elsewhere");
    }

    Optional<String> call(String tool, Map<String, String> input) {
        JsonNode node = JSON.valueToTree(input);
        return Confinement.violation(tool, node, worktree.toString());
    }

    @Test
    void filesInsideTheWorktreeAreReachable() {
        assertThat(call(
                        "Read",
                        Map.of("file_path", worktree.resolve("README.md").toString())))
                .isEmpty();
        assertThat(call("Read", Map.of("file_path", "README.md"))).isEmpty();
        assertThat(call(
                        "Write",
                        Map.of("file_path", worktree.resolve("src/New.java").toString())))
                .isEmpty();
        assertThat(call("Grep", Map.of("pattern", "TODO", "path", "src"))).isEmpty();
        assertThat(call("Glob", Map.of("pattern", "**/*.java"))).isEmpty();
    }

    /** CodeQL java/polynomial-redos: a name an agent writes is judged in linear time, however long and odd. */
    @Test
    void aPathologicalNameIsJudgedFast() {
        String tildes = "~0".repeat(100_000);
        // Dots, not spaces: Windows refuses a name that ends with a space before the name is judged.
        String dots = "x" + ".".repeat(200_000) + "y";
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            // The line break made ".*~\\d.*" try every start again, and hid the short name from it.
            assertThat(Confinement.check(tildes + "\n", worktree, true)).isPresent();
            assertThat(Confinement.check(tildes, worktree, true)).isPresent();
            assertThat(Confinement.check(dots, worktree, true)).isEmpty();
            assertThat(Confinement.refusal(worktree, dots + "/.git" + ".".repeat(200_000), ConfinedPath.Mode.READ))
                    .hasValueSatisfying(why -> assertThat(why).endsWith("is git's own data"));
        });
    }

    @Test
    void windowsSpellingsOfProtectedFoldersAreProtected() throws Exception {
        // Windows drops trailing dots and spaces and resolves 8.3 short names: ".git." and "GIT~1" are ".git".
        for (String path : List.of(
                ".git./hooks/pre-commit",
                ".git /hooks/pre-commit",
                "GIT~1/hooks/pre-commit",
                ".claude./settings.json",
                ".git::$INDEX_ALLOCATION/hooks/x",
                "notes.txt:hidden")) {
            assertThat(call("Write", Map.of("file_path", path))).as(path).isPresent();
        }
        assertThat(call("Write", Map.of("file_path", "docs/notes.v2.txt"))).isEmpty();
    }

    @Test
    void backslashesAreSeparatorsOnEveryOs() {
        // Recorded on Windows, replayed on Linux in CI: same verdict either way.
        assertThat(call("Write", Map.of("file_path", worktree + "\\notes.txt"))).isEmpty();
        assertThat(call("Read", Map.of("file_path", "docs\\..\\..\\secrets\\id_ed25519")))
                .isPresent();
        assertThat(call("Write", Map.of("file_path", ".git\\hooks\\pre-commit")))
                .isPresent();
    }

    @Test
    void filesOutsideTheWorktreeAreNot() {
        assertThat(call(
                        "Read",
                        Map.of("file_path", outside.resolve("id_ed25519").toString())))
                .hasValueSatisfying(v -> assertThat(v).contains("outside the agent's worktree"));
        assertThat(call("Read", Map.of("file_path", "../secrets/id_ed25519"))).isPresent();
        assertThat(call("Read", Map.of("file_path", "~/.ssh/id_ed25519"))).isPresent();
        assertThat(call("Grep", Map.of("pattern", "BEGIN", "path", outside.toString())))
                .isPresent();
        assertThat(call("Glob", Map.of("pattern", "../**/*"))).isPresent();
        assertThat(call("Glob", Map.of("pattern", outside.toString().replace('\\', '/') + "/*")))
                .isPresent();
        assertThat(call(
                        "Edit",
                        Map.of(
                                "file_path",
                                worktree.resolve("../secrets/id_ed25519").toString())))
                .isPresent();
    }

    @Test
    void gitAndClaudeFilesCanBeReadButNotWritten() {
        assertThat(call("Read", Map.of("file_path", ".git"))).isEmpty();
        assertThat(call("Write", Map.of("file_path", ".git"))).isPresent();
        assertThat(call("Write", Map.of("file_path", ".GIT/hooks/pre-commit"))).isPresent();
        assertThat(call("Write", Map.of("file_path", ".claude/settings.json"))).isPresent();
        assertThat(call("Edit", Map.of("file_path", "sub/.mcp.json"))).isPresent();
    }

    @Test
    void aCaseVariantOfTheProtectedNamesIsRefusedAtDelivery() {
        for (String p :
                new String[] {".CLAUDE/settings.json", "a/.Claude/skills/x/SKILL.md", ".MCP.json", "x\\.Mcp.JSON"}) {
            assertThat(Confinement.configurationPath(java.util.List.of("ok.txt", p)))
                    .contains(p);
        }
        assertThat(Confinement.configurationPath(java.util.List.of("claude.md", "src/mcp.json.txt")))
                .isEmpty();
    }

    @Test
    void aLinkInsideTheWorktreeLeadingOutsideCountsAsOutside() throws IOException {
        Path link = worktree.resolve("innocent");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows without the symlink privilege: a junction needs none and leads outside just the same.
            assumeTrue(junction(link, outside), "neither a symbolic link nor a junction could be created");
        }
        assertThat(call("Read", Map.of("file_path", "innocent/id_ed25519")))
                .hasValueSatisfying(v -> assertThat(v).contains("through a link"));
        assertThat(call("Write", Map.of("file_path", "innocent/new.txt"))).isPresent();
    }

    static boolean junction(Path link, Path target) {
        if (!System.getProperty("os.name").startsWith("Windows")) return false;
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0 && Files.exists(link);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    void otherToolsAreNotFileTools() {
        assertThat(call("Bash", Map.of("command", "cat ~/.ssh/id_ed25519"))).isEmpty();
        assertThat(call("WebFetch", Map.of("url", "https://example.com"))).isEmpty();
    }

    @Test
    void withoutAWorkspaceFileToolsAreRefused() {
        JsonNode node = JSON.valueToTree(Map.of("file_path", "README.md"));
        assertThat(Confinement.violation("Read", node, null)).isPresent();
    }
}
