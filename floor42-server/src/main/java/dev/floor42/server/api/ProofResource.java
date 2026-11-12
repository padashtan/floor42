package dev.floor42.server.api;

import static dev.floor42.server.api.DirectorRequests.parse;

import dev.floor42.domain.MissionId;
import dev.floor42.server.app.DeliveryProofs;
import dev.floor42.server.app.ProofRunner;
import dev.floor42.server.app.ProofSendBack;
import dev.floor42.server.app.ProofViews.ProofAcceptanceView;
import dev.floor42.server.app.ProofViews.ProofView;
import dev.floor42.server.app.ProofViews.SentBack;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * "Prove the delivery" (ADR-0058): the producers' recipes, Floor42's run of the steps it can run in the sandbox (on a
 * copy, offline, never on the host), the director's reports and verdict, and "Send back to the producer".
 */
@Path("/api/missions/{id}/proof")
@Produces(MediaType.APPLICATION_JSON)
public class ProofResource {

    /**
     * @param agent the agent whose worktree's recipe failed
     * @param note anything more the director wants the producer to read
     */
    public record SendBackRequest(String agent, String note) {}

    /**
     * @param step the step's number in that agent's recipe
     * @param seen what the director saw; required when it does not work
     * @param target the worktree's `target` as the director read it: refused (409 PROOF_STALE) when it changed
     */
    public record WitnessRequest(String agent, int step, boolean works, String seen, String target) {}

    /**
     * @param verdict PROVEN or NOT_PROVEN
     * @param targets the view's `targets` as the director read it: refused (409 PROOF_STALE) when it changed
     */
    public record VerdictRequest(String verdict, String note, String targets) {}

    @Inject
    DeliveryProofs proofs;

    @Inject
    ProofRunner runner;

    @Inject
    ProofSendBack sendBack;

    @GET
    @RunOnVirtualThread
    public ProofView proof(@PathParam("id") String id) {
        return proofs.view(parse(() -> new MissionId(id)));
    }

    @GET
    @Path("/acceptance")
    @RunOnVirtualThread
    public ProofAcceptanceView acceptance(@PathParam("id") String id) {
        return proofs.acceptance(parse(() -> new MissionId(id)));
    }

    @POST
    @Path("/run")
    @RunOnVirtualThread
    public ProofView run(@PathParam("id") String id) {
        MissionId m = parse(() -> new MissionId(id));
        runner.start(m);
        return proofs.view(m);
    }

    @POST
    @Path("/witness")
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public ProofView witness(@PathParam("id") String id, WitnessRequest r) {
        MissionId m = parse(() -> new MissionId(id));
        if (r == null) throw dev.floor42.server.app.RefusedException.invalid("INVALID_ARGUMENT", "a body is required");
        return proofs.witness(m, r.agent(), r.step(), r.works(), r.seen(), r.target());
    }

    @POST
    @Path("/verdict")
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public ProofView verdict(@PathParam("id") String id, VerdictRequest r) {
        MissionId m = parse(() -> new MissionId(id));
        if (r == null) throw dev.floor42.server.app.RefusedException.invalid("INVALID_ARGUMENT", "a body is required");
        return proofs.verdict(m, r.verdict(), r.note(), r.targets());
    }

    @POST
    @Path("/send-back")
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public SentBack sendBack(@PathParam("id") String id, SendBackRequest request) {
        MissionId m = parse(() -> new MissionId(id));
        return sendBack.sendBack(m, request == null ? null : request.agent(), request == null ? null : request.note());
    }
}
