package dev.floor42.server.iam;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;

/** What a session token states about its launch: the launch id and the agent, mission and ticket it serves. */
public record SessionClaims(String launch, AgentId agent, MissionId mission, TicketId ticket) {}
