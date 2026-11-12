package dev.floor42.domain.event;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import java.util.ArrayList;
import java.util.Collections;

/** Agent sessions as the runtime recorded them, and their continuations. */
public sealed interface SessionEvent extends DomainEvent {

    /** {@code runtimeVersion} nullable; {@code capUsd}: the cap it runs under, null in a replay and before 1.9.0. */
    record AgentSessionStarted(
            String session,
            MissionId mission,
            TicketId ticket,
            AgentId agent,
            String model,
            String runtimeVersion,
            Double capUsd)
            implements SessionEvent {}

    /**
     * What the agent wrote between two tool calls, in its own words, cut at {@code SessionsFacade.REMARK_MAX} characters. The
     * scene shows it in a speech bubble while the agent thinks (ADR-0026).
     */
    record AgentRemarked(String session, AgentId agent, TicketId ticket, String text) implements SessionEvent {}

    /**
     * A line of a kind the director accepted, recorded as Claude Code sent it, values that look like secrets masked
     * and the whole cut to {@link dev.floor42.domain.SessionsFacade#RUNTIME_LINE_MAX} characters.
     */
    record RuntimeLineRecorded(String session, AgentId agent, TicketId ticket, String kind, String content)
            implements SessionEvent {}

    /** Tokens and estimated cost as the runtime reported them (null when not); {@code costUsdBound}: AgentSession. */
    record AgentSessionEnded(
            String session,
            boolean failed,
            String outcome,
            Integer turns,
            Long inputTokensProvided,
            Long outputTokensProvided,
            Double costUsdEstimated,
            Double costUsdBound)
            implements SessionEvent {}

    /**
     * The director asks that the next session of this agent on this ticket continue the given ended session with
     * Claude Code's {@code --resume} instead of starting fresh. Floor42 checks again, when the session starts, that it
     * is still safe; otherwise the session starts fresh, and the journal shows which happened.
     */
    record SessionContinuationRequested(MissionId mission, TicketId ticket, AgentId agent, String session)
            implements SessionEvent {}

    /**
     * The runtime obtained the session's identity token (IAM mode, ADR to come): who the token names, never the token.
     * {@code expiresAt} is an ISO-8601 instant, {@code tokenId} the token's {@code jti}.
     */
    record AgentTokenIssued(
            String session,
            AgentId agent,
            MissionId mission,
            TicketId ticket,
            String issuer,
            String expiresAt,
            String tokenId)
            implements SessionEvent {}

    /**
     * The session's risk went up (ADR-0066): codes and ids only, never what the agent wrote or read. {@code windowStart}
     * is an ISO-8601 instant; {@code actions} the latest action ids counted, at most 10.
     */
    record SessionRiskRaised(
            String session,
            AgentId agent,
            MissionId mission,
            TicketId ticket,
            String level,
            java.util.List<String> reasons,
            String windowStart,
            java.util.List<String> actions)
            implements SessionEvent {
        public SessionRiskRaised {
            actions = actions == null ? null : Collections.unmodifiableList(new ArrayList<>(actions));
            reasons = reasons == null ? null : Collections.unmodifiableList(new ArrayList<>(reasons));
        }
    }

    /**
     * The director quarantined the session (ADR-0066): which steps did something, whatever the mission's state. Codes
     * and ids only, never tool input or a secret.
     */
    record MissionQuarantined(
            String session,
            AgentId agent,
            MissionId mission,
            TicketId ticket,
            String level,
            java.util.List<String> reasons,
            boolean revoked,
            boolean suspended,
            boolean stopped)
            implements SessionEvent {
        public MissionQuarantined {
            reasons = reasons == null ? null : Collections.unmodifiableList(new ArrayList<>(reasons));
        }
    }

    /** The session was started with Claude Code's {@code --resume} of {@code from}: it holds that session's history. */
    record SessionContinued(String session, String from) implements SessionEvent {}
}
