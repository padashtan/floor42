package dev.floor42.domain;

import java.time.Instant;

/**
 * The director asked that the next session of {@code agent} on {@code ticket} continue {@code session} (Claude Code's
 * {@code --resume}) rather than start fresh. It holds until a session of that agent starts on that ticket.
 */
public record ContinuationRequest(MissionId mission, TicketId ticket, AgentId agent, String session, Instant at) {}
