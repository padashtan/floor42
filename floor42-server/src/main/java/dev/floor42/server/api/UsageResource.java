package dev.floor42.server.api;

import dev.floor42.server.api.UsageViews.UsageView;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.OfficeSettings;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;

/** The director's usage page. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class UsageResource {

    private final OfficeService office;
    private final OfficeSettings settings;

    public UsageResource(OfficeService office, OfficeSettings settings) {
        this.office = office;
        this.settings = settings;
    }

    /** The usage page: plan windows (measured by Claude Code) and what agent sessions cost (estimated). */
    @GET
    @Path("/usage")
    public UsageView usage() {
        return office.query(c -> UsageView.of(c, settings.planLimitThreshold(), Instant.now()));
    }
}
