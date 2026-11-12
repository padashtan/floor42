package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Claude Code's and MCP's own configuration in the worktree ({@code .claude}, {@code .mcp.json}) changes what runs
 * later outside the sandbox: the file tools refuse it (Confinement), and so must a shell. When they exist they are
 * mounted read-only in the real sandbox. Skipped where no sandbox backend exists.
 */
class SandboxProtectedFilesTest {

    @TempDir
    Path data;

    @BeforeAll
    static void sandboxRunsHere() {
        WslSandbox.platformOrSkip();
    }

    static final String WRITES = """
            try() { if "$@" >/dev/null 2>&1; then echo "WROTE $*"; else echo "FAILED $*"; fi; }
            try sh -c 'echo x >> .claude/settings.json'
            try sh -c 'echo x > .claude/new.json'
            try mkdir -p .claude/skills/x
            try rm .claude/settings.json
            try mv .claude .claude-moved
            try rm -rf .claude
            try sh -c 'echo x >> .mcp.json'
            try rm .mcp.json
            try sh -c 'echo ok > other.txt'
            echo end
            """;

    @Test
    void anExistingClaudeFolderAndMcpFileCannotBeChangedFromTheShell() throws Exception {
        Path wt = Files.createDirectories(data.resolve("wt"));
        Files.createDirectories(wt.resolve(".claude"));
        Files.writeString(wt.resolve(".claude/settings.json"), "{}\n");
        Files.writeString(wt.resolve(".mcp.json"), "{}\n");

        String out = run(WslSandbox.ready(data).argv(wt, WRITES, 120, false));

        assertThat(out).as("the script ran to its end").contains("end");
        assertThat(out.lines().filter(l -> l.startsWith("WROTE")).toList())
                .as(out)
                .containsExactly("WROTE sh -c echo ok > other.txt");
        assertThat(Files.readString(wt.resolve(".claude/settings.json"))).isEqualToNormalizingNewlines("{}\n");
        assertThat(Files.readString(wt.resolve(".mcp.json"))).isEqualToNormalizingNewlines("{}\n");
        try (var files = Files.list(wt.resolve(".claude"))) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("settings.json");
        }
    }

    private static String run(List<String> argv) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
        p.getOutputStream().close();
        var out = new java.io.ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                p.getInputStream().transferTo(out);
            } catch (IOException e) {
                // Handled: the output read so far is kept
            }
        });
        if (!p.waitFor(180, TimeUnit.SECONDS)) p.destroyForcibly();
        reader.join(5000);
        return out.toString(StandardCharsets.UTF_8);
    }
}
