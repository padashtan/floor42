package dev.floor42.domain;

/**
 * What an agent is doing, derived from facts (assignments, actions, questions) instead of being stored, so an agent
 * can never appear busy on a finished mission. Meeting and review states arrive with the committee and review flows.
 */
public enum AgentStatus {
    AVAILABLE,
    ASSIGNED,
    WORKING,
    /** Running an independent review of someone else's ticket. */
    REVIEWING,
    /** Sitting on an architecture committee that is deliberating. */
    IN_COMMITTEE,
    /** At the table for the retrospective of an ended mission (ADR-0020). */
    IN_RETRO,
    /** Called to the table by the director (ADR-0047): no new step until the meeting closes. */
    IN_MEETING,
    WAITING_FOR_AUTHORIZATION,
    EXECUTING,
    WAITING_FOR_DIRECTOR,
    PAUSED
}
