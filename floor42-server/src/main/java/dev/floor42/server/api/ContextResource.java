package dev.floor42.server.api;

import dev.floor42.server.app.OfficeService;
import dev.floor42.server.runtime.ContextGauge;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * A session's context in use, as its last request reported it (live ones on /ws/context). In memory, not journaled.
 * A known session that has not reported yet answers 204: the screen asks as soon as the session opens.
 */
@Path("/api/sessions")
@Produces(MediaType.APPLICATION_JSON)
public class ContextResource {

    @Inject
    ContextGauge gauge;

    @Inject
    OfficeService office;

    @GET
    @Path("/{id}/context")
    public ContextGauge.ContextReading context(@PathParam("id") String id) {
        return gauge.last(id).orElseGet(() -> {
            if (office.query(c -> c.sessions().find(id)).isEmpty()) throw new NotFoundException("No session " + id);
            return null; // 204: no reading yet
        });
    }
}
