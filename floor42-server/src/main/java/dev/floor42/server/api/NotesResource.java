package dev.floor42.server.api;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Origin;
import dev.floor42.server.app.OfficeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** The director's improvement checklist: what Floor42 could do better, noted while using it. Company-wide, real. */
@Path("/api/notes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NotesResource {

    /**
     * @param ticket the ticket the director made of the note, null if none
     * @param commit the commit that implemented it, null while unknown
     */
    public record NoteView(
            String id, String text, String addedAt, boolean done, String doneAt, String ticket, String commit) {}

    public record Closing(String commit) {}

    public record ToTicket(String mission) {}

    public record NewNote(String text) {}

    public record NoteState(boolean done) {}

    @Inject
    OfficeService office;

    @GET
    public List<NoteView> list() {
        return office.query(c -> c.notebook().notes().stream()
                .map(n -> new NoteView(
                        n.id(),
                        n.text(),
                        n.addedAt().toString(),
                        n.done(),
                        n.doneAt() == null ? null : n.doneAt().toString(),
                        n.ticket() == null ? null : n.ticket().value(),
                        n.commit()))
                .toList());
    }

    @POST
    public List<NoteView> add(NewNote request) {
        office.execute(c -> c.notebook().add(director(), request == null ? null : request.text()));
        return list();
    }

    @PUT
    @Path("/{id}")
    public List<NoteView> check(@PathParam("id") String id, NoteState request) {
        office.execute(c -> c.notebook().check(director(), id, request != null && request.done()));
        return list();
    }

    /** Makes the note a ticket of a mission in progress, written by the director: it goes to work without approval. */
    @POST
    @Path("/{id}/ticket")
    public List<NoteView> toTicket(@PathParam("id") String id, ToTicket request) {
        if (request == null || request.mission() == null) throw new BadRequestException("mission is required");
        office.execute(c -> c.notebook()
                .turnIntoTicket(
                        office.context(Actor.director(), Origin.REAL),
                        id,
                        new dev.floor42.domain.MissionId(request.mission())));
        return list();
    }

    /** Closes the note into the archive, with the commit that implemented it (optional, 7 to 40 hex digits). */
    @POST
    @Path("/{id}/close")
    public List<NoteView> close(@PathParam("id") String id, Closing request) {
        office.execute(c -> c.notebook().close(director(), id, request == null ? null : request.commit()));
        return list();
    }

    @DELETE
    @Path("/{id}")
    public List<NoteView> remove(@PathParam("id") String id) {
        office.execute(c -> c.notebook().remove(director(), id));
        return list();
    }

    /** The director's own notes: real, whatever the missions on the board. */
    private dev.floor42.domain.CommandContext director() {
        return office.context(Actor.director(), Origin.REAL);
    }
}
