package dev.floor42.domain.event;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MeetingPlace;
import dev.floor42.domain.TicketId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The director's meeting since 1.9.0 (ADR-0049): where it sits and its turns of speech, in events of their own, so a
 * 1.8.0 daemon still reads every {@code MeetingCalled}. The journal stores their simple names, as for every event.
 */
public sealed interface MeetingRoomEvent extends MeetingEvent {

    /** The meeting sits at another table than the meeting table, recorded with its call. */
    record MeetingPlaced(String meeting, MeetingPlace place) implements MeetingRoomEvent {}

    /**
     * The director asks the speakers for their view, one after the other in this order, within a cost cap for the whole
     * meeting. The first ask of a meeting also opens its own ticket ({@code ticket}, of kind MEETING, named after the
     * meeting, outside the T-n sequence): the turns' sessions are recorded on it, never on an attendee's work.
     *
     * @param question nullable: the agenda says what is asked
     */
    record MeetingViewsAsked(
            String meeting, TicketId ticket, int round, List<AgentId> speakers, double capUsd, String question)
            implements MeetingRoomEvent {
        public MeetingViewsAsked {
            speakers = speakers == null ? null : Collections.unmodifiableList(new ArrayList<>(speakers));
        }
    }

    /**
     * One speaker's turn of a round: what it answered, or why it was not heard (exactly one of the two).
     *
     * @param session nullable: the turn's session record, when one ran
     * @param budgetUsd nullable: the most the turn's session was allowed ({@code --max-budget-usd}); what the meeting is
     *     charged for it when Claude Code gave no estimate (a session that failed or was stopped)
     */
    record MeetingTurnTaken(
            String meeting, int round, AgentId attendee, String session, String answer, String why, Double budgetUsd)
            implements MeetingRoomEvent {}
}
