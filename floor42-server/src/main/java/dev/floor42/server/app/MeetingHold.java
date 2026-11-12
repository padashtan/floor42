package dev.floor42.server.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.floor42.domain.AgentId;
import dev.floor42.server.runtime.AgentRuntime.Governor;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.AgentRuntime.Verdict;
import dev.floor42.server.runtime.AskOutcome;
import dev.floor42.server.runtime.RuntimeEvent;
import dev.floor42.server.runtime.RuntimeEvent.ToolCompleted;
import dev.floor42.server.runtime.RuntimeEvent.ToolRequested;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The director's meeting, at the hook (ADR-0047): while the session's agent attends an open meeting, every new tool call
 * is refused with the meeting as the reason, read from the journal at each call; a call that was waiting for the
 * director is refused too when its answer comes during the meeting. The call already running is never cut: this hold
 * counts the calls it let run until their result comes back, so Floor42 stops a session only between two calls. A call
 * counts as running while it shows signs of life (its allow, then Claude Code's heartbeat every 30 s or so); one silent
 * for longer than {@code longestSilence} (a result that never came) no longer keeps the session from being stopped,
 * except a subagent or an MCP tool, whose silence is allowed much longer: no heartbeat is proven for them.
 */
final class MeetingHold implements Governor {

    static final String REASON = "The director called you to a meeting about the mission. Stop here and end your turn"
            + " now with one line on where you are: your work stays in your working directory as it is, nothing is"
            + " lost, and Floor42 gives you the ticket back after the meeting with what the director said.";

    private final OfficeService office;
    private final SessionSpec spec;
    private final Governor inner;
    private final Set<String> held = ConcurrentHashMap.newKeySet();
    /** Each call this hold let run, with its last sign of life and how long it may then stay silent. */
    private final Map<String, Sign> running = new ConcurrentHashMap<>();

    private final Duration longestSilence;
    private final Duration longestLongSilence;

    private record Sign(Instant at, Duration bound) {
        boolean alive(Instant now) {
            return at.plus(bound).isAfter(now);
        }
    }

    /**
     * @param longestSilence how long an ordinary call may stay silent and still count as running
     * @param longestLongSilence the same for a tool that may run long with no heartbeat at all (ADR-0047, amendment 3)
     */
    MeetingHold(
            OfficeService office,
            SessionSpec spec,
            Governor inner,
            Duration longestSilence,
            Duration longestLongSilence) {
        this.office = office;
        this.spec = spec;
        this.inner = inner;
        this.longestSilence = longestSilence;
        this.longestLongSilence = longestLongSilence;
    }

    /**
     * A subagent (Task, Agent) or an MCP tool may work for minutes without Claude Code's heartbeat, which is proven
     * only for the built-in tools: never take such a call for dead on the short bound.
     */
    static boolean mayRunLong(String tool) {
        return tool != null && (tool.equals("Task") || tool.equals("Agent") || tool.startsWith("mcp__"));
    }

    AgentId agent() {
        return spec.agent();
    }

    SessionSpec spec() {
        return spec;
    }

    /** True once a call of this session was refused for a meeting: its last text is no report. */
    boolean refusedForMeeting() {
        return !held.isEmpty();
    }

    /** True while a call this hold let run has not sent its result back: the session must not be stopped now. */
    boolean toolRunning() {
        Instant now = Instant.now();
        return running.values().stream().anyMatch(sign -> sign.alive(now));
    }

    @Override
    public Verdict decide(ToolRequested call) {
        if (inMeeting()) return refuse(call);
        return ran(call, inner.decide(call));
    }

    @Override
    public Verdict awaitDecision(ToolRequested call, Duration patience) {
        Verdict v = inner.awaitDecision(call, patience);
        if (v == Verdict.ALLOW && inMeeting()) return refuse(call);
        return ran(call, v);
    }

    private Verdict refuse(ToolRequested call) {
        held.add(call.toolUseId());
        inner.announced(call);
        return Verdict.DENY;
    }

    private Verdict ran(ToolRequested call, Verdict v) {
        if (v == Verdict.ALLOW)
            running.put(
                    call.toolUseId(),
                    new Sign(Instant.now(), mayRunLong(call.tool()) ? longestLongSilence : longestSilence));
        return v;
    }

    private boolean inMeeting() {
        return office.query(c -> c.meetings().attending(spec.agent()).isPresent());
    }

    @Override
    public void completed(ToolCompleted result) {
        running.remove(result.toolUseId());
        inner.completed(result);
    }

    @Override
    public void announced(ToolRequested call) {
        inner.announced(call);
    }

    @Override
    public void unreplayable(String toolUseId, String reason) {
        running.remove(toolUseId);
        inner.unreplayable(toolUseId, reason);
    }

    @Override
    public void observed(RuntimeEvent event) {
        if (event instanceof RuntimeEvent.ToolProgress p)
            running.computeIfPresent(p.toolUseId(), (id, was) -> new Sign(Instant.now(), was.bound()));
        inner.observed(event);
    }

    @Override
    public AskOutcome askDirector(
            String question, List<String> options, String recommendation, String toolUseId, Duration patience) {
        return inner.askDirector(question, options, recommendation, toolUseId, patience);
    }

    @Override
    public String violationNow(ToolRequested call) {
        return inner.violationNow(call);
    }

    @Override
    public JsonNode updatedInput(ToolRequested call) {
        return inner.updatedInput(call);
    }

    @Override
    public String explain(String toolUseId) {
        return held.contains(toolUseId) ? REASON : inner.explain(toolUseId);
    }

    // The MCP server asks the session's governor whether the hook authorized a call (the broker's call_resource):
    // without these, the interface's defaults refused every broker call of a real session (pilot, M-17).
    @Override
    public dev.floor42.domain.ActionId actionId(String toolUseId) {
        return inner.actionId(toolUseId);
    }

    @Override
    public boolean authorized(String toolUseId) {
        return inner.authorized(toolUseId);
    }

    @Override
    public void refuse(ToolRequested call, String detail) {
        inner.refuse(call, detail);
    }
}
