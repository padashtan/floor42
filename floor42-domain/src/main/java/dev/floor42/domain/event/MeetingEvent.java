package dev.floor42.domain.event;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The director's meetings with agents at the table (ADR-0047), and their turns of speech (ADR-0049). */
public sealed interface MeetingEvent extends DomainEvent
        permits MeetingEvent.MeetingCalled,
                MeetingEvent.MeetingNoteGiven,
                MeetingEvent.MeetingClosed,
                MeetingRoomEvent {

    /**
     * The director calls agents to the table about a mission: they take no new step until the meeting closes.
     *
     * @param agenda nullable: what the director wants to talk about
     */
    record MeetingCalled(String meeting, MissionId mission, List<AgentId> attendees, String agenda)
            implements MeetingEvent {
        public MeetingCalled {
            attendees = attendees == null ? null : Collections.unmodifiableList(new ArrayList<>(attendees));
        }
    }

    /**
     * What the director said at the meeting, restated in the next brief of the attendees it concerns.
     *
     * @param attendee nullable: said to every attendee
     */
    record MeetingNoteGiven(String meeting, AgentId attendee, String text) implements MeetingEvent {}

    /** The director closed the meeting: the attendees go back to their work, with what was said. */
    record MeetingClosed(String meeting, MissionId mission, List<AgentId> attendees) implements MeetingEvent {
        public MeetingClosed {
            attendees = attendees == null ? null : Collections.unmodifiableList(new ArrayList<>(attendees));
        }
    }
}
