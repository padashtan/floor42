package dev.floor42.server.api;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.Meeting;
import dev.floor42.domain.MeetingNoteUse;
import dev.floor42.domain.MeetingPlace;
import dev.floor42.domain.MissionId;
import dev.floor42.server.api.MeetingViews.Invitation;
import dev.floor42.server.api.MeetingViews.MeetingView;
import dev.floor42.server.app.DirectorMeetings;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.runtime.RunningSessions;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

/**
 * The director's meetings at the table (ADR-0047): who is called about which mission, what the director says, the
 * close. Director only: an agent's credential is refused before it gets here.
 */
@Path("/api/meetings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MeetingsResource {

    /**
     * @param agenda optional
     * @param place optional: {@code TABLE} (default) or {@code CONFERENCE}, the table the director chose (ADR-0049)
     */
    public record NewMeeting(String mission, @NoNullItems List<String> attendees, String agenda, String place) {
        public NewMeeting {
            attendees = attendees == null ? null : Collections.unmodifiableList(new ArrayList<>(attendees));
        }
    }

    /**
     * "Ask for their view" (ADR-0049): paid, each speaker one short read-only session, in this order.
     *
     * @param capUsd the most the whole meeting may be charged, in USD as Claude Code estimates it
     * @param question optional: what to ask this round; the agenda otherwise
     */
    public record NewViews(@NoNullItems List<String> speakers, Double capUsd, String question) {
        public NewViews {
            speakers = speakers == null ? null : Collections.unmodifiableList(new ArrayList<>(speakers));
        }
    }

    /**
     * @param attendee who it is said to; null for every attendee
     * @param use {@code NOTE_ONLY} (default) or {@code ALSO_DIRECTIVE}: also a directive on their ticket under way
     */
    public record NewMeetingNote(String attendee, String text, String use) {}

    private final OfficeService office;
    private final DirectorMeetings meetings;
    private final RunningSessions sessions;

    @Inject
    MeetingsResource(OfficeService office, DirectorMeetings meetings, RunningSessions sessions) {
        this.office = office;
        this.meetings = meetings;
        this.sessions = sessions;
    }

    /**
     * The meetings, newest first: the open ones are those with no {@code closedAt}. With {@code limit}, every open one
     * and the latest closed up to that many in all: the panel and the scene read it every few seconds (ADR-0049).
     */
    @GET
    public List<MeetingView> list(
            @Parameter(
                            description =
                                    "Most meetings to return: every open one, then the latest closed ones up to this many in all; all of them when absent.")
                    @QueryParam("limit")
                    Integer limit) {
        long most = limit == null ? Long.MAX_VALUE : Math.max(1, limit);
        return office.query(c -> {
            List<Meeting> newest = c.meetings().all().stream()
                    .sorted(Comparator.comparing(Meeting::calledAt).reversed())
                    .toList();
            List<Meeting> shown = new java.util.ArrayList<>(
                    newest.stream().filter(Meeting::open).toList());
            newest.stream()
                    .filter(m -> !m.open())
                    .limit(Math.max(0, most - shown.size()))
                    .forEach(shown::add);
            shown.sort(Comparator.comparing(Meeting::calledAt).reversed());
            return shown.stream().map(m -> MeetingView.of(c, m, sessions)).toList();
        });
    }

    /** The agents the panel proposes for a meeting about this mission: those working on it now. */
    @GET
    @Path("/invitation")
    public Invitation invitation(
            @Parameter(description = "The mission id (M-1) the invitation is about; required.") @QueryParam("mission")
                    String mission) {
        if (DirectorRequests.blank(mission)) throw new BadRequestException("mission is required");
        MissionId m = DirectorRequests.parse(() -> new MissionId(mission));
        return office.query(c -> {
            if (c.missions().find(m).isEmpty()) throw new NotFoundException("No mission " + m);
            return Invitation.of(c, m);
        });
    }

    /** Calls the meeting: the attendees stop at their next tool call and come to the table. */
    @POST
    public MeetingView call(NewMeeting request) {
        if (request == null || DirectorRequests.blank(request.mission()))
            throw new BadRequestException("mission is required");
        MissionId m = DirectorRequests.parse(() -> new MissionId(request.mission()));
        List<AgentId> who = request.attendees() == null
                ? List.of()
                : request.attendees().stream()
                        .map(a -> DirectorRequests.parse(() -> new AgentId(a)))
                        .toList();
        return view(meetings.call(m, who, request.agenda(), place(request.place())));
    }

    /** The director asks attendees for their view: the round starts, each speaker once seated. */
    @POST
    @Path("/{id}/views")
    public MeetingView askForViews(@PathParam("id") String id, NewViews request) {
        if (request == null || request.capUsd() == null) throw new BadRequestException("capUsd is required");
        List<AgentId> who = request.speakers() == null
                ? List.of()
                : request.speakers().stream()
                        .map(a -> DirectorRequests.parse(() -> new AgentId(a)))
                        .toList();
        meetings.askForViews(id, who, request.capUsd(), request.question());
        return view(id);
    }

    private static MeetingPlace place(String place) {
        if (DirectorRequests.blank(place)) return MeetingPlace.TABLE;
        try {
            return MeetingPlace.valueOf(place.strip());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("place is TABLE or CONFERENCE", e);
        }
    }

    @POST
    @Path("/{id}/notes")
    public MeetingView note(@PathParam("id") String id, NewMeetingNote request) {
        if (request == null) throw new BadRequestException("text is required");
        AgentId to = DirectorRequests.blank(request.attendee())
                ? null
                : DirectorRequests.parse(() -> new AgentId(request.attendee()));
        meetings.note(id, to, request.text(), use(request.use()));
        return view(id);
    }

    /** Closes the meeting: the attendees go back to their work, with what was said in their next brief. */
    @POST
    @Path("/{id}/close")
    public MeetingView close(@PathParam("id") String id) {
        meetings.close(id);
        return view(id);
    }

    private static MeetingNoteUse use(String use) {
        if (DirectorRequests.blank(use)) return MeetingNoteUse.NOTE_ONLY;
        try {
            return MeetingNoteUse.valueOf(use.strip());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("use is NOTE_ONLY or ALSO_DIRECTIVE", e);
        }
    }

    private MeetingView view(String id) {
        return office.query(c -> c.meetings()
                .find(id)
                .map(m -> MeetingView.of(c, m, sessions))
                .orElseThrow(() -> new NotFoundException("No meeting " + id)));
    }
}
