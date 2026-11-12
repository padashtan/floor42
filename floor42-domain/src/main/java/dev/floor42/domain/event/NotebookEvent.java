package dev.floor42.domain.event;

import dev.floor42.domain.TicketId;

/** The director's improvement checklist. */
public sealed interface NotebookEvent extends DomainEvent {

    record NoteAdded(String note, String text) implements NotebookEvent {}

    record NoteChecked(String note, boolean done) implements NotebookEvent {}

    record NoteRemoved(String note) implements NotebookEvent {}

    /** The director made a note into a ticket of a mission: the note stays, linked to the ticket. */
    record NoteTurnedIntoTicket(String note, TicketId ticket) implements NotebookEvent {}

    /** The commit that implemented a note: from the workshop, or given by the director for work done elsewhere. */
    record NoteCommitted(String note, String commit) implements NotebookEvent {}
}
