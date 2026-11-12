package dev.floor42.server.api;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Origin;
import dev.floor42.server.api.RetroViews.LessonView;
import dev.floor42.server.app.OfficeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Lessons drawn by retrospectives (ADR-0020). The chair proposes; the director accepts (possibly reworded), rejects,
 * or later retires a lesson. Only accepted lessons reach the briefs of later missions.
 */
@Path("/api/lessons")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LessonsResource {

    public record Wording(String text) {}

    public record Reason(String reason) {}

    @Inject
    OfficeService office;

    /** Every lesson, oldest first, whatever its status. */
    @GET
    public List<LessonView> list() {
        return office.query(
                c -> c.retros().lessons().stream().map(LessonView::of).toList());
    }

    @POST
    @Path("/{id}/accept")
    public List<LessonView> accept(@PathParam("id") String id, Wording request) {
        office.execute(c -> c.retros().acceptLesson(director(), id, request == null ? null : request.text()));
        return list();
    }

    @POST
    @Path("/{id}/reject")
    public List<LessonView> reject(@PathParam("id") String id, Reason request) {
        office.execute(c -> c.retros().rejectLesson(director(), id, request == null ? null : request.reason()));
        return list();
    }

    @POST
    @Path("/{id}/retire")
    public List<LessonView> retire(@PathParam("id") String id, Reason request) {
        office.execute(c -> c.retros().retireLesson(director(), id, request == null ? null : request.reason()));
        return list();
    }

    /** Lessons come from real missions only: the director's decisions on them are real. */
    private dev.floor42.domain.CommandContext director() {
        return office.context(Actor.director(), Origin.REAL);
    }
}
