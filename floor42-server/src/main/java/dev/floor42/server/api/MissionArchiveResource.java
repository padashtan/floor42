package dev.floor42.server.api;

import static dev.floor42.server.api.DirectorRequests.asDirector;
import static dev.floor42.server.api.DirectorRequests.missionView;
import static dev.floor42.server.api.DirectorRequests.parse;

import dev.floor42.domain.Actor;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Refusal;
import dev.floor42.domain.Refused;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.server.api.MissionViews.MissionSummary;
import dev.floor42.server.api.MissionViews.MissionView;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.RefusedException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The archives (ADR-0054): the director puts closed missions away, one at a time or every closed one at once, and
 * restores them. An archived mission leaves the board and the lists ({@code GET /api/missions?archived=true} lists
 * them); its journal and worktrees stay. Every check runs inside the command that records the event, under
 * {@link OfficeService}'s lock, the one a session start and a retrospective also take. Protected by
 * {@code LocalAccessGuard}.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class MissionArchiveResource {

    private final OfficeService office;

    public MissionArchiveResource(OfficeService office) {
        this.office = office;
    }

    /** The most missions one request archives: a board, never a script's loop over an unbounded list. */
    static final int ARCHIVE_MAX = 500;

    /** @param missions the ids the director confirmed, from {@code GET /api/missions/archivable} */
    public record ArchiveRequest(@NoNullItems List<String> missions) {
        public ArchiveRequest {
            missions = missions == null ? null : Collections.unmodifiableList(new ArrayList<>(missions));
        }
    }

    /**
     * @param archived true when this request archived it
     * @param code why it was not: the refusal's code ({@code MISSION_STILL_OPEN}, {@code SESSION_RUNNING},
     *     {@code RETRO_UNDER_WAY}, {@code MISSION_ARCHIVED} when it already was, {@code UNKNOWN_MISSION}); null when
     *     archived
     */
    public record ArchiveOutcome(String mission, boolean archived, String code) {}

    /** The closed missions the director can archive now, oldest first: what "Archive all closed missions" counts. */
    @GET
    @Path("/missions/archivable")
    public List<MissionSummary> archivable() {
        return office.query(c -> c.missions().all().stream()
                .filter(m -> !m.archived())
                .filter(m -> c.missions()
                        .refusalToArchive(office.context(Actor.director(), m.origin()), m.id())
                        .isEmpty())
                .map(m -> MissionSummary.of(c, m))
                .toList());
    }

    /**
     * Archives the missions the director confirmed, one {@code MissionArchived} each, in one command: each is checked
     * again under the office's lock, so one that a session or a retrospective took in between is left out with its
     * code, never archived.
     */
    @POST
    @Path("/missions/archive")
    @Consumes(MediaType.APPLICATION_JSON)
    public List<ArchiveOutcome> archiveMany(ArchiveRequest request) {
        if (request == null || request.missions() == null || request.missions().isEmpty()) {
            throw RefusedException.invalid("MISSING_FIELDS", "missions is expected");
        }
        if (request.missions().size() > ARCHIVE_MAX) {
            throw RefusedException.invalid("INVALID_ARGUMENT", "at most " + ARCHIVE_MAX + " missions at a time");
        }
        List<MissionId> ids = request.missions().stream()
                .map(id -> parse(() -> new MissionId(id)))
                .toList();
        List<ArchiveOutcome> outcomes = new ArrayList<>();
        office.execute(c -> {
            List<EventEnvelope> events = new ArrayList<>();
            for (MissionId m : ids) outcomes.add(archiveOne(c, m, events));
            return events;
        });
        return List.copyOf(outcomes);
    }

    /** One mission of a bulk archive, checked first: a refusal is its outcome, never a failure of the whole command. */
    private ArchiveOutcome archiveOne(Company c, MissionId m, List<EventEnvelope> events) {
        Mission found = c.missions().find(m).orElse(null);
        if (found == null) return new ArchiveOutcome(m.value(), false, Refusal.UNKNOWN_MISSION.name());
        if (found.archived()) return new ArchiveOutcome(m.value(), false, Refusal.MISSION_ARCHIVED.name());
        CommandContext ctx = office.context(Actor.director(), found.origin());
        Optional<Refused> refused = c.missions().refusalToArchive(ctx, m);
        if (refused.isPresent())
            return new ArchiveOutcome(m.value(), false, refused.get().code());
        events.addAll(c.missions().archive(ctx, m));
        return new ArchiveOutcome(m.value(), true, null);
    }

    /**
     * Puts a closed mission away (ADR-0054): out of the board and the lists, kept in the journal with its worktrees.
     * Refused while it is open ({@code MISSION_STILL_OPEN}), while a session of it runs ({@code SESSION_RUNNING}) or
     * while its retrospective is under way ({@code RETRO_UNDER_WAY}).
     */
    @POST
    @Path("/missions/{id}/archived")
    public MissionView archive(@PathParam("id") String id) {
        MissionId m = parse(() -> new MissionId(id));
        office.execute(c -> c.missions().archive(asDirector(office, m), m));
        return missionView(office, m);
    }

    /** Brings an archived mission back to the lists, its status and outcome unchanged. */
    @DELETE
    @Path("/missions/{id}/archived")
    public MissionView unarchive(@PathParam("id") String id) {
        MissionId m = parse(() -> new MissionId(id));
        office.execute(c -> c.missions().unarchive(asDirector(office, m), m));
        return missionView(office, m);
    }
}
