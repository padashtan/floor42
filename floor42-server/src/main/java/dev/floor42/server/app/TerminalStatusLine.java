package dev.floor42.server.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * How the terminal's cost counter learns what Claude Code reports (ADR-0041). Claude Code gives its status line command
 * the session's figures as JSON on standard input ({@code cost.total_cost_usd}, its own estimate); Floor42 passes one
 * setting, a status line that copies that JSON to a file in the terminal's folder, then hands it to the director's own
 * status line command, unchanged, so the bar the director knows still shows. Claude Code runs the command through Git
 * Bash on Windows and sh elsewhere: the command is POSIX shell.
 */
final class TerminalStatusLine {

    static final String SETTINGS = "settings.json";
    static final String STATUS = "status.json";

    private TerminalStatusLine() {}

    /** The director's own status line, as Claude Code would pick it: local project settings, then project, then user. */
    static Optional<ObjectNode> directorsOwn(ObjectMapper json, Path claudeHome, Path project) {
        for (Path file : List.of(
                project.resolve(".claude").resolve("settings.local.json"),
                project.resolve(".claude").resolve(SETTINGS),
                claudeHome.resolve(SETTINGS))) {
            try {
                if (!Files.isRegularFile(file) || Files.size(file) > 1_000_000) continue;
                JsonNode line = json.readTree(file.toFile()).get("statusLine");
                if (line instanceof ObjectNode o && o.path("command").isTextual()) return Optional.of(o.deepCopy());
            } catch (IOException | RuntimeException e) {
                // Handled: A settings file Floor42 cannot read is Claude Code's to judge: Floor42 just does not chain
                // it.
            }
        }
        return Optional.empty();
    }

    /** The shell command: copy standard input to the status file, then run the director's own command on it, if any. */
    static String command(Path status, String own) {
        String file =
                "'" + status.toAbsolutePath().toString().replace('\\', '/').replace("'", "'\\''") + "'";
        if (own == null || own.isBlank()) return "cat > " + file;
        return "tee " + file + " | {\n" + own + "\n}";
    }

    /** Writes the settings file given to Claude Code with {@code --settings}, and returns it. */
    static Path write(ObjectMapper json, Path folder, Path claudeHome, Path project) throws IOException {
        Files.createDirectories(folder);
        ObjectNode line = directorsOwn(json, claudeHome, project).orElseGet(json::createObjectNode);
        String own = line.path("command").isTextual() ? line.get("command").asText() : null;
        line.put("type", "command");
        line.put("command", command(folder.resolve(STATUS), own));
        ObjectNode settings = json.createObjectNode();
        settings.set("statusLine", line);
        Path file = folder.resolve(SETTINGS);
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), settings);
        return file;
    }

    /** What Claude Code last reported, read from the status file; empty while it reported nothing readable. */
    static Optional<Reported> read(ObjectMapper json, Path status) {
        try {
            if (!Files.isRegularFile(status)) return Optional.empty();
            JsonNode node = json.readTree(status.toFile());
            if (node == null) return Optional.empty();
            JsonNode cost = node.path("cost").path("total_cost_usd");
            if (!cost.isNumber()) return Optional.empty();
            String model = node.path("model").path("display_name").asText(null);
            String session = node.path("session_id").asText(null);
            return Optional.of(new Reported(cost.asDouble(), model, session));
        } catch (IOException | RuntimeException e) {
            // Handled: Half written: the next reading will do.
            return Optional.empty();
        }
    }

    /** Claude Code's figures: its own cost estimate for the session, the model, the session's id. */
    record Reported(double costUsd, String model, String session) {}
}
