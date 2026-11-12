package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.Company;
import dev.floor42.domain.event.NotebookEvent;
import dev.floor42.domain.event.NotebookEvent.NoteAdded;
import dev.floor42.domain.event.NotebookEvent.NoteChecked;
import dev.floor42.domain.event.NotebookEvent.NoteCommitted;
import dev.floor42.domain.event.NotebookEvent.NoteRemoved;
import dev.floor42.domain.event.NotebookEvent.NoteTurnedIntoTicket;

/** The lines of the director's improvement checklist. */
final class NotebookRenderer implements EventRenderer<NotebookEvent> {

    @Override
    public EventLine render(Company c, NotebookEvent event) {
        return switch (event) {
            case NoteAdded e -> note(null, e.note()).param("text", e.text()).build();
            case NoteChecked e ->
                note(e.done() ? "done" : "reopened", e.note())
                        .param("done", e.done())
                        .build();
            case NoteRemoved e -> note(null, e.note()).build();
            case NoteTurnedIntoTicket e ->
                note(null, e.note())
                        .ticket(e.ticket())
                        .param("ticket", e.ticket())
                        .build();
            case NoteCommitted e ->
                note(null, e.note()).param("commit", e.commit()).build();
        };
    }

    private static EventLine.Builder note(String variant, String note) {
        return line(variant).param("note", note);
    }
}
