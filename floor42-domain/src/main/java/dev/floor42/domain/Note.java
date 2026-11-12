package dev.floor42.domain;

import java.time.Instant;

/** One line of the director's improvement checklist: what Floor42 itself could do better, noted while using it. */
public final class Note {

    private final String id;
    private final String text;
    private final Instant addedAt;
    boolean done;
    Instant doneAt;
    TicketId ticket;
    String commit;

    Note(String id, String text, Instant addedAt) {
        this.id = id;
        this.text = text;
        this.addedAt = addedAt;
    }

    public String id() {
        return id;
    }

    public String text() {
        return text;
    }

    public Instant addedAt() {
        return addedAt;
    }

    public boolean done() {
        return done;
    }

    /** When it was last ticked; null while open. */
    public Instant doneAt() {
        return doneAt;
    }

    /** The commit that implemented it, once known; null otherwise. */
    public String commit() {
        return commit;
    }

    /** The ticket the director made of it; null if none. */
    public TicketId ticket() {
        return ticket;
    }
}
