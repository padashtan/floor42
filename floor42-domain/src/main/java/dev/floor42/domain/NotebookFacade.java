package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import java.util.List;

/**
 * The director's improvement checklist: notes added, ticked, made into tickets, closed into the archive with their
 * commit, or removed. Reached by {@link Company#notebook()}; the rules live in the notebook.
 */
public final class NotebookFacade {

    private final Notebook notebook;
    private final TicketsFacade tickets;

    NotebookFacade(Notebook notebook, TicketsFacade tickets) {
        this.notebook = notebook;
        this.tickets = tickets;
    }

    /** The director's improvement checklist, oldest first. */
    public List<Note> notes() {
        return notebook.notes();
    }

    public List<EventEnvelope> add(CommandContext ctx, String text) {
        return notebook.addNote(ctx, text);
    }

    public List<EventEnvelope> check(CommandContext ctx, String id, boolean done) {
        return notebook.checkNote(ctx, id, done);
    }

    /**
     * The director makes a note into a ticket of a mission in progress: a ticket the director wrote, so it needs no
     * approval. Its text is the note's; the note stays on the checklist, linked to the ticket, until the director ticks
     * it.
     */
    public List<EventEnvelope> turnIntoTicket(CommandContext ctx, String id, MissionId mission) {
        return notebook.turnNoteIntoTicket(ctx, id, mission, tickets::create);
    }

    /**
     * Closes a note into the archive, with the commit that implemented it when there is one. The director closes any
     * note (naming the commit of work done outside Floor42); Floor42 closes a note on its own only once the ticket made
     * of it is done, with the commit the director accepted in the workshop for that ticket's work, if any. A commit
     * known later is added to a note already closed.
     */
    public List<EventEnvelope> close(CommandContext ctx, String id, String commit) {
        return notebook.closeNote(ctx, id, commit);
    }

    public List<EventEnvelope> remove(CommandContext ctx, String id) {
        return notebook.removeNote(ctx, id);
    }
}
