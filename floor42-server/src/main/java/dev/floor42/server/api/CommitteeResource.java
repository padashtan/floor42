package dev.floor42.server.api;

import static dev.floor42.server.api.DirectorRequests.asDirector;
import static dev.floor42.server.api.DirectorRequests.blank;
import static dev.floor42.server.api.DirectorRequests.missionView;
import static dev.floor42.server.api.DirectorRequests.parse;

import dev.floor42.domain.CommitteeRef;
import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.server.api.MissionViews.MissionView;
import dev.floor42.server.app.CommitteeRoles;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.RefusedException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The director and the committees: the seats to fill, and the director's say in a debate. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class CommitteeResource {

    /**
     * @param panels the committees this seat sits on: architecture, idea
     * @param custom true for a seat the director defined
     * @param vacant its member has left the company: the seat cannot be chosen
     */
    public record RoleView(
            String key,
            String name,
            String title,
            String model,
            String lens,
            @NoNullItems List<String> panels,
            boolean custom,
            boolean vacant) {
        public RoleView {
            panels = panels == null ? null : Collections.unmodifiableList(new ArrayList<>(panels));
        }
    }

    /** A seat of the director's own: its key (and its member's id) is made from the name. */
    public record NewSeat(String name, String title, String lens, String model, String effort, List<String> panels) {
        public NewSeat {
            panels = panels == null ? null : Collections.unmodifiableList(new ArrayList<>(panels));
        }
    }

    /** What the director says in person in the seat of their double, with the same closing lines as a member. */
    public record DirectorContribution(String text, String proposal, String position, String criticalObjection) {}

    public record Constraint(String constraint) {}

    private final OfficeService office;

    public CommitteeResource(OfficeService office) {
        this.office = office;
    }

    /** The seats the director can fill on a committee: Floor42's built-in ones, then the director's own. */
    @GET
    @Path("/committee/roles")
    public List<RoleView> committeeRoles() {
        return office.query(CommitteeResource::roleViews);
    }

    /**
     * The director defines a seat of their own: a specialist who only reads and gives an opinion. Defining the same
     * name again changes the seat. Its member is hired the first time a committee seats it.
     */
    @POST
    @Path("/committee/roles")
    @Consumes(MediaType.APPLICATION_JSON)
    public List<RoleView> defineSeat(NewSeat request) {
        if (request == null || blank(request.name()))
            throw RefusedException.invalid("MISSING_FIELDS", "name is required");
        String key = seatKey(request.name());
        if (CommitteeRoles.builtIn(key)) {
            throw RefusedException.invalid(
                    "SEAT_TAKEN", request.name() + " is one of Floor42's own seats: choose another name");
        }
        office.execute(c -> c.committees()
                .defineSeat(
                        asDirector(office),
                        key,
                        request.name(),
                        request.title(),
                        blank(request.model()) ? "claude-sonnet-5-5" : request.model(),
                        blank(request.effort()) ? "medium" : request.effort(),
                        request.lens(),
                        request.panels()));
        return office.query(CommitteeResource::roleViews);
    }

    /** The key of a seat, made from its name: without accents, lower case, words joined by dashes. */
    private static String seatKey(String name) {
        return Normalizer.normalize(name.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(?:^-)|(?:-$)", "");
    }

    private static List<RoleView> roleViews(Company c) {
        var custom =
                CommitteeRoles.custom(c).stream().map(CommitteeRoles.Role::key).toList();
        return CommitteeRoles.all(c).stream()
                .map(r -> new RoleView(
                        r.key(),
                        r.name(),
                        r.title(),
                        r.model(),
                        r.lens(),
                        r.panels(),
                        custom.contains(r.key()),
                        CommitteeRoles.vacant(c, r)))
                .toList();
    }

    /**
     * The director speaks in person in the seat of their double on an idea committee. Recorded as the director's; the
     * members read it from their next session on. A committee is named by its number in the mission (ADR-0046).
     */
    @POST
    @Path("/missions/{id}/committees/{no}/contribute")
    @Consumes(MediaType.APPLICATION_JSON)
    public MissionView contributeInPerson(
            @PathParam("id") String id, @PathParam("no") int no, DirectorContribution request) {
        CommitteeRef ref = committee(id, no);
        MissionId m = ref.mission();
        if (request == null || blank(request.text()))
            throw RefusedException.invalid("MISSING_FIELDS", "text is required");
        office.execute(c -> c.committees()
                .contributeAsDirector(
                        asDirector(office, m),
                        ref,
                        request.text(),
                        request.proposal(),
                        request.position(),
                        request.criticalObjection()));
        return missionView(office, m);
    }

    /** Sends the committee's decision back with a constraint: one more debate round, then a new record. */
    @POST
    @Path("/missions/{id}/committees/{no}/send-back")
    @Consumes(MediaType.APPLICATION_JSON)
    public MissionView sendBack(@PathParam("id") String id, @PathParam("no") int no, Constraint request) {
        CommitteeRef ref = committee(id, no);
        MissionId m = ref.mission();
        office.execute(c ->
                c.committees().sendBack(asDirector(office, m), ref, request == null ? null : request.constraint()));
        return missionView(office, m);
    }

    private static CommitteeRef committee(String id, int no) {
        return parse(() -> new CommitteeRef(new MissionId(id), no));
    }
}
