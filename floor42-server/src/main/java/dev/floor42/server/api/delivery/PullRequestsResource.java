package dev.floor42.server.api.delivery;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AssurancePolicy;
import dev.floor42.domain.MissionId;
import dev.floor42.server.api.IamActGuard;
import dev.floor42.server.app.core.OfficeService;
import dev.floor42.server.app.delivery.PullRequests;
import dev.floor42.server.app.delivery.PullRequests.MergeRequest;
import dev.floor42.server.app.delivery.PullRequests.PullRequestOutcome;
import dev.floor42.server.app.delivery.PullRequests.PullRequestPreview;
import dev.floor42.server.app.delivery.PullRequests.PullRequestRequest;
import dev.floor42.server.app.delivery.PullRequests.PullRequestView;
import dev.floor42.server.security.LocalAccessGuard;
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

    @Inject
    IamActGuard guard;

    @Inject
    io.quarkus.vertx.http.runtime.CurrentVertxRequest current;

    @Inject
    dev.floor42.server.iam.IamSettings iam;

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
        return pullRequests.refreshChecks(mission(id), number);
    }

    /** Where the branch is now, against the commit the checks were read for: tells when the evidence is stale. */
    @GET
    @Path("/{number}/head")
    @RunOnVirtualThread
    public PullRequestView head(@PathParam("id") String id, @PathParam("number") int number) {
        return pullRequests.head(mission(id), number);
    }

    /**
     * Merges the pull request on GitHub with the director's own gh (ADR-0084), only at the commit the director looked at
     * and only when its checks are green (409 before anything is asked of the phone). In IAM mode the merge is an
     * assured act at the strong level: a token gets 403 {@code HUMAN_ONLY}, the browser follows the step-up (202) and the
     * merge is carried out once the signature is in, after the same checks again.
     */
    @POST
    @Path("/{number}/merge")
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public PullRequestView merge(@PathParam("id") String id, @PathParam("number") int number, MergeRequest request) {
        // The director's session click, never a token: the bearer, a script token (whatever its scope), IAM on or off.
        if (LocalAccessGuard.viaOf(current.getCurrent()) != LocalAccessGuard.Via.COOKIE) {
            throw dev.floor42.server.api.AssuranceResponses.humanOnly();
        }
        MissionId m = mission(id);
        String commit = request == null ? null : request.commit();
        pullRequests.checkMerge(m, number, commit);
        String ref = "pr:" + m + "#" + number;
        return guard.run(
                AssurancePolicy.ActKind.MERGE,
                ref,
                true,
                () -> pullRequests.merge(m, number, commit, iam.enabled() ? "MERGE:" + ref : "CLICK"));
    }

    private MissionId mission(String id) {
        MissionId m = new MissionId(id);
        if (office.query(c -> c.missions().find(m)).isEmpty()) throw new NotFoundException("No mission " + id);
        return m;
    }
}
