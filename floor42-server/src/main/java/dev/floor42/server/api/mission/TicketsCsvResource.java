package dev.floor42.server.api.mission;

import static dev.floor42.server.api.DirectorRequests.parse;

import dev.floor42.domain.MissionId;
import dev.floor42.server.api.FileResponses;
import dev.floor42.server.app.core.OfficeService;
import dev.floor42.server.app.core.RefusedException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;

/** The mission's tickets as a Jira import file: a snapshot, read only, journals nothing, nothing is sent to Jira. */
@Path("/api")
public class TicketsCsvResource {

    private final OfficeService office;

    public TicketsCsvResource(OfficeService office) {
        this.office = office;
    }

    @GET
    @Path("/missions/{id}/tickets.csv")
    @Produces("text/csv; charset=utf-8")
    public Response csv(@PathParam("id") String id) {
        MissionId m = parse(() -> new MissionId(id));
        String body = office.query(c -> {
            if (c.missions().find(m).isEmpty()) throw new RefusedException(404, "UNKNOWN_MISSION", "No mission " + id);
            return TicketsCsv.of(TicketViews.TicketView.all(c, m), id);
        });
        return Response.ok(body.getBytes(StandardCharsets.UTF_8))
                .header(
                        "Content-Disposition",
                        FileResponses.disposition("attachment", "floor42-" + id + "-tickets.csv"))
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .build();
    }
}
