package dev.floor42.server.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jboss.logging.Logger;

/**
 * The launches a previous daemon left without a session in the journal: it started a Claude Code process, then stopped
 * before the journal held its session (it named none, or named one the journal never got), so the process may have
 * spent up to its cap with nothing journaled. Found at start-up from the session folders (every process record left,
 * with its launch record), before the leftovers are stopped; the recovery skips those the journal holds already, under
 * the folder's launch id or the session's id, and records each other one, so the mission's budget counts its cap once.
 */
@ApplicationScoped
public class UnnamedLaunches {

    private static final Logger LOG = Logger.getLogger(UnnamedLaunches.class);

    /**
     * @param folder the session folder's name, unique: it makes the recorded id
     * @param capUsd the cap the process was launched with; null in a launch record written before 1.9.0
     * @param sessionId the Claude Code session it named, null when it named none; written before the journal holds
     *     the session, so the recovery checks the journal for it
     */
    public record Launch(
            String folder,
            String mission,
            String ticket,
            String agent,
            String model,
            Double capUsd,
            String sessionId) {}

    private final Queue<Launch> found = new ConcurrentLinkedQueue<>();
    private final ObjectMapper json;

    @Inject
    UnnamedLaunches(ObjectMapper json) {
        this.json = json;
    }

    /** Looks for them among the session folders; called before the leftover processes are stopped. */
    public void scan(Path sessions) {
        if (!Files.isDirectory(sessions)) return;
        try (var dirs = Files.list(sessions)) {
            for (Path dir : dirs.toList()) {
                Launch l = unnamed(dir);
                if (l != null) found.add(l);
            }
        } catch (IOException e) {
            // Handled: logged; a launch not found here is not counted, and the WARN says so
            LOG.warnf("Cannot look for launches left without a session: %s", e.getMessage());
        }
    }

    private Launch unnamed(Path dir) throws IOException {
        Path launch = dir.resolve(ClaudeCodeRuntime.LAUNCH_FILE);
        if (!Files.isRegularFile(dir.resolve(SessionProcesses.PROCESS_FILE)) || !Files.isRegularFile(launch))
            return null;
        JsonNode n = json.readTree(Files.readString(launch));
        if (!n.hasNonNull("mission") || !n.hasNonNull("ticket")) return null;
        return new Launch(
                dir.getFileName().toString(),
                n.get("mission").asText(),
                n.get("ticket").asText(),
                n.path("agent").asText(null),
                n.path("model").asText(null),
                n.hasNonNull("capUsd") ? n.get("capUsd").asDouble() : null,
                n.path("sessionId").asText(null));
    }

    /** What the scan found, each handed out once. */
    public List<Launch> take() {
        List<Launch> out = new ArrayList<>();
        for (Launch l; (l = found.poll()) != null; ) out.add(l);
        return out;
    }
}
