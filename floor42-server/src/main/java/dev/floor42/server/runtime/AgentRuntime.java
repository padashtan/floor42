package dev.floor42.server.runtime;

import dev.floor42.domain.ActionId;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketId;
import dev.floor42.server.runtime.RuntimeEvent.ToolCompleted;
import dev.floor42.server.runtime.RuntimeEvent.ToolRequested;
import java.time.Duration;
import java.util.List;

/**
 * Port to whatever runs an agent session (ADR-0006). Adapters: {@link ReplayRuntime} (recorded sessions) and {@link
 * ClaudeCodeRuntime} (live sessions). Governance is not the runtime's business: every tool call goes through the {@link
 * Governor}.
 */
public interface AgentRuntime {

    /**
     * @param brief what the agent is asked to do, built from the journal (never a chat history); null for replays
     * @param model the model the agent profile names; null for replays (the recording decides)
     * @param effort nullable
     * @param tools the tools the agent may even attempt; the policy still decides on each call
     * @param resume the Claude Code session id this session continues ({@code --resume}, ADR-0038); null for a fresh
     *     session, which is the default
     * @param capUsd the most this session may spend when below the mission's session cap (a meeting turn, ADR-0049);
     *     null for the session cap alone
     * @param readOnlyShell a reviewer's session (ADR-0046, amended 2026-11-05): its shell runs only in the sandbox, with
     *     the work mounted read-only; false for every other session
     */
    record SessionSpec(
            MissionId mission,
            TicketId ticket,
            AgentId agent,
            Origin origin,
            String workdir,
            String brief,
            String model,
            String effort,
            List<String> tools,
            String resume,
            Double capUsd,
            boolean readOnlyShell) {

        public SessionSpec {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }

        public SessionSpec(
                MissionId mission,
                TicketId ticket,
                AgentId agent,
                Origin origin,
                String workdir,
                String brief,
                String model,
                String effort,
                List<String> tools,
                String resume,
                Double capUsd) {
            this(mission, ticket, agent, origin, workdir, brief, model, effort, tools, resume, capUsd, false);
        }

        /** The same session, its shell read-only in the sandbox: a reviewer's. */
        public SessionSpec withReadOnlyShell() {
            return new SessionSpec(
                    mission, ticket, agent, origin, workdir, brief, model, effort, tools, resume, capUsd, true);
        }

        /** A session under the mission's session cap alone. */
        public SessionSpec(
                MissionId mission,
                TicketId ticket,
                AgentId agent,
                Origin origin,
                String workdir,
                String brief,
                String model,
                String effort,
                List<String> tools,
                String resume) {
            this(mission, ticket, agent, origin, workdir, brief, model, effort, tools, resume, null);
        }

        /** A fresh session: the brief alone, no history. */
        public SessionSpec(
                MissionId mission,
                TicketId ticket,
                AgentId agent,
                Origin origin,
                String workdir,
                String brief,
                String model,
                String effort,
                List<String> tools) {
            this(mission, ticket, agent, origin, workdir, brief, model, effort, tools, null);
        }

        /** The same session, continuing an earlier one of Claude Code with this brief. */
        public SessionSpec resuming(String claudeSession, String newBrief) {
            return new SessionSpec(
                    mission,
                    ticket,
                    agent,
                    origin,
                    workdir,
                    newBrief,
                    model,
                    effort,
                    tools,
                    claudeSession,
                    capUsd,
                    readOnlyShell);
        }

        /** The same session, allowed at most {@code usd} (never more than the session cap). */
        public SessionSpec capped(double usd) {
            return new SessionSpec(
                    mission, ticket, agent, origin, workdir, brief, model, effort, tools, resume, usd, readOnlyShell);
        }

        /** A replayed session: the recording decides model, tools and prompt. */
        public SessionSpec(MissionId mission, TicketId ticket, AgentId agent, Origin origin, String workdir) {
            this(mission, ticket, agent, origin, workdir, null, null, null, List.of(), null);
        }
    }

    enum Verdict {
        ALLOW,
        DENY,
        /** Waiting for the director: the tool must not run yet. */
        PENDING
    }

    /** Floor42's side of a session: decides on each tool call and records each outcome. */
    interface Governor {
        Verdict decide(ToolRequested call);

        /**
         * Waits up to {@code patience} for a pending call to be settled, as a live session does while its PreToolUse
         * hook is waiting for the director. Returns PENDING if nobody decided in time.
         */
        default Verdict awaitDecision(ToolRequested call, Duration patience) {
            return Verdict.PENDING;
        }

        /**
         * Why the call must be refused now although it was allowed: checked again right before an allow is answered,
         * because the file system may have changed while the director decided (a link swapped in). Null when none. No
         * default: a governor must say, so none can skip the check.
         */
        String violationNow(ToolRequested call);

        void completed(ToolCompleted result);

        /** A call as the stream announced it, before or without its hook (the runtime may refuse it itself). */
        default void announced(ToolRequested call) {}

        /** The call was allowed but the source cannot show it running (a replay of a refused call). */
        default void unreplayable(String toolUseId, String reason) {}

        /** Session lifecycle and agent messages, for the record. */
        default void observed(RuntimeEvent event) {}

        /**
         * The agent asks the director through Floor42's MCP tool (ADR-0027) and its session waits: the answer, or null
         * when none came within {@code patience} (the question is then withdrawn).
         *
         * @param toolUseId the call's id, as Claude Code gives it; null when unknown
         */
        default AskOutcome askDirector(
                String question, List<String> options, String recommendation, String toolUseId, Duration patience) {
            return new AskOutcome.NoAnswer();
        }

        /**
         * The input an allowed call runs with instead of the agent's, or null to run it as asked (ADR-0028: a shell
         * command rewritten to run in the sandbox).
         */
        default com.fasterxml.jackson.databind.JsonNode updatedInput(ToolRequested call) {
            return null;
        }

        /**
         * A call refused before it reached the policy because the caller's identity token failed (IAM mode): recorded
         * as refused upstream, with {@code detail} as the reason. Nothing to record by default.
         */
        default void refuse(ToolRequested call, String detail) {}

        /** True when the PreToolUse hook authorized this tool call (the MCP server's call comes after it). */
        default boolean authorized(String toolUseId) {
            return false;
        }

        /** The journal id of a tool call of this session, or null when the governor keeps none. */
        default ActionId actionId(String toolUseId) {
            return null;
        }

        /** Why Floor42 decided as it did on a call, for the agent; null when unknown. */
        default String explain(String toolUseId) {
            return null;
        }
    }

    SessionReport run(SessionSpec spec, Governor governor);
}
