package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.Company;
import dev.floor42.domain.event.MeetingEvent;
import dev.floor42.domain.event.MeetingEvent.MeetingCalled;
import dev.floor42.domain.event.MeetingEvent.MeetingClosed;
import dev.floor42.domain.event.MeetingEvent.MeetingNoteGiven;
import dev.floor42.domain.event.MeetingRoomEvent;
import dev.floor42.domain.event.MeetingRoomEvent.MeetingPlaced;
import dev.floor42.domain.event.MeetingRoomEvent.MeetingTurnTaken;
import dev.floor42.domain.event.MeetingRoomEvent.MeetingViewsAsked;

/** The director's meetings at the table (ADR-0047): called, what was said, closed. */
final class MeetingRenderer implements EventRenderer<MeetingEvent> {

    @Override
    public EventLine render(Company c, MeetingEvent event) {
        return switch (event) {
            case MeetingCalled e ->
                line(e.agenda() == null ? null : "agenda")
                        .param("meeting", e.meeting())
                        .param("mission", e.mission())
                        .param("attendees", e.attendees())
                        .param("agenda", e.agenda())
                        .build();
            case MeetingNoteGiven e ->
                line(e.attendee() == null ? "all" : "one")
                        .agent(e.attendee())
                        .param("meeting", e.meeting())
                        .param("agent", e.attendee())
                        .param("text", e.text())
                        .build();
            case MeetingClosed e ->
                line().param("meeting", e.meeting())
                        .param("mission", e.mission())
                        .param("attendees", e.attendees())
                        .build();
            case MeetingRoomEvent e -> room(e);
        };
    }

    /** Where it sits and its turns of speech (ADR-0049). */
    private static EventLine room(MeetingRoomEvent event) {
        return switch (event) {
            case MeetingPlaced e ->
                line().param("meeting", e.meeting()).param("place", e.place()).build();
            case MeetingViewsAsked e ->
                line().param("meeting", e.meeting())
                        .param("round", e.round())
                        .param("speakers", e.speakers())
                        .param("cap", e.capUsd())
                        .build();
            case MeetingTurnTaken e ->
                line(e.answer() != null ? "heard" : "notHeard")
                        .agent(e.attendee())
                        .param("meeting", e.meeting())
                        .param("agent", e.attendee())
                        .param("text", e.answer() != null ? e.answer() : e.why())
                        .build();
        };
    }
}
