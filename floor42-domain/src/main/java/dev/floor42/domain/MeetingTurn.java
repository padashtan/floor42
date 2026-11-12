package dev.floor42.domain;

import java.time.Instant;

/**
 * What one attendee said at a round of the director's meeting (ADR-0049): its answer, or why it was not heard. Exactly
 * one of {@code answer} and {@code why} is set.
 *
 * @param session the turn's session record; null when no session ran
 * @param budgetUsd the most its session was allowed; null when none ran
 */
public record MeetingTurn(AgentId attendee, String session, String answer, String why, Double budgetUsd, Instant at) {

    public boolean heard() {
        return answer != null;
    }
}
