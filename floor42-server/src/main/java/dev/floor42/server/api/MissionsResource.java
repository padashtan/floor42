package dev.floor42.server.api;

import static dev.floor42.server.api.DirectorRequests.asDirector;
import static dev.floor42.server.api.DirectorRequests.blank;
import static dev.floor42.server.api.DirectorRequests.missionView;
import static dev.floor42.server.api.DirectorRequests.parse;

import dev.floor42.domain.MissionId;
import dev.floor42.server.api.DirectorRequests.Reason;
import dev.floor42.server.api.MissionViews.MissionSummary;
import dev.floor42.server.api.MissionViews.MissionView;
import dev.floor42.server.app.CommitteeRoles;
import dev.floor42.server.app.DeliverablesHome;
import dev.floor42.server.app.IdempotentCreations;
import dev.floor42.server.app.LiveMissions;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.RefusedException;
import dev.floor42.server.app.Staffing;
import dev.floor42.server.app.UncommittedDeliverables;
import dev.floor42.server.iam.IamSettings;
import dev.floor42.server.security.LocalAccessGuard;
import dev.floor42.server.security.SessionRegistry;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The director's missions: creating one, deciding on its plan, its checks and budget, and closing it. Protected by
 * {@code LocalAccessGuard}.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class MissionsResource {

    /**
     * @param repository the git repository; may be empty for an idea committee, which then meets in a new empty one
     * @param committee roles to seat on a committee first (the architect, or the idea committee's moderator, chairs);
     *     empty for none
     * @param plan true to let the planner split the objective into tickets the director validates (ADR-0017)
     * @param panel {@code architecture} (default) or {@code idea}: a committee before a new project (ADR-0024)
     * @param workflow the name of a company workflow the mission pins; absent: Floor42's built-in one (ADR-0050)
     * @param deliverTo where the accepted deliverables are copied (ADR-0051): a folder inside the project (relative)
     *     or an existing absolute one; absent or blank: nothing is copied
     * @param mode how the team works (ADR-0062): {@code loop} (default) or {@code graph}, which needs {@code plan}
     */
    public record NewMission(
            String title,
            String objective,
            String repository,
            @NoNullItems List<String> committee,
            Boolean plan,
            String panel,
            String workflow,
            String deliverTo,
            String mode) {
        public NewMission {
            committee = committee == null ? null : Collections.unmodifiableList(new ArrayList<>(committee));
        }
    }

    /**
     * The director accepts the delivery: {@code reason} is the closing summary; {@code withIgnored} also copies the
     * documents git ignores (build outputs such as {@code out/report.pdf}) when the mission has a destination.
     */
    /**
     * @param acknowledged the token of the uncommitted work the director saw and accepts as it is (ADR-0055); null when
     *     there is none
     * @param proofAcknowledged the token of the unproven delivery the director saw and accepts as it is (ADR-0058);
     *     null when it is proven
     */
    public record Completion(String reason, Boolean withIgnored, String acknowledged, String proofAcknowledged) {}

    /**
     * The cap decided with the approval: a budget in USD, or {@code noCap} true for none, both journaled as the
     * director's decision. Both null keeps the budget already set; a real mission without one is refused.
     * {@code resources} (IAM mode only, optional): the catalogue names the mission's agents may reach for the director.
     */
    public record PlanApproval(
            Double budgetUsd, Boolean noCap, @NoNullItems List<String> resources) {
        public PlanApproval {
            resources = resources == null ? null : Collections.unmodifiableList(new ArrayList<>(resources));
        }
    }

    public record ChecksRequest(String command, boolean automatic) {}

    public record BudgetRequest(Double usd) {}

    /**
     * A developer who takes no new ticket until the director accepts or rejects, in the workshop, the changes of the
     * finished ticket its worktree still holds. Measured on the worktree.
     */
    public record HeldDeveloper(String agent, String ticket) {}

    private final OfficeService office;
    private final LiveMissions live;
    private final Staffing staffing;
    private final IdempotentCreations creations;
    private final DeliverablesHome home;

    private final CurrentVertxRequest request;
    private final PlanApprovals approvals;
    private final IamSettings iam;

    public MissionsResource(
            OfficeService office,
            LiveMissions live,
            Staffing staffing,
            IdempotentCreations creations,
            DeliverablesHome home,
            CurrentVertxRequest request,
            PlanApprovals approvals,
            IamSettings iam) {
        this.office = office;
        this.live = live;
        this.staffing = staffing;
        this.creations = creations;
        this.home = home;
        this.request = request;
        this.approvals = approvals;
        this.iam = iam;
    }

    /** The folder the form offers by default (ADR-0051): the only one a {@code missions:write} script may give. */
    static final String DEFAULT_DELIVER_TO = "deliverables";

    /**
     * Writing files outside the project's default folder is the director's choice: their browser, or a script token of
     * scope {@code all}. A {@code missions:write} token steers missions; it may keep the default, or give none.
     */
    private void refuseAFolderToANarrowScope(String deliverTo) {
        if (deliverTo == null || deliverTo.isBlank() || deliverTo.strip().equals(DEFAULT_DELIVER_TO)) return;
        if (LocalAccessGuard.scopeOf(request.getCurrent()) == SessionRegistry.Scope.ALL) return;
        throw new RefusedException(
                403, "SCOPE", "A deliverables folder other than the default needs a token of scope all");
    }

    /**
     * The missions on the board and in the lists; with {@code archived=true}, only those the director put away
     * (ADR-0054), restorable from there.
     */
    @GET
    @Path("/missions")
    public List<MissionSummary> missions(
            @Parameter(description = "true lists the archived missions instead of the live ones (default false).")
                    @QueryParam("archived")
                    @DefaultValue("false")
                    boolean archived) {
        return office.query(c -> c.missions().all().stream()
                .filter(m -> m.archived() == archived)
                .map(m -> MissionSummary.of(c, m))
                .toList());
    }

    @GET
    @Path("/missions/{id}")
    public MissionView mission(@PathParam("id") String id) {
        MissionId mission = parse(() -> new MissionId(id));
        return office.query(c -> c.missions()
                .find(mission)
                .map(m -> MissionView.of(c, m))
                .orElseThrow(() -> new NotFoundException("No mission " + id)));
    }

    /** The longest mission title: a title is read on a board card and in a tab. */
    public static final int TITLE_MAX = 200;

    /** The longest objective: a brief of several pages, never a pasted file. */
    public static final int OBJECTIVE_MAX = 20_000;

    /**
     * A real mission: live agents on the given git repository. Floor42 proposes a plan the director then approves. With
     * an {@code Idempotency-Key}, the same request sent twice creates one mission: the second answer is 200 with it.
     */
    @POST
    @Path("/missions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createMission(NewMission request, @HeaderParam("Idempotency-Key") String key) {
        requireValid(request);
        refuseAFolderToANarrowScope(request.deliverTo());
        if (blank(key)) {
            return Response.status(Response.Status.CREATED)
                    .entity(missionView(office, create(request)))
                    .build();
        }
        IdempotentCreations.Outcome done =
                creations.create(key.strip(), String.valueOf(request), () -> create(request));
        return Response.status(done.replayed() ? Response.Status.OK : Response.Status.CREATED)
                .entity(missionView(office, done.mission()))
                .build();
    }

    private static void requireValid(NewMission request) {
        boolean idea = request != null && CommitteeRoles.IDEA.equals(request.panel());
        if (request == null
                || blank(request.title())
                || blank(request.objective())
                || (blank(request.repository()) && !idea)) {
            throw RefusedException.invalid("MISSING_FIELDS", "title, objective and repository are required");
        }
        if (request.mode() != null
                && !request.mode().equals("loop")
                && !request.mode().equals("graph")) {
            throw RefusedException.invalid("INVALID_ARGUMENT", "mode is loop or graph");
        }
        if (request.title().strip().length() > TITLE_MAX) {
            throw RefusedException.invalid("TITLE_TOO_LONG", "title is at most " + TITLE_MAX + " characters");
        }
        if (request.objective().strip().length() > OBJECTIVE_MAX) {
            throw RefusedException.invalid(
                    "OBJECTIVE_TOO_LONG", "objective is at most " + OBJECTIVE_MAX + " characters");
        }
    }

    private MissionId create(NewMission request) {
        try {
            return live.create(
                    request.title(),
                    request.objective(),
                    blank(request.repository()) ? null : request.repository(),
                    request.committee() == null ? List.of() : request.committee(),
                    Boolean.TRUE.equals(request.plan()),
                    request.panel(),
                    request.workflow(),
                    request.deliverTo(),
                    "graph".equals(request.mode()));
        } catch (IllegalArgumentException e) {
            throw RefusedException.invalid("INVALID_REPOSITORY", e.getMessage(), e);
        }
    }

    @POST
    @Path("/missions/{id}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    @APIResponse(
            responseCode = "200",
            description = "Approved (IAM mode off)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = MissionView.class)))
    @APIResponse(
            responseCode = "202",
            description = "IAM mode: follow redirect to type the password again",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = PlanApprovals.StepUp.class)))
    public Response approve(@PathParam("id") String id, PlanApproval request) {
        MissionId m = parse(() -> new MissionId(id));
        boolean noCap = request != null && Boolean.TRUE.equals(request.noCap());
        Double budget = request == null ? null : request.budgetUsd();
        if (noCap && budget != null) {
            throw RefusedException.invalid("INVALID_ARGUMENT", "Give a budget or choose no cap, not both");
        }
        if (iam.enabled()) {
            // Answers 202 with the path of the password step-up; the plan is approved when that succeeds.
            List<String> resources = request == null ? null : request.resources();
            return approvals.stepUp(m, budget, noCap, resources, LocalAccessGuard.sessionOf(this.request.getCurrent()));
        }
        approvals.approve(m, budget, noCap);
        return Response.ok(missionView(office, m)).build();
    }

    @POST
    @Path("/missions/{id}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    public MissionView reject(@PathParam("id") String id, Reason reason) {
        MissionId m = parse(() -> new MissionId(id));
        office.execute(c -> c.missions().rejectPlan(asDirector(office, m), m, reason == null ? null : reason.reason()));
        return missionView(office, m);
    }

    @POST
    @Path("/missions/{id}/complete")
    @Consumes(MediaType.APPLICATION_JSON)
    public MissionView complete(@PathParam("id") String id, Completion summary) {
        MissionId m = parse(() -> new MissionId(id));
        // A mission with a destination gets its documents copied there first (ADR-0051); the others close as before.
        // Work left uncommitted in a worktree, or a state Floor42 cannot read, needs the director's say-so (ADR-0055).
        home.complete(
                m,
                summary == null ? null : summary.reason(),
                summary != null && Boolean.TRUE.equals(summary.withIgnored()),
                summary == null ? null : summary.acknowledged(),
                summary == null ? null : summary.proofAcknowledged());
        return missionView(office, m);
    }

    /**
     * What is not committed in the mission's worktrees now, each worktree CLEAN, DIRTY or UNKNOWN (ADR-0055); the
     * token goes back with an acceptance that knowingly leaves it so.
     */
    @GET
    @Path("/missions/{id}/uncommitted")
    public UncommittedDeliverables.UncommittedCheck uncommitted(@PathParam("id") String id) {
        MissionId m = parse(() -> new MissionId(id));
        return home.uncommitted(m);
    }

    /** The command Floor42 runs in agents' workspaces to check their work; optionally after every delivery. */
    @PUT
    @Path("/missions/{id}/checks")
    @Consumes(MediaType.APPLICATION_JSON)
    public MissionView configureChecks(@PathParam("id") String id, ChecksRequest request) {
        MissionId m = parse(() -> new MissionId(id));
        if (request == null) throw RefusedException.invalid("MISSING_FIELDS", "command and automatic are expected");
        office.execute(c ->
                c.verification().configureChecks(asDirector(office, m), m, request.command(), request.automatic()));
        return missionView(office, m);
    }

    /** A budget in USD, compared with the runtime's cost estimates; null removes the limit. */
    @PUT
    @Path("/missions/{id}/budget")
    @Consumes(MediaType.APPLICATION_JSON)
    public MissionView setBudget(@PathParam("id") String id, BudgetRequest request) {
        MissionId m = parse(() -> new MissionId(id));
        office.execute(
                c -> c.verification().setBudget(asDirector(office, m), m, request == null ? null : request.usd()));
        return missionView(office, m);
    }

    /**
     * Resumes a mission Floor42 suspended after a critical incident. The director decides, having read the incident;
     * the dispatcher then picks the work up where the journal says it stands.
     */
    @POST
    @Path("/missions/{id}/resume")
    public MissionView resume(@PathParam("id") String id) {
        MissionId m = parse(() -> new MissionId(id));
        office.execute(c -> c.missions().resume(asDirector(office, m), m));
        return missionView(office, m);
    }

    /**
     * Asks the planner whether to reinforce the team: one short session on a light model, with no tool, from the facts
     * Floor42 measured. Its advice comes back as a yes-or-no question; nothing is hired before the director's yes.
     */
    @POST
    @Path("/missions/{id}/staffing")
    public MissionView reviewStaffing(@PathParam("id") String id) {
        MissionId m = parse(() -> new MissionId(id));
        staffing.request(m);
        return missionView(office, m);
    }

    @GET
    @Path("/missions/{id}/held")
    public List<HeldDeveloper> held(@PathParam("id") String id) {
        MissionId m = parse(() -> new MissionId(id));
        office.query(c -> c.missions().find(m).orElseThrow(() -> new NotFoundException("No mission " + id)));
        return live.held(m).entrySet().stream()
                .map(e -> new HeldDeveloper(e.getKey().value(), e.getValue().value()))
                .toList();
    }

    /** Abandons a mission at any stage before it ends: open questions are withdrawn, pending actions denied. */
    @POST
    @Path("/missions/{id}/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    public MissionView cancel(@PathParam("id") String id, Reason reason) {
        MissionId m = parse(() -> new MissionId(id));
        // Never in the middle of a copy of the deliverables: one close or cancel of a mission at a time.
        home.closing(
                m,
                () -> office.execute(
                        c -> c.missions().cancel(asDirector(office, m), m, reason == null ? null : reason.reason())));
        return missionView(office, m);
    }
}
