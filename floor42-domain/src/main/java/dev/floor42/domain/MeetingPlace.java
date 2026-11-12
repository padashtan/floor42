package dev.floor42.domain;

/** The table the director called a meeting at (ADR-0049): the attendees sit around it. */
public enum MeetingPlace {
    /** The meeting table in the director's room: what every meeting before 1.9.0 used. */
    TABLE,
    /** The conference table in the south wing: twelve seats. */
    CONFERENCE
}
