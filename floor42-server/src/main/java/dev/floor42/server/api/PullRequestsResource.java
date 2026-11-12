package dev.floor42.server.api;

import dev.floor42.domain.Actor;
import dev.floor42.domain.MissionId;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.PullRequests;
import dev.floor42.server.app.PullRequests.PullRequestOutcome;
import dev.floor42.server.app.PullRequests.PullRequestPreview;
import dev.floor42.server.app.PullRequests.PullRequestRequest;
import dev.floor42.server.app.PullRequests.PullRequestView;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

/**
 * Pull requests on GitHub (ADR-0036): the director's routes only, under {@code /api}, so the director's session token
 * only. No agent tool, hook or brief reaches them; Floor42's MCP server offers nothing of the kind.
 */
@Path("/api/missions/{id}/pull-requests")
@Produces(MediaType.APPLICATION_JSON)
public class PullRequestsResource {

    @Inject
    OfficeService office;

    @Inject
    PullRequests pullRequests;

    @GET
    public List<PullRequestView> list(@PathParam("id") String id) {
        return pullRequests.list(mission(id));
    }

    @GET
    @Path("/preview")
    @RunOnVirtualThread
    public PullRequestPreview preview(
            @PathParam("id") String id,
            @Parameter(description = "The workspace (agent) whose branch the preview is about.")
                    @QueryParam("workspace")
                    String workspace) {
        return pullRequests.preview(Actor.director(), mission(id), workspace);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public PullRequestOutcome open(@PathParam("id") String id, PullRequestRequest request) {
        return pullRequests.open(Actor.director(), mission(id), request);
    }

    @POST
    @Path("/{number}/checks")
    @RunOnVirtualThread
    public PullRequestView refreshChecks(@PathParam("id") String id, @PathParam("number") int number) {
        return pullRequests.refreshChecks(Actor.director(), mission(id), number);
    }

    private MissionId mission(String id) {
        MissionId m = new MissionId(id);
        if (office.query(c -> c.missions().find(m)).isEmpty()) throw new NotFoundException("No mission " + id);
        return m;
    }
}
