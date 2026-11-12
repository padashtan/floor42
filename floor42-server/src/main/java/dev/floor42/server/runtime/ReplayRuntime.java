package dev.floor42.server.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.Origin;
import dev.floor42.server.iam.SessionIdentity;
import dev.floor42.server.runtime.RuntimeEvent.AgentText;
import dev.floor42.server.runtime.RuntimeEvent.SessionEnded;
import dev.floor42.server.runtime.RuntimeEvent.SessionStarted;
import dev.floor42.server.runtime.RuntimeEvent.ToolCompleted;
import dev.floor42.server.runtime.RuntimeEvent.ToolRequested;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays a recorded Claude Code transcript as a simulated session. Floor42's governance applies for real: each recorded
 * tool call is submitted to the current policy, and a recorded result is only reported for calls the policy lets run.
 *
 * <p>With a patience, a call awaiting the director blocks the replay until it is settled, exactly like a live session
 * blocked on its hook; without one, it stays pending. When the current decision allows a call, its recorded result is
 * applied only if the recording really ran it; a call the recording was refused has no result to replay, and is
 * reported as such instead of showing the old refusal or an invented success.
 */
public final class ReplayRuntime implements AgentRuntime {

    private final List<String> transcript;
    private final Duration pace;
    private final Duration patience;
    private final StreamJsonParser parser;

    public ReplayRuntime(List<String> transcriptLines, ObjectMapper json) {
        this(transcriptLines, Duration.ZERO, Duration.ZERO, json);
    }

    /**
     * @param pace wait before each tool call and each result, so a watcher can follow the session
     * @param patience how long a call awaiting the director blocks the session, like a live hook; zero never waits
     */
    public ReplayRuntime(List<String> transcriptLines, Duration pace, Duration patience, ObjectMapper json) {
        this.parser = new StreamJsonParser(json);
        this.transcript = List.copyOf(transcriptLines);
        this.pace = pace;
        this.patience = patience;
    }

    private SessionIdentity identity;

    /** In IAM mode the simulated session gets a token of the simulated issuer, journaled with its start. */
    public ReplayRuntime identifying(SessionIdentity simulated) {
        this.identity = simulated;
        return this;
    }

    @Override
    public SessionReport run(SessionSpec spec, Governor governor) {
        if (spec.origin() != Origin.SIMULATED) {
            throw new IllegalArgumentException("A replayed session is simulated by definition");
        }
        Replay r = new Replay(governor, spec);
        for (String line : transcript) {
            for (RuntimeEvent event : parser.parse(line)) r.on(event);
        }
        return r.report();
    }

    /** What one replay of the recording decided and kept. */
    private final class Replay {
        final Governor governor;
        final Map<String, Verdict> verdicts = new HashMap<>();
        final List<String> notes = new ArrayList<>();
        String sessionId;
        String model;
        String finalText;
        SessionEnded end;

        final SessionSpec spec;

        Replay(Governor governor, SessionSpec spec) {
            this.governor = governor;
            this.spec = spec;
        }

        void on(RuntimeEvent event) {
            switch (event) {
                case SessionStarted s -> {
                    sessionId = s.sessionId();
                    model = s.model();
                    started(s);
                }
                case ToolRequested call -> {
                    pause();
                    Verdict v = governor.decide(call);
                    if (v == Verdict.PENDING && !patience.isZero()) v = governor.awaitDecision(call, patience);
                    verdicts.put(call.toolUseId(), v);
                }
                case ToolCompleted result -> completed(result);
                case AgentText text -> {
                    finalText = text.text();
                    governor.observed(text);
                }
                // The governor ignores a recording's usage: another day's plan.
                case RuntimeEvent.PlanUsageReported usage -> governor.observed(usage);
                case RuntimeEvent.ToolProgress progress -> governor.observed(progress);
                // A recording's context is another day's session: never shown as a live reading.
                case RuntimeEvent.ContextUsage _, RuntimeEvent.TokenIssued _ -> {}
                case RuntimeEvent.Unfamiliar u ->
                    notes.add("the recording holds a line Floor42 does not know: " + u.kind());
                case SessionEnded e -> {
                    end = e;
                    governor.observed(e);
                }
            }
        }

        /** In IAM mode the simulated session carries a token of the simulated issuer, never the real one. */
        private void started(SessionStarted s) {
            governor.observed(s);
            if (identity != null) identity.mintSimulated(spec).ifPresent(m -> governor.observed(m.event()));
        }

        private void completed(ToolCompleted result) {
            pause();
            Verdict v = verdicts.get(result.toolUseId());
            if (v == Verdict.PENDING) {
                notes.add(result.toolUseId() + ": awaiting the director, recorded result not applied");
            } else if (v == Verdict.ALLOW && result.refusedByGovernance()) {
                String reason = "the recorded session was refused this call, so there is no result to replay";
                notes.add(result.toolUseId() + ": " + reason);
                governor.unreplayable(result.toolUseId(), reason);
            } else if (v != null) {
                governor.completed(result);
            }
        }

        SessionReport report() {
            return new SessionReport(
                    Origin.SIMULATED,
                    sessionId,
                    model,
                    verdicts.size(),
                    count(verdicts, Verdict.ALLOW),
                    count(verdicts, Verdict.DENY),
                    count(verdicts, Verdict.PENDING),
                    notes,
                    finalText,
                    end == null ? null : end.inputTokens(),
                    end == null ? null : end.outputTokens(),
                    end == null ? null : end.estimatedCostUsd(),
                    end == null || end.error());
        }
    }

    private void pause() {
        if (pace.isZero()) return;
        try {
            Thread.sleep(pace);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Replay interrupted", e);
        }
    }

    private static int count(Map<String, Verdict> verdicts, Verdict v) {
        return (int) verdicts.values().stream().filter(v::equals).count();
    }
}
