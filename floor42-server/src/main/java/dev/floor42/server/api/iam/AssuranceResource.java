package dev.floor42.server.api.iam;

import dev.floor42.server.iam.AssuredActs;
import dev.floor42.server.iam.AssuredWaiting;
import dev.floor42.server.iam.IamSettings;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** The acts waiting for a second pair of eyes (ADR-0081): who signed first, until when, where the second person signs. */
@Path("/api/iam/assurance")
public class AssuranceResource {

    private final IamSettings settings;
    private final AssuredActs acts;

    public AssuranceResource(IamSettings settings, AssuredActs acts) {
        this.settings = settings;
        this.acts = acts;
    }

    /** Empty with IAM mode off. A first signature that nobody countersigned in 30 minutes is gone from the list. */
    @GET
    @Path("/waiting")
    @Produces(MediaType.APPLICATION_JSON)
    public List<AssuredWaiting> waiting() {
        return settings.enabled() ? acts.waiting() : List.of();
    }
}
