package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.NotebookEvent;
import dev.floor42.domain.event.NotebookEvent.NoteAdded;
import dev.floor42.domain.event.NotebookEvent.NoteChecked;
import dev.floor42.domain.event.NotebookEvent.NoteCommitted;
import dev.floor42.domain.event.NotebookEvent.NoteRemoved;
import dev.floor42.domain.event.NotebookEvent.NoteTurnedIntoTicket;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The director's improvement checklist. */
final class Notebook {

    private final CompanyView view;
    private final Map<String, Note> notes = new LinkedHashMap<>();
    /** Note ids are never reused, even after a removal. */
    private int notesEver;

    Notebook(CompanyView view) {
        this.view = view;
    }

    List<Note> notes() {
        return List.copyOf(notes.values());
    }

    List<EventEnvelope> addNote(CommandContext ctx, String text) {
        requireDirector(ctx);
        requireText(text, "text");
        require(text.strip().length() <= 2_000, Refusal.INVALID_ARGUMENT, "A note is at most 2000 characters");
        return view.record(ctx, null)
                .emit(new NoteAdded("N-" + (notesEver + 1), text.strip()))
                .events();
    }

    List<EventEnvelope> checkNote(CommandContext ctx, String id, boolean done) {
        requireDirector(ctx);
        Note n = existing(id);
        if (n.done == done) return List.of();
        return view.record(ctx, null).emit(new NoteChecked(id, done)).events();
    }

    List<EventEnvelope> removeNote(CommandContext ctx, String id) {
        requireDirector(ctx);
        require(notes.containsKey(id), Refusal.UNKNOWN_NOTE, "No note " + id);
        return view.record(ctx, null).emit(new NoteRemoved(id)).events();
    }

    List<EventEnvelope> turnNoteIntoTicket(CommandContext ctx, String id, MissionId mission, TicketCreation tickets) {
        requireDirector(ctx);
        Note n = existing(id);
        require(n.ticket == null, Refusal.NOTE_ALREADY_TICKET, id + " is already " + n.ticket);
        Mission m = view.missionFor(ctx, mission);
        require(m.status == MissionStatus.IN_PROGRESS, Refusal.MISSION_NOT_ACTIVE, mission + " is " + m.status);
        String text = n.text().strip();
        List<EventEnvelope> events = new ArrayList<>(tickets.create(
                ctx,
                mission,
                titleOf(text),
                text + "\n\nFrom the director's note " + id + ".",
                Priority.NORMAL,
                List.of(),
                List.of("Done as the note asks: " + text)));
        TicketId ticket = ((TicketCreated) events.getFirst().event()).ticket();
        events.addAll(view.record(ctx, mission)
                .emit(new NoteTurnedIntoTicket(id, ticket))
                .events());
        return events;
    }

    private static String titleOf(String text) {
        String title = text.lines().findFirst().orElse(text);
        return title.length() > 80 ? title.substring(0, 79).strip() + "…" : title;
    }

    List<EventEnvelope> closeNote(CommandContext ctx, String id, String commit) {
        Note n = existing(id);
        if (ctx.actor().kind() == Actor.Kind.SYSTEM) {
            require(
                    n.ticket != null && view.existingTicket(n.ticket).status() == TicketStatus.DONE,
                    Refusal.NOTE_NOT_IMPLEMENTED,
                    id + " has no done ticket: only the director closes it");
        } else {
            requireDirector(ctx);
        }
        String c = commit == null || commit.isBlank() ? null : commit.strip();
        require(c == null || c.matches("[0-9a-fA-F]{7,40}"), Refusal.INVALID_COMMIT, "A commit is 7 to 40 hex digits");
        Recorder r = view.record(ctx, null);
        if (!n.done) r.emit(new NoteChecked(id, true));
        if (c != null && !c.equals(n.commit)) r.emit(new NoteCommitted(id, c));
        return r.events();
    }

    private Note existing(String id) {
        Note n = notes.get(id);
        require(n != null, Refusal.UNKNOWN_NOTE, "No note " + id);
        return n;
    }

    /** The only way this area's state changes. */
    void apply(NotebookEvent event, EventEnvelope envelope) {
        Instant at = envelope.occurredAt();
        switch (event) {
            case NoteAdded e -> on(e, at);
            case NoteChecked e -> on(e, at);
            case NoteRemoved e -> on(e);
            case NoteTurnedIntoTicket e -> on(e);
            case NoteCommitted e -> on(e);
        }
    }

    void on(NoteAdded e, Instant at) {
        notes.put(e.note(), new Note(e.note(), e.text(), at));
        notesEver++;
    }

    void on(NoteChecked e, Instant at) {
        Note n = notes.get(e.note());
        n.done = e.done();
        n.doneAt = e.done() ? at : null;
    }

    void on(NoteRemoved e) {
        notes.remove(e.note());
    }

    void on(NoteTurnedIntoTicket e) {
        notes.get(e.note()).ticket = e.ticket();
    }

    void on(NoteCommitted e) {
        notes.get(e.note()).commit = e.commit();
    }
}
