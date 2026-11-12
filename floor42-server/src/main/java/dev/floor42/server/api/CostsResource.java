package dev.floor42.server.api;

import dev.floor42.domain.MissionId;
import dev.floor42.server.app.Costs;
import dev.floor42.server.app.RefusedException;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

/**
 * What the sessions cost, read from the journal (a projection: nothing is recorded by reading it). Director only, like
 * every route under /api: an agent session's credential gets 403.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class CostsResource {

    @Inject
    Costs costs;

    /** @param zone the director's time zone (IANA, such as Europe/Paris) for today, the week and the month */
    @GET
    @Path("/costs")
    public Costs.CostsOverview overview(
            @Parameter(
                            description =
                                    "The director time zone (IANA, such as Europe/Paris) that sets today, the week and the month.")
                    @QueryParam("zone")
                    String zone) {
        ZoneId z;
        try {
            z = zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone.strip());
        } catch (DateTimeException e) {
            throw RefusedException.invalid("INVALID_ARGUMENT", "Unknown time zone " + zone, e);
        }
        return costs.overview(z, Instant.now());
    }

    @GET
    @Path("/missions/{id}/costs")
    public Costs.MissionCosts mission(@PathParam("id") String id) {
        MissionId m = new MissionId(id);
        return costs.of(m).orElseThrow(() -> new NotFoundException("No mission " + id));
    }
}
