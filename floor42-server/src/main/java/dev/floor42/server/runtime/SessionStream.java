package dev.floor42.server.runtime;

import dev.floor42.domain.Origin;
import dev.floor42.server.runtime.AgentRuntime.Governor;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.RuntimeEvent.AgentText;
import dev.floor42.server.runtime.RuntimeEvent.SessionEnded;
import dev.floor42.server.runtime.RuntimeEvent.SessionStarted;
import dev.floor42.server.runtime.RuntimeEvent.ToolCompleted;
import dev.floor42.server.runtime.RuntimeEvent.ToolRequested;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * What one live session's stream said, event by event: its id and model, the calls it announced, its last text, its
 * end, and the notes for the director. Governance happens in the hook; the stream is counted and passed on.
 */
final class SessionStream {

    final List<String> notes = new ArrayList<>();
    String sessionId;
    String model;
    String finalText;
    SessionEnded end;
    int toolCalls;
    /** The session folder's name: the stable id of its launch (a restart finds the same one). */
    String launch;
    /** The identity token the session runs with (IAM mode), journaled right after its start; null otherwise. */
    RuntimeEvent.TokenIssued issued;

    private boolean launched;

    private final SessionSpec spec;
    private final SessionTally tally;
    private final Governor governor;
    private final ContextGauge context;
    private final ClaudeCodeProbe probe;
    private final String cap;
    /** Told the session's id once its init line gave it (the launch record is written again). */
    private final Consumer<String> started;

    SessionStream(
            SessionSpec spec,
            SessionTally tally,
            Governor governor,
            ContextGauge context,
            ClaudeCodeProbe probe,
            String cap,
            Consumer<String> started) {
        this.spec = spec;
        this.tally = tally;
        this.governor = governor;
        this.context = context;
        this.probe = probe;
        this.cap = cap;
        this.started = started;
        this.model = spec.model();
    }

    void on(RuntimeEvent event) {
        switch (event) {
            case SessionStarted s -> {
                sessionId = s.sessionId();
                model = s.model();
                started.accept(sessionId);
                // The cap goes into the journal with the start: a restart bounds the session by it, not by today's.
                tally.observed(s.withCap(capUsd()));
                if (issued != null) tally.observed(issued);
            }
            // Counted and kept, in case Claude Code refuses the call itself and no hook ever runs.
            case ToolRequested call -> {
                toolCalls++;
                tally.announced(call);
            }
            case ToolCompleted result -> tally.completed(result);
            case AgentText text -> {
                finalText = text.text();
                tally.observed(text);
            }
            case SessionEnded e -> ended(e);
            case RuntimeEvent.PlanUsageReported usage -> tally.observed(usage);
            case RuntimeEvent.TokenIssued ignored -> {}
            case RuntimeEvent.ToolProgress progress -> governor.observed(progress);
            case RuntimeEvent.ContextUsage usage -> context(usage);
            case RuntimeEvent.Unfamiliar u -> unfamiliar(u);
        }
    }

    private void ended(SessionEnded e) {
        end = e.withCap(capUsd()).fromLaunch(launch);
        if (e.capReached()) {
            String cost =
                    e.estimatedCostUsd() == null ? "unknown" : String.format(Locale.ROOT, "%.4f", e.estimatedCostUsd());
            notes.add("Claude Code stopped the session at its cost cap of " + cap + " USD (estimated cost " + cost
                    + " USD; the cap is checked after each turn)");
        }
        // The end with its cap and its folder: a result before any init still counts the launch for its cap.
        tally.observed(end);
    }

    /** Pushed live, never journaled; the record id is the one MissionGovernor journals. */
    private void context(RuntimeEvent.ContextUsage usage) {
        if (sessionId == null) return;
        context.observe(
                spec.mission().value() + ":" + sessionId,
                spec.agent().value(),
                spec.mission().value(),
                model,
                usage);
    }

    private void unfamiliar(RuntimeEvent.Unfamiliar u) {
        if (probe.accepted(u.kind())) {
            governor.observed(u);
        } else if (probe.unfamiliar(u.kind(), u.shape(), model)) {
            notes.add("Claude Code sent a stream line Floor42 does not know: " + u.kind());
        }
    }

    private Double capUsd() {
        return Double.valueOf(cap);
    }

    /** The cap the session runs under, in USD, as Claude Code reads it. */
    String cap() {
        return cap;
    }

    /** Its process was started: from here on, an end without a reported cost counts for the cap. */
    void launched() {
        launched = true;
    }

    /**
     * The session failed before or without a result: recorded as ended in error, with the reason. Once a process was
     * launched it may have spent up to its cap, so the cap goes with the end; before, nothing ran and nothing counts.
     */
    void failed(String reason) {
        SessionEnded failed = new SessionEnded(true, null, null, null, null, null);
        end = (launched ? failed.withCap(capUsd()) : failed).fromLaunch(launch);
        notes.add(reason);
        tally.observed(end);
    }

    SessionReport report() {
        return new SessionReport(
                Origin.REAL,
                sessionId,
                model,
                toolCalls,
                tally.allowed.get(),
                tally.denied.get(),
                tally.pending.get(),
                notes,
                finalText,
                end.inputTokens(),
                end.outputTokens(),
                end.estimatedCostUsd(),
                end.error());
    }
}
