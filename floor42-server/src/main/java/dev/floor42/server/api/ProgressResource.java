package dev.floor42.server.api;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.server.app.AwaySummary;
import dev.floor42.server.app.LiveMissions;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.Progress;
import dev.floor42.server.app.Progress.MissionProgress;
import dev.floor42.server.app.RefusedException;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

/**
 * The progress panel: what runs, what waits for the director, what is stuck, for every mission not ended; and the
 * summary of what happened while the director was away.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class ProgressResource {

    @Inject
    Progress progress;

    @Inject
    LiveMissions live;

    @Inject
    OfficeService office;

    @GET
    @Path("/progress")
    public List<MissionProgress> progress() {
        return progress.all();
    }

    /**
     * Asks the dispatcher to look at a mission again, as it does after each event it knows: work that can run starts.
     * Nothing is forced; the journal decides what can run.
     */
    @POST
    @Path("/missions/{id}/nudge")
    public List<MissionProgress> nudge(@PathParam("id") String id) {
        MissionId m = DirectorRequests.parse(() -> new MissionId(id));
        if (DirectorRequests.originOf(office, m) != Origin.REAL) {
            throw RefusedException.conflict(
                    "SIMULATED_MISSION", m + " is simulated: it plays its own script, Floor42 starts nothing for it");
        }
        live.nudge(m);
        return progress.all();
    }

    @Inject
    AwaySummary away;

    /**
     * What happened after this journal sequence (delivered tickets, merged work, sessions, their estimated cost) and
     * what waits now. Without {@code since}, the journal's latest sequence and nothing else: the starting point to
     * acknowledge.
     */
    @GET
    @Path("/journal/summary")
    public AwaySummary.AwayView summary(
            @Parameter(
                            description =
                                    "A journal sequence: what happened after it is summarised; without it, only the latest sequence is returned.")
                    @QueryParam("since")
                    Long since) {
        return away.since(since);
    }
}
