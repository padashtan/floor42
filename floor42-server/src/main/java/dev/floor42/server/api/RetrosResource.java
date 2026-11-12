package dev.floor42.server.api;

import static dev.floor42.server.api.DirectorRequests.missionView;
import static dev.floor42.server.api.DirectorRequests.parse;

import dev.floor42.domain.MissionId;
import dev.floor42.server.api.MissionViews.MissionView;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.RetroConvening;
import dev.floor42.server.app.Retrospectives;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** A mission's end-of-mission retrospective (ADR-0020), as the director follows or closes it. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class RetrosResource {

    /** When the retrospective's next turn runs, if it waits for the plan's usage window to reset; null otherwise. */
    public record RetroWait(String until) {}

    /**
     * Whether the director can convene the retrospective now (ADR-0054); {@code code} says why not: {@code
     * RETRO_DISABLED}, {@code RETRO_NOT_IN_WORKFLOW}, {@code NO_PARTICIPANTS}, {@code DUPLICATE_RETRO} (it has one),
     * {@code MISSION_NOT_FINISHED}, {@code MISSION_ARCHIVED}, {@code SIMULATED_MISSION}; null when it can.
     */
    public record RetroOffer(boolean available, String code) {}

    private final OfficeService office;
    private final Retrospectives retros;
    private final RetroConvening convening;

    public RetrosResource(OfficeService office, Retrospectives retros, RetroConvening convening) {
        this.office = office;
        this.retros = retros;
        this.convening = convening;
    }

    @GET
    @Path("/missions/{id}/retro/offer")
    public RetroOffer retroOffer(@PathParam("id") String id) {
        MissionId m = parse(() -> new MissionId(id));
        String code = convening.refusal(m).orElse(null);
        return new RetroOffer(code == null, code);
    }

    /**
     * The director convenes the retrospective of a closed mission that has none (ADR-0054): its paid sessions start
     * now, never on a restore. Refused with the offer's code.
     */
    @POST
    @Path("/missions/{id}/retro/convene")
    public MissionView convene(@PathParam("id") String id) {
        MissionId m = parse(() -> new MissionId(id));
        convening.convene(m);
        return missionView(office, m);
    }

    @GET
    @Path("/missions/{id}/retro/wait")
    public RetroWait retroWait(@PathParam("id") String id) {
        MissionId m = parse(() -> new MissionId(id));
        return new RetroWait(retros.waitingUntil(m).map(Object::toString).orElse(null));
    }

    /** Closes the retrospective now: the turns still due are recorded as not heard, no lesson is drawn. */
    @POST
    @Path("/missions/{id}/retro/close")
    public MissionView closeRetro(@PathParam("id") String id) {
        MissionId m = parse(() -> new MissionId(id));
        retros.closeNow(m);
        return missionView(office, m);
    }
}
