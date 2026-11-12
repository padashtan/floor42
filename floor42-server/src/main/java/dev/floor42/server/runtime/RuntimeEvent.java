package dev.floor42.server.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;

/** What an agent runtime reports, independent of the runtime (live Claude Code session or recorded transcript). */
public sealed interface RuntimeEvent {

    /** @param capUsd the cap the live session runs under, journaled with its start; null as parsed and in a replay */
    record SessionStarted(String sessionId, String model, String runtimeVersion, Double capUsd)
            implements RuntimeEvent {

        public SessionStarted(String sessionId, String model, String runtimeVersion) {
            this(sessionId, model, runtimeVersion, null);
        }

        /** The same start, with the cap the session runs under. */
        public SessionStarted withCap(Double cap) {
            return new SessionStarted(sessionId, model, runtimeVersion, cap);
        }
    }

    record ToolRequested(String toolUseId, String tool, JsonNode input) implements RuntimeEvent {}

    /**
     * @param refusedByGovernance true when the call never ran because a permission hook or prompt refused it; its
     *     output is the refusal, not a tool result
     */
    record ToolCompleted(String toolUseId, boolean error, boolean refusedByGovernance, String output)
            implements RuntimeEvent {}

    record AgentText(String text) implements RuntimeEvent {}

    /** The session's identity token was obtained (IAM mode): who issued it, until when, its jti. Never the token. */
    record TokenIssued(String issuer, java.time.Instant expiresAt, String jti) implements RuntimeEvent {}

    /**
     * A line or block of the runtime's stream Floor42 does not know: the protocol changed. What it carries is not
     * recorded; the tool calls themselves stay governed by the hook, which does not depend on the stream.
     *
     * @param kind its type, as {@code type/subtype} or {@code type:block}
     */
    /**
     * @param shape the line's keys and the JSON type of each value, never the values: what changed, safely shown
     * @param content the line itself, values that look like secrets masked: recorded only for a kind the director
     *     accepted
     */
    record Unfamiliar(String kind, String shape, String content) implements RuntimeEvent {}

    /** Claude Code's heartbeat for a tool call still running: the call, the tool, the seconds since it started. */
    record ToolProgress(String toolUseId, String tool, long seconds) implements RuntimeEvent {}

    /**
     * The plan's usage windows as the runtime reports them (Claude Code's rate_limit_event). Measured by the runtime.
     *
     * @param status the runtime's verdict, known for the window the event is about; null for the others
     */
    record UsageWindow(String name, double utilization, long resetsAtEpochSecond, String status) {}

    record PlanUsageReported(java.util.List<UsageWindow> windows) implements RuntimeEvent {
        public PlanUsageReported {
            windows = windows == null ? null : Collections.unmodifiableList(new ArrayList<>(windows));
        }
    }

    /**
     * The context the session's last request carried, as the runtime reports it on an assistant line of the main
     * session (a subagent's lines are another context and never become one). Measured tokens: input plus cache writes
     * plus cache reads. Several lines of one message repeat the same usage: the last one per message counts, never a
     * sum.
     *
     * @param messageId the model's message id, shared by the lines of one response; null when the runtime gave none
     */
    record ContextUsage(String messageId, long usedTokens) implements RuntimeEvent {}

    /**
     * Token counts are provided by the runtime; the cost is a client-side estimate (ADR-0006) and must be displayed as
     * such. Values are null when the runtime did not report them.
     */
    record SessionEnded(
            boolean error,
            Integer turns,
            Long durationMs,
            Long inputTokens,
            Long outputTokens,
            Double estimatedCostUsd,
            String stop,
            Double capUsd,
            String launch)
            implements RuntimeEvent {

        /**
         * @param capUsd the cap the session ran under, given by the runtime when no cost was reported (the session was
         *     killed or its stream closed without a result): the mission's budget counts it, as an upper bound
         * @param launch the session folder's name, given by the live runtime: the stable id of a launch that never named
         *     its session, the same one a restart finds in that folder; null as parsed and in a replay
         */
        public SessionEnded(
                boolean error,
                Integer turns,
                Long durationMs,
                Long inputTokens,
                Long outputTokens,
                Double estimatedCostUsd,
                String stop) {
            this(error, turns, durationMs, inputTokens, outputTokens, estimatedCostUsd, stop, null, null);
        }

        /** The same end with the cap the session ran under, kept only when no cost was reported. */
        public SessionEnded withCap(Double cap) {
            if (estimatedCostUsd != null) return this;
            return new SessionEnded(error, turns, durationMs, inputTokens, outputTokens, null, stop, cap, launch);
        }

        /** The same end, from the session folder of this name. */
        public SessionEnded fromLaunch(String folder) {
            return new SessionEnded(
                    error, turns, durationMs, inputTokens, outputTokens, estimatedCostUsd, stop, capUsd, folder);
        }

        /** Claude Code's result when it stopped the session at its {@code --max-budget-usd} cap. */
        public static final String CAP_REACHED = "error_max_budget_usd";

        public SessionEnded(
                boolean error, Integer turns, Long durationMs, Long inputTokens, Long outputTokens, Double cost) {
            this(error, turns, durationMs, inputTokens, outputTokens, cost, null);
        }

        public boolean capReached() {
            return CAP_REACHED.equals(stop);
        }
    }
}
