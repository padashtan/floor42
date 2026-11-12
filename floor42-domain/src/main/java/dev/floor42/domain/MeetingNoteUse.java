package dev.floor42.domain;

/** What a meeting note becomes besides being restated in the attendees' next brief. */
public enum MeetingNoteUse {
    /** A note only: the next brief of each attendee it concerns restates it. */
    NOTE_ONLY,
    /** Also a directive on the ticket each attendee it concerns has under way on the mission. */
    ALSO_DIRECTIVE
}
