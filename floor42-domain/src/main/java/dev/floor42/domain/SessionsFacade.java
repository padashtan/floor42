package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.SessionEvent.AgentSessionEnded;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The agents' sessions: opened on a ticket, what the agent says in them, how they end, and the continuation of an
 * ended one. Reached by {@link Company#sessions()}; the rules live in the session log.
 */
public final class SessionsFacade {

    /** The longest remark kept: a speech bubble, not a report. */
    public static final int REMARK_MAX = 280;

    /** The longest line of an accepted kind kept in the journal. */
    public static final int RUNTIME_LINE_MAX = 2_000;

    private final SessionLog sessionLog;

    SessionsFacade(SessionLog sessionLog) {
        this.sessionLog = sessionLog;
    }

    public List<AgentSession> of(MissionId mission) {
        return sessionLog.sessions(mission);
    }

    public Optional<AgentSession> find(String id) {
        return sessionLog.session(id);
    }

    /** The model a recorded session ran, or null. */
    public String modelOf(String session) {
        return sessionLog.modelOfSession(session);
    }

    /**
     * The session the director asked the next session of this agent on this ticket to continue, if that request still
     * holds: no session of the agent has started on the ticket since.
     */
    public Optional<String> continuationRequested(MissionId mission, TicketId ticket, AgentId agent) {
        return sessionLog.continuationRequested(mission, ticket, agent);
    }

    /** A session is opened for a ticket the agent is producing or reviewing, while the mission is in progress. */
    public List<EventEnvelope> start(
            CommandContext ctx, String sessionId, TicketId ticketId, AgentId agentId, String model, String version) {
        return start(ctx, sessionId, ticketId, agentId, model, version, null);
    }

    /** As above, with the cap the session runs under, kept so that a restart bounds its cost by it. */
    public List<EventEnvelope> start(
            CommandContext ctx,
            String sessionId,
            TicketId ticketId,
            AgentId agentId,
            String model,
            String version,
            Double capUsd) {
        return sessionLog.start(ctx, sessionId, ticketId, agentId, model, version, capUsd);
    }

    /**
     * A launched process that never named its session (it died first, or the daemon did): recorded as a failed session
     * of its own, bounded by its cap, so that the budget counts what it may have spent. A missing record is no proof
     * that nothing was spent.
     */
    public List<EventEnvelope> recordUnnamedLaunch(
            CommandContext ctx, String sessionId, TicketId ticketId, AgentId agentId, String model, double capUsd) {
        return sessionLog.unnamedLaunch(ctx, sessionId, ticketId, agentId, model, capUsd);
    }

    /** Records what an agent wrote in an open session, between two tool calls; blank text records nothing. */
    public List<EventEnvelope> recordRemark(CommandContext ctx, String sessionId, String text) {
        return sessionLog.remark(ctx, sessionId, text);
    }

    /**
     * Records a line of a kind the director accepted, from an open session. The runtime masks what looks like a
     * secret before it gets here.
     */
    public List<EventEnvelope> recordRuntimeLine(CommandContext ctx, String sessionId, String kind, String content) {
        return sessionLog.runtimeLine(ctx, sessionId, kind, content);
    }

    public List<EventEnvelope> end(
            CommandContext ctx,
            String sessionId,
            boolean failed,
            String outcome,
            Integer turns,
            Long inputTokens,
            Long outputTokens,
            Double costUsdEstimated) {
        return end(ctx, sessionId, failed, outcome, turns, inputTokens, outputTokens, costUsdEstimated, null);
    }

    /**
     * As above; {@code costUsdBound} is the cap the session ran under, given when it ended without reporting its cost,
     * so that the mission's budget still counts it (an upper bound, never presented as measured).
     */
    public List<EventEnvelope> end(
            CommandContext ctx,
            String sessionId,
            boolean failed,
            String outcome,
            Integer turns,
            Long inputTokens,
            Long outputTokens,
            Double costUsdEstimated,
            Double costUsdBound) {
        return sessionLog.end(
                ctx,
                new AgentSessionEnded(
                        sessionId, failed, outcome, turns, inputTokens, outputTokens, costUsdEstimated, costUsdBound));
    }

    /**
     * The director asks that the next session of the agent on this ticket continue an ended session (Claude Code's
     * {@code --resume}). Only the latest session of a developer on a ticket it still carries, on a real mission in
     * progress. Whether it is safe on this machine (same worktree, same tools, the transcript still there) is checked by
     * the runtime, when asked and again when the session starts.
     */
    public List<EventEnvelope> requestContinuation(CommandContext ctx, String sessionId) {
        return sessionLog.requestContinuation(ctx, sessionId);
    }

    /** Floor42's runtime records the identity token it obtained for an open session (never the token itself). */
    public List<EventEnvelope> tokenIssued(
            CommandContext ctx, String sessionId, String issuer, Instant expiresAt, String tokenId) {
        return sessionLog.tokenIssued(ctx, sessionId, issuer, expiresAt, tokenId);
    }

    /** Floor42's runtime records that an open session's risk went up (never down); codes and ids, no content. */
    public List<EventEnvelope> riskRaised(
            CommandContext ctx,
            String sessionId,
            RiskLevel level,
            List<RiskReason> reasons,
            Instant windowStart,
            List<ActionId> actions) {
        return sessionLog.riskRaised(ctx, sessionId, level, reasons, windowStart, actions);
    }

    /** The director's quarantine of a session at risk: one record of which steps did something (ADR-0066). */
    public List<EventEnvelope> quarantined(
            CommandContext ctx, String sessionId, boolean revoked, boolean suspended, boolean stopped) {
        return sessionLog.quarantined(ctx, sessionId, revoked, suspended, stopped);
    }

    /** Floor42's runtime records that a session it started continued an earlier one of the same agent and ticket. */
    public List<EventEnvelope> recordContinuation(CommandContext ctx, String sessionId, String from) {
        return sessionLog.recordContinuation(ctx, sessionId, from);
    }
}
