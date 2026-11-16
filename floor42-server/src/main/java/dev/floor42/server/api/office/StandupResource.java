package dev.floor42.server.api.office;

import dev.floor42.server.app.office.standup.Standup;
import dev.floor42.server.app.office.standup.Standups;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.DateTimeException;
import java.time.ZoneId;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The 9 o'clock standup (ADR-0078): read from the journal, no model call, nothing journaled. For the director only: it
 * sits behind the session token.
 */
@Path("/api/standup")
public class StandupResource {

    @Inject
    Standups standups;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(
            responseCode = "200",
            description = "Per agent: yesterday, today and blocked, from the real events of the journal",
            content =
                    @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Standup.class)))
    @RunOnVirtualThread
    public Response standup(
            @Parameter(description = "An IANA time zone id (the browser's); the daemon's own zone when absent.")
                    @QueryParam("zone")
                    String zone) {
        Standup s = standups.now(zoneOf(zone));
        return Response.ok(s, MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .build();
    }

    static ZoneId zoneOf(String zone) {
        if (zone == null || zone.isBlank()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new BadRequestException("Unknown time zone " + zone, e);
        }
    }
}
