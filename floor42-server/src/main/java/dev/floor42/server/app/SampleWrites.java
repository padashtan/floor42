package dev.floor42.server.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.floor42.server.runtime.AgentRuntime.Governor;
import dev.floor42.server.runtime.AgentRuntime.Verdict;
import dev.floor42.server.runtime.AskOutcome;
import dev.floor42.server.runtime.ConfinedPath;
import dev.floor42.server.runtime.Confinement;
import dev.floor42.server.runtime.OutsideWorkspace;
import dev.floor42.server.runtime.RuntimeEvent;
import dev.floor42.server.runtime.RuntimeEvent.ToolCompleted;
import dev.floor42.server.runtime.RuntimeEvent.ToolRequested;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * The governor of a rehearsal's replayed session (ADR-0045): the mission's own governor decides on every call, and a
 * recorded {@code Write} the director allowed is then applied to the rehearsal's worktree of the sample repository,
 * with the recorded content, through the workshop's confinement. So the workshop shows a real diff to accept or reject,
 * on Floor42's throwaway sample only; a call denied or still pending writes nothing.
 */
final class SampleWrites implements Governor {

    private static final Logger LOG = Logger.getLogger(SampleWrites.class);

    private final Governor governor;
    private final Path worktree;
    private final String workdir;
    private final Map<String, ToolRequested> allowed = new ConcurrentHashMap<>();

    SampleWrites(Governor governor, Path worktree, String workdir) {
        this.governor = governor;
        this.worktree = worktree;
        this.workdir = workdir;
    }

    @Override
    public Verdict decide(ToolRequested call) {
        return noted(call, governor.decide(call));
    }

    @Override
    public Verdict awaitDecision(ToolRequested call, Duration patience) {
        return noted(call, governor.awaitDecision(call, patience));
    }

    private Verdict noted(ToolRequested call, Verdict v) {
        if (v == Verdict.ALLOW && call.tool().equals("Write")) allowed.put(call.toolUseId(), call);
        return v;
    }

    @Override
    public void completed(ToolCompleted result) {
        governor.completed(result);
        ToolRequested call = allowed.remove(result.toolUseId());
        if (call != null && !result.error()) write(call.input());
    }

    private void write(JsonNode input) {
        String path = relative(workdir, input.path("file_path").asText(""));
        try {
            ConfinedPath target = Confinement.relative(worktree, path, ConfinedPath.Mode.WRITE);
            Files.createDirectories(target.file().getParent());
            Files.writeString(target.file(), input.path("content").asText(""), StandardCharsets.UTF_8);
        } catch (OutsideWorkspace | IOException e) {
            // Handled: Left unwritten: the workshop then shows no change for it, never an invented one.
            LOG.warnf("Replayed write of %s not applied to the sample: %s", path, e.getMessage());
        }
    }

    /** A recorded path ("/workspace\notes.txt") relative to the recording's working folder ("notes.txt"). */
    static String relative(String workdir, String recorded) {
        String p = recorded.replace('\\', '/');
        String w = workdir.replace('\\', '/');
        return p.startsWith(w + "/") ? p.substring(w.length() + 1) : p;
    }

    @Override
    public void announced(ToolRequested call) {
        governor.announced(call);
    }

    @Override
    public void unreplayable(String toolUseId, String reason) {
        allowed.remove(toolUseId);
        governor.unreplayable(toolUseId, reason);
    }

    @Override
    public void observed(RuntimeEvent event) {
        governor.observed(event);
    }

    @Override
    public AskOutcome askDirector(
            String question, List<String> options, String recommendation, String toolUseId, Duration patience) {
        return governor.askDirector(question, options, recommendation, toolUseId, patience);
    }

    @Override
    public String violationNow(ToolRequested call) {
        return governor.violationNow(call);
    }

    @Override
    public JsonNode updatedInput(ToolRequested call) {
        return governor.updatedInput(call);
    }

    @Override
    public String explain(String toolUseId) {
        return governor.explain(toolUseId);
    }

    @Override
    public dev.floor42.domain.ActionId actionId(String toolUseId) {
        return governor.actionId(toolUseId);
    }

    @Override
    public boolean authorized(String toolUseId) {
        return governor.authorized(toolUseId);
    }

    @Override
    public void refuse(ToolRequested call, String detail) {
        governor.refuse(call, detail);
    }
}
