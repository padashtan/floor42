package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one setting the director's terminal adds: a status line that copies what Claude Code reports (ADR-0041). */
class TerminalStatusLineTest {

    final ObjectMapper json = new ObjectMapper();

    @Test
    void theDirectorsOwnStatusLineStillRunsAfterTheCopy(@TempDir Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(
                home.resolve("settings.json"),
                "{\"statusLine\":{\"type\":\"command\",\"command\":\"exec node hud.js\",\"padding\":0},\"hooks\":{}}");
        Path settings = TerminalStatusLine.write(json, tmp.resolve("t"), home, project);
        var line = json.readTree(settings.toFile()).get("statusLine");
        assertThat(line.get("padding").asInt())
                .as("the director's own options kept")
                .isZero();
        assertThat(line.get("command").asText())
                .startsWith("tee '")
                .contains("/t/status.json' | {\nexec node hud.js\n}")
                .doesNotContain("\\");
        assertThat(json.readTree(settings.toFile()).has("hooks"))
                .as("nothing else copied")
                .isFalse();

        // A project's local settings win, as in Claude Code.
        Files.createDirectories(project.resolve(".claude"));
        Files.writeString(
                project.resolve(".claude/settings.local.json"), "{\"statusLine\":{\"command\":\"echo local\"}}");
        assertThat(TerminalStatusLine.directorsOwn(json, home, project)
                        .orElseThrow()
                        .get("command")
                        .asText())
                .isEqualTo("echo local");
    }

    @Test
    void withoutAStatusLineOfTheDirectorsTheCopyIsAllAndQuotesAreSafe(@TempDir Path tmp) throws Exception {
        Path status = tmp.resolve("it's").resolve("status.json");
        String expected = status.toAbsolutePath().toString().replace('\\', '/').replace("'", "'\\''");
        assertThat(TerminalStatusLine.command(status, null))
                .isEqualTo("cat > '" + expected + "'")
                .contains("it'\\''s/status.json'");
    }

    @Test
    void onlyAReportedCostIsRead(@TempDir Path tmp) throws Exception {
        Path status = tmp.resolve("status.json");
        assertThat(TerminalStatusLine.read(json, status)).isEmpty();
        Files.writeString(status, "{\"cost\":{\"total_cost_usd\":0.5},\"model\":{\"display_name\":\"Opus\"}}");
        assertThat(TerminalStatusLine.read(json, status).orElseThrow().costUsd())
                .isEqualTo(0.5);
        Files.writeString(status, "{\"cost\":{}}");
        assertThat(TerminalStatusLine.read(json, status))
                .as("no figure, no counter")
                .isEmpty();
        Files.writeString(status, "{\"cost\":");
        assertThat(TerminalStatusLine.read(json, status)).as("half written").isEmpty();
    }
}
