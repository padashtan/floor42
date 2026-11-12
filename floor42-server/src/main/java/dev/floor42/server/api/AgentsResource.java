package dev.floor42.server.api;

import static dev.floor42.server.api.DirectorRequests.asDirector;
import static dev.floor42.server.api.DirectorRequests.blank;
import static dev.floor42.server.api.DirectorRequests.parse;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.Setting;
import dev.floor42.domain.WorkRole;
import dev.floor42.server.api.AgentViews.AgentView;
import dev.floor42.server.api.AgentViews.CoreDismissalEntry;
import dev.floor42.server.api.AgentViews.CoreDismissalOutcome;
import dev.floor42.server.api.AgentViews.CoreDismissalPreview;
import dev.floor42.server.api.AgentViews.CoreDismissalReport;
import dev.floor42.server.api.DirectorRequests.Reason;
import dev.floor42.server.app.Dismissals;
import dev.floor42.server.app.LiveMissions;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.RefusedException;
import dev.floor42.server.app.Staff;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** The director's staff: hiring, settings, suspension and dismissal of agents. Protected by {@code LocalAccessGuard}. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class AgentsResource {

    public record Hiring(String name, String role, String workRole, String model, String effort) {}

    public record CoreMark(Boolean core) {}

    public record CoreDismissal(@NoNullItems List<String> agents, String reason) {
        public CoreDismissal {
            agents = agents == null ? null : Collections.unmodifiableList(new ArrayList<>(agents));
        }
    }

    public record AgentSettings(String model, String effort) {}

    private final OfficeService office;
    private final LiveMissions live;
    private final Dismissals dismissals;

    public AgentsResource(OfficeService office, LiveMissions live, Dismissals dismissals) {
        this.office = office;
        this.live = live;
        this.dismissals = dismissals;
    }

    /** The staff present: agents who left stay in the journal and in their profiles, not in the office. */
    @GET
    @Path("/agents")
    public List<AgentView> agents() {
        return office.query(c -> c.staff().all().stream()
                .filter(a -> !a.dismissed())
                .map(a -> AgentView.of(c, a))
                .toList());
    }

    /** The director hires an agent for a work role (ADR-0019); what it may do follows from the role, by code. */
    @POST
    @Path("/agents")
    @Consumes(MediaType.APPLICATION_JSON)
    public AgentView hireAgent(Hiring hiring) {
        if (hiring == null || blank(hiring.name()) || blank(hiring.workRole()) || blank(hiring.model())) {
            throw RefusedException.invalid("MISSING_FIELDS", "name, workRole and model are required");
        }
        WorkRole role = workRole(hiring.workRole());
        String model = knownModel(hiring.model());
        String title = blank(hiring.role()) ? defaultTitle(role) : hiring.role().strip();
        // The id is chosen under the same lock that records the hiring: two hirings never take the same one.
        AtomicReference<AgentId> hired = new AtomicReference<>();
        office.execute(c -> c.staff()
                .hire(
                        asDirector(office),
                        newId(c, freeName(c, hiring.name()), hired),
                        hiring.name().strip(),
                        title,
                        role,
                        model,
                        blank(hiring.effort()) ? null : hiring.effort().strip()));
        // New hands are put to work at once on the missions in progress.
        live.staffChanged();
        return agentView(hired.get());
    }

    /**
     * The director names an agent the office does not already have: two present agents never share a name, whatever
     * its case. The identifier still takes a number when the name was a former agent's, since a journal keeps every
     * identifier for good; the answer carries it.
     */
    private static String freeName(Company c, String name) {
        String wanted = name.strip();
        String key = sameName(wanted);
        boolean taken = c.staff().all().stream()
                .anyMatch(a -> !a.dismissed() && sameName(a.displayName()).equals(key));
        if (taken) throw RefusedException.conflict("NAME_TAKEN", "An agent named " + wanted + " already works here");
        return wanted;
    }

    /**
     * The form two names compare in: NFKC (a full-width or ligature letter is its plain one), zero-width, direction and
     * variation marks removed (U+200B to U+200F, U+2060, U+FEFF, U+00AD, U+034F, U+FE0F), Unicode spaces collapsed, case folded. "Dev", " dev ", "Dev" with a zero-width space inside, and "ＤＥＶ" are one name.
     */
    static String sameName(String name) {
        String plain = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKC)
                .replaceAll("[\\u200B-\\u200F\\u2060\\uFEFF\\u00AD\\u034F\\uFE0F]", "")
                .replaceAll("(?U)[\\p{Z}\\s]+", " ")
                .strip();
        return plain.toUpperCase(java.util.Locale.ROOT).toLowerCase(java.util.Locale.ROOT);
    }

    /** A model of the catalogue the hire panel offers: an unknown one would only fail at the agent's first session. */
    private static String knownModel(String model) {
        String m = model.strip();
        List<String> known = Setting.RETRO_MODEL.options();
        if (!known.contains(m)) {
            throw RefusedException.invalid("UNKNOWN_MODEL", "model is one of " + String.join(", ", known));
        }
        return m;
    }

    private static WorkRole workRole(String name) {
        WorkRole role;
        try {
            role = WorkRole.valueOf(name.strip());
        } catch (IllegalArgumentException e) {
            throw RefusedException.invalid(
                    "INVALID_ARGUMENT", "workRole is DEVELOPER, REVIEWER, RESEARCHER or ANALYST", e);
        }
        if (role == WorkRole.PLANNER) {
            throw RefusedException.invalid(
                    "INVALID_ARGUMENT", "The planner is Geoffrey; hire developers, reviewers or researchers");
        }
        return role;
    }

    private static AgentId newId(Company c, String name, AtomicReference<AgentId> out) {
        out.set(Staff.newId(c, name));
        return out.get();
    }

    private static String defaultTitle(WorkRole role) {
        return switch (role) {
            case DEVELOPER -> "Developer";
            case REVIEWER -> "Reviewer";
            case RESEARCHER -> "Researcher";
            case PLANNER -> "Planner";
            case ANALYST -> "Business analyst";
        };
    }

    /**
     * The agent leaves the company unless it is at work right now; its history stays. Only the two chairs stay for
     * good: Geoffrey plans every mission and chairs the architecture committee, Lucie moderates idea committees, and
     * no one can be hired in their place. A committee member who leaves gives up their seat.
     */
    @POST
    @Path("/agents/{id}/dismiss")
    @Consumes(MediaType.APPLICATION_JSON)
    public AgentView dismissAgent(@PathParam("id") String id, Reason reason) {
        AgentId agent = parse(() -> new AgentId(id));
        String why = reason == null || blank(reason.reason()) ? "dismissed by the director" : reason.reason();
        // The chairs stay; nobody leaves mid-task. Its tickets still to do, and those it had started, go to a
        // colleague.
        dismissals.dismiss(agent, why);
        return agentView(agent);
    }

    /** The director stars an agent as a core member, or takes the star away (journaled, real). */
    @PUT
    @Path("/agents/{id}/core")
    @Consumes(MediaType.APPLICATION_JSON)
    public AgentView markCore(@PathParam("id") String id, CoreMark mark) {
        AgentId agent = parse(() -> new AgentId(id));
        if (mark == null || mark.core() == null) throw RefusedException.invalid("MISSING_FIELDS", "core is required");
        office.execute(c -> c.staff().markCore(asDirector(office), agent, mark.core()));
        return agentView(agent);
    }

    /** Who "dismiss everyone except the core members" would let go, who stays, and who cannot leave now, and why. */
    @GET
    @Path("/agents/dismiss-all-but-core")
    public CoreDismissalPreview coreDismissalPreview() {
        return new CoreDismissalPreview(
                dismissals.preview().stream().map(CoreDismissalEntry::of).toList());
    }

    /**
     * Dismisses, one by one and each as a single dismissal would, the agents the director confirmed from the preview;
     * core members and anyone at work are never dismissed. Reports the outcome per agent.
     */
    @POST
    @Path("/agents/dismiss-all-but-core")
    @Consumes(MediaType.APPLICATION_JSON)
    public CoreDismissalReport dismissAllButCore(CoreDismissal request) {
        if (request == null || request.agents() == null) {
            throw RefusedException.invalid(
                    "MISSING_FIELDS", "agents (the ids the preview lists as leaving) is required");
        }
        List<AgentId> confirmed =
                request.agents().stream().map(a -> parse(() -> new AgentId(a))).toList();
        String why = blank(request.reason()) ? "dismissed with everyone but the core members" : request.reason();
        return new CoreDismissalReport(dismissals.dismissAllButCore(confirmed, why).stream()
                .map(CoreDismissalOutcome::of)
                .toList());
    }

    /** Declared (hiring) and measured (journal, real missions) facts about an agent. */
    @GET
    @Path("/agents/{id}/profile")
    public AgentProfileView agentProfile(@PathParam("id") String id) {
        AgentId agent = parse(() -> new AgentId(id));
        return office.query(c -> c.staff()
                .find(agent)
                .map(a -> AgentProfileView.of(c, a))
                .orElseThrow(() -> new NotFoundException("No agent " + id)));
    }

    /** The director changes the model and effort of an agent's next sessions. */
    @PUT
    @Path("/agents/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public AgentView reconfigureAgent(@PathParam("id") String id, AgentSettings settings) {
        AgentId agent = parse(() -> new AgentId(id));
        if (settings == null || blank(settings.model())) {
            throw RefusedException.invalid("MISSING_FIELDS", "model is required");
        }
        // An agent hired before the catalogue was checked keeps its model; a new one is a model of the catalogue.
        String current =
                office.query(c -> c.staff().find(agent).map(a -> a.model()).orElse(null));
        String model = settings.model().strip().equals(current) ? current : knownModel(settings.model());
        office.execute(c -> c.staff().reconfigure(asDirector(office), agent, model, settings.effort()));
        return agentView(agent);
    }

    /** No new tickets are assigned to the agent; its current work goes on. */
    @POST
    @Path("/agents/{id}/suspend")
    @Consumes(MediaType.APPLICATION_JSON)
    public AgentView suspendAgent(@PathParam("id") String id, Reason reason) {
        AgentId agent = parse(() -> new AgentId(id));
        String why = reason == null || blank(reason.reason()) ? "suspended by the director" : reason.reason();
        office.execute(c -> c.staff().suspendAssignments(asDirector(office), agent, why));
        return agentView(agent);
    }

    @POST
    @Path("/agents/{id}/resume")
    public AgentView resumeAgent(@PathParam("id") String id) {
        AgentId agent = parse(() -> new AgentId(id));
        office.execute(c -> c.staff().resumeAssignments(asDirector(office), agent));
        return agentView(agent);
    }

    private AgentView agentView(AgentId agent) {
        return office.query(c -> c.staff()
                .find(agent)
                .map(a -> AgentView.of(c, a))
                .orElseThrow(() -> new NotFoundException("No agent " + agent)));
    }
}
