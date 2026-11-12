package dev.floor42.server.api;

import static dev.floor42.server.api.DirectorRequests.missionView;
import static dev.floor42.server.api.DirectorRequests.parse;

import dev.floor42.domain.MissionId;
import dev.floor42.server.api.MissionViews.MissionView;
import dev.floor42.server.app.GraphExtensions;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.RefusedException;
import dev.floor42.server.security.LocalAccessGuard;
import dev.floor42.server.security.SessionRegistry;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The graph of a graph-mode mission (ADR-0062). Protected by {@code LocalAccessGuard}, like every call; this one also
 * starts a paid planner session, so it needs the director's own browser or a token of scope {@code all}.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class GraphResource {

    /** @param goal what the director wants added, in a few words; optional */
    public record ExtendRequest(String goal) {}

    /** The longest goal kept: it goes into the planner's brief. */
    static final int MAX_GOAL = 1_000;

    @Inject
    CurrentVertxRequest request;

    @Inject
    OfficeService office;

    @Inject
    GraphExtensions extensions;

    /**
     * Extend the graph: one read-only planner session, given the graph as it stands, proposes at most 8 nodes. Paid. They
     * arrive as proposals to approve; nothing done is reopened.
     */
    @POST
    @Path("/missions/{id}/graph/extend")
    @Consumes(MediaType.APPLICATION_JSON)
    public MissionView extend(@PathParam("id") String id, ExtendRequest body) {
        if (LocalAccessGuard.scopeOf(request.getCurrent()) != SessionRegistry.Scope.ALL) {
            throw new RefusedException(403, "SCOPE", "Extending the graph starts a paid session: it needs scope all");
        }
        MissionId m = parse(() -> new MissionId(id));
        String goal = body == null || body.goal() == null ? "" : body.goal().strip();
        if (goal.length() > MAX_GOAL) {
            throw RefusedException.invalid("INVALID_ARGUMENT", "goal is at most " + MAX_GOAL + " characters");
        }
        extensions.start(m, goal);
        return missionView(office, m);
    }
}
