package dev.floor42.server.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.floor42.server.runtime.AgentRuntime.Governor;
import dev.floor42.server.runtime.AgentRuntime.Verdict;
import dev.floor42.server.runtime.RuntimeEvent.ToolCompleted;
import dev.floor42.server.runtime.RuntimeEvent.ToolRequested;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One live session's governor, around the mission's: counts Floor42's verdicts for the session report, refuses a shell
 * command when the sandbox the director requires does not work here, rewrites an allowed one to run in it, and keeps
 * the last sign of life the watchdog reads.
 */
final class SessionTally implements Governor {

    final Governor inner;
    /** The worktree the session's shell commands run at, in the sandbox. */
    final Path workspace;

    private final Sandbox sandbox;
    /** Calls Floor42 refused itself, with the reason (no sandbox where one is required). */
    private final Map<String, String> refused = new ConcurrentHashMap<>();

    final AtomicInteger allowed = new AtomicInteger();
    final AtomicInteger denied = new AtomicInteger();
    final AtomicInteger pending = new AtomicInteger();
    /** When the session last showed a sign of life: a line on its stream, or a tool call through the hook. */
    final AtomicLong lastSign = new AtomicLong(System.nanoTime());
    /** Hook calls waiting for the director: a session waiting for an answer is not stuck. */
    final AtomicInteger waiting = new AtomicInteger();

    /** A reviewer's session: its shell only runs in the sandbox, with the worktree mounted read-only. */
    private final boolean readOnlyShell;

    SessionTally(Governor inner, Path workspace, Sandbox sandbox) {
        this(inner, workspace, sandbox, false);
    }

    SessionTally(Governor inner, Path workspace, Sandbox sandbox, boolean readOnlyShell) {
        this.inner = inner;
        this.workspace = workspace;
        this.sandbox = sandbox;
        this.readOnlyShell = readOnlyShell;
    }

    /** Whether a reviewer's shell call may not run: it needs the sandbox on and working, whatever the table says. */
    private boolean readOnlyShellUnsafe(ToolRequested call) {
        return readOnlyShell && "Bash".equals(call.tool()) && !(sandbox.enabled() && sandbox.available());
    }

    /** A shell command while the director requires the sandbox and it does not work here: refused, fail closed. */
    boolean sandboxMissing(ToolRequested call) {
        return "Bash".equals(call.tool()) && sandbox.enabled() && !sandbox.available();
    }

    /**
     * Why this REAL session does not start: its shell must run in the WSL sandbox (the director's setting, or imposed
     * unless unsafe mode is on, ADR-0063) and the sandbox does not work here. Null when it may start.
     */
    String startRefusal() {
        if (!sandbox.enabled() || sandbox.available()) return null;
        return "SANDBOX_REQUIRED: the WSL sandbox does not work on this machine, and a real mission's shell may not run"
                + " without it. Install or repair WSL, or switch unsafe mode on in the settings.";
    }

    Verdict count(Verdict v) {
        (v == Verdict.ALLOW ? allowed : v == Verdict.DENY ? denied : pending).incrementAndGet();
        return v;
    }

    void alive() {
        lastSign.set(System.nanoTime());
    }

    @Override
    public Verdict decide(ToolRequested call) {
        alive();
        if (readOnlyShellUnsafe(call)) {
            refused.put(
                    call.toolUseId(),
                    "A reviewer's shell runs only in the WSL sandbox with the work mounted read-only, and the sandbox is"
                            + " off or does not work here: no shell command runs for this review.");
            inner.announced(call);
            return count(Verdict.DENY);
        }
        if (sandboxMissing(call)) {
            refused.put(
                    call.toolUseId(),
                    "The director requires the shell to run in the WSL sandbox, and it does not work on this machine"
                            + " right now: no shell command runs until it does.");
            inner.announced(call);
            return count(Verdict.DENY);
        }
        Verdict v = inner.decide(call);
        return v == Verdict.PENDING ? v : count(lastCheck(call, v));
    }

    /**
     * An allow is checked once more right before it is answered: the file system may have changed while the director
     * decided (a link swapped in). The call is then recorded as refused, with the reason, and counted as a deny.
     */
    private Verdict lastCheck(ToolRequested call, Verdict v) {
        if (v != Verdict.ALLOW) return v;
        String now = inner.violationNow(call);
        if (now == null) return v;
        refused.put(call.toolUseId(), now);
        late.add(call.toolUseId());
        inner.completed(new ToolCompleted(call.toolUseId(), true, true, "Refused at the last check: " + now));
        return Verdict.DENY;
    }

    /** Calls refused by the last check: their tool result from the stream only confirms it. */
    private final java.util.Set<String> late = ConcurrentHashMap.newKeySet();

    @Override
    public Verdict awaitDecision(ToolRequested call, Duration patience) {
        waiting.incrementAndGet();
        try {
            return count(lastCheck(call, inner.awaitDecision(call, patience)));
        } finally {
            waiting.decrementAndGet();
            alive();
        }
    }

    @Override
    public void completed(ToolCompleted result) {
        if (late.contains(result.toolUseId())) return;
        inner.completed(result);
    }

    @Override
    public void announced(ToolRequested call) {
        inner.announced(call);
    }

    @Override
    public void observed(RuntimeEvent event) {
        inner.observed(event);
    }

    @Override
    public void refuse(ToolRequested call, String detail) {
        inner.refuse(call, detail);
    }

    @Override
    public void unreplayable(String toolUseId, String reason) {
        inner.unreplayable(toolUseId, reason);
    }

    @Override
    public dev.floor42.domain.ActionId actionId(String toolUseId) {
        return inner.actionId(toolUseId);
    }

    @Override
    public boolean authorized(String toolUseId) {
        return inner.authorized(toolUseId);
    }

    @Override
    public String explain(String toolUseId) {
        String own = refused.get(toolUseId);
        return own != null ? own : inner.explain(toolUseId);
    }

    /** The shell command, rewritten to run in the sandbox when the director requires it. */
    @Override
    public String violationNow(ToolRequested call) {
        return inner.violationNow(call);
    }

    @Override
    public JsonNode updatedInput(ToolRequested call) {
        if (!"Bash".equals(call.tool())) return null;
        // Fail closed: a reviewer's command is never handed back unwrapped (decide() denied it before this).
        if (readOnlyShell && (workspace == null || !sandbox.enabled())) {
            throw new IllegalStateException("A reviewer's shell command cannot run outside the sandbox");
        }
        if (!sandbox.enabled() || workspace == null) return null;
        if (!(call.input() instanceof ObjectNode in)) return unwrapped();
        String command = in.path("command").asText("");
        if (command.isBlank()) return unwrapped();
        ObjectNode out = in.deepCopy();
        out.put("command", sandbox.wrapForShell(workspace, command, readOnlyShell));
        return out;
    }

    /** No command to wrap: null for any session, but a reviewer's call is never handed back as it came. */
    private JsonNode unwrapped() {
        if (readOnlyShell) throw new IllegalStateException("A reviewer's shell input cannot be wrapped");
        return null;
    }

    /** A session waiting for the director's answer is not stuck. */
    @Override
    public AskOutcome askDirector(
            String question, List<String> options, String recommendation, String toolUseId, Duration patience) {
        waiting.incrementAndGet();
        try {
            return inner.askDirector(question, options, recommendation, toolUseId, patience);
        } finally {
            waiting.decrementAndGet();
            alive();
        }
    }
}
