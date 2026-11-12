package dev.floor42.server.api;

import static dev.floor42.server.api.DirectorRequests.asDirector;
import static dev.floor42.server.api.DirectorRequests.missionView;
import static dev.floor42.server.api.DirectorRequests.parse;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.Objection;
import dev.floor42.domain.TicketId;
import dev.floor42.server.api.MissionViews.MissionView;
import dev.floor42.server.app.DeliverablesHome;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.RefusedException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The director's objections at acceptance (ADR-0060). Needs a token of scope {@code all}, like the acceptance itself
 * (ADR-0044). Runs under the mission's close lock: refused while the mission closes or its proof runs.
 */
@Path("/api/missions/{id}/objections")
@Produces(MediaType.APPLICATION_JSON)
public class ObjectionsResource {

    /**
     * @param ticket the done work ticket the objection is about; null or blank for new work
     * @param text what the director objects to, or asks for (1 to 4 000 characters)
     */
    public record ObjectionRequest(String ticket, String text) {}

    /** @param objections 1 to 10 */
    public record ObjectionsRequest(@NoNullItems List<ObjectionRequest> objections) {
        public ObjectionsRequest {
            objections = objections == null ? null : Collections.unmodifiableList(new ArrayList<>(objections));
        }
    }

    private final OfficeService office;
    private final DeliverablesHome home;

    public ObjectionsResource(OfficeService office, DeliverablesHome home) {
        this.office = office;
        this.home = home;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public MissionView object(@PathParam("id") String id, ObjectionsRequest request) {
        MissionId m = parse(() -> new MissionId(id));
        if (request == null || request.objections() == null)
            throw RefusedException.invalid("INVALID_ARGUMENT", "objections are expected");
        List<Objection> items = request.objections().stream()
                .map(o -> new Objection(
                        o == null || o.ticket() == null || o.ticket().isBlank()
                                ? null
                                : parse(() -> new TicketId(o.ticket().strip())),
                        o == null ? null : o.text()))
                .toList();
        home.closing(m, () -> office.execute(c -> c.missions().object(asDirector(office, m), m, items)));
        return missionView(office, m);
    }
}
