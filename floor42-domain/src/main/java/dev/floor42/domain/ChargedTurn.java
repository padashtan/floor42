package dev.floor42.domain;

import java.time.Instant;

/**
 * A turn of speech at the director's meeting charged its budget for want of an estimate, whose session adds nothing
 * itself (ADR-0049): the part of the mission's upper bound no session carries. Views split it by period (its turn's
 * time), by speaker and by ticket (the meeting's own).
 *
 * @param usd the budget the turn was allowed: an upper bound, never an estimate nor a measure
 * @param at when the turn was recorded
 */
public record ChargedTurn(MissionId mission, TicketId ticket, AgentId attendee, double usd, Instant at) {}
