package dev.floor42.domain;

/**
 * What a ticket is for, recorded when it is created ({@code TicketCreated.kind}, ADR-0009 amendment 2026-10-31): never
 * read again from its title, which the director may rewrite.
 */
public enum TicketKind {
    /** Work to deliver: the producer's, then a review. */
    WORK,
    /** The planner's ticket: its session proposes the mission's tickets (ADR-0017). */
    PLANNING,
    /**
     * A director's meeting's own ticket (ADR-0049): the attendees' turns of speech are its sessions. Never listed with
     * the mission's tickets, never worked by a ticket command.
     */
    MEETING;

    /** The title a planning ticket had before kinds were recorded: the rule for tickets journaled before 1.6.0 only. */
    public static final String LEGACY_PLAN_PREFIX = "Plan: ";

    /** The kind of a ticket journaled before kinds existed, by the rule then in force: its title. */
    public static TicketKind ofLegacyTitle(String title) {
        return title != null && title.startsWith(LEGACY_PLAN_PREFIX) ? PLANNING : WORK;
    }
}
