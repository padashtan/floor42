package dev.floor42.domain;

/**
 * What Floor42's runtime reports of one turn of speech (ADR-0049): the answer, or why there is none (exactly one).
 *
 * @param session the turn's session record; null when no session ran
 * @param budgetUsd the most that session was allowed; null when none ran
 */
public record SpokenTurn(int round, AgentId attendee, String session, String answer, String why, Double budgetUsd) {

    /** The speaker was heard. */
    public static SpokenTurn heard(int round, AgentId attendee, String session, String answer, double budgetUsd) {
        return new SpokenTurn(round, attendee, session, answer, null, budgetUsd);
    }

    /** The speaker was not heard, for this reason; {@code session} and {@code budgetUsd} when one ran. */
    public static SpokenTurn notHeard(int round, AgentId attendee, String session, String why, Double budgetUsd) {
        return new SpokenTurn(round, attendee, session, null, why, budgetUsd);
    }
}
