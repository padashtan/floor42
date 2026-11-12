package dev.floor42.domain;

import java.time.Instant;

/**
 * What the director said at a meeting.
 *
 * @param attendee null when said to every attendee
 */
public record MeetingNote(String meeting, AgentId attendee, String text, Instant at) {

    /** Said to this agent, or to everyone at the table. */
    public boolean concerns(AgentId agent) {
        return attendee == null || attendee.equals(agent);
    }
}
