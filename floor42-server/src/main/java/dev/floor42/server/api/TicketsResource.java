package dev.floor42.server.api;

import static dev.floor42.server.api.DirectorRequests.asDirector;
import static dev.floor42.server.api.DirectorRequests.blank;
import static dev.floor42.server.api.DirectorRequests.missionOf;
import static dev.floor42.server.api.DirectorRequests.missionView;
import static dev.floor42.server.api.DirectorRequests.parse;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import dev.floor42.server.api.DirectorRequests.Reason;
import dev.floor42.server.api.MissionViews.MissionView;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.RefusedException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The director's say on a mission's tickets: approving, rewriting, dropping. Protected by {@code LocalAccessGuard}. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class TicketsResource {

    /** @param risks graph mode (ADR-0062): the director's risk, {@code low} or {@code high}, for nodes by ticket id */
    public record TicketSelection(@NoNullItems List<String> tickets, Map<String, String> risks) {
        public TicketSelection {
            risks = risks == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(risks));
            tickets = tickets == null ? null : Collections.unmodifiableList(new ArrayList<>(tickets));
        }

        public TicketSelection(List<String> tickets) {
            this(tickets, null);
        }
    }

    public record TicketEdit(
            String title, String description, @NoNullItems List<String> acceptanceCriteria) {
        public TicketEdit {
            acceptanceCriteria = acceptanceCriteria == null
                    ? null
                    : Collections.unmodifiableList(new ArrayList<>(acceptanceCriteria));
        }
    }

    private final OfficeService office;

    public TicketsResource(OfficeService office) {
        this.office = office;
    }

    /** The director approves proposed tickets: each becomes work to do, one at a time, in order (ADR-0017). */
    @POST
    @Path("/missions/{id}/tickets/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    public MissionView approveTickets(@PathParam("id") String id, TicketSelection selection) {
        MissionId m = parse(() -> new MissionId(id));
        List<TicketId> tickets = selection == null || selection.tickets() == null
                ? List.of()
                : selection.tickets().stream()
                        .map(t -> parse(() -> new TicketId(t)))
                        .toList();
        Map<TicketId, String> risks = new LinkedHashMap<>();
        if (selection != null && selection.risks() != null) {
            selection.risks().forEach((t, risk) -> risks.put(parse(() -> new TicketId(t)), risk));
        }
        office.execute(c -> c.tickets().approve(asDirector(office, m), m, tickets, risks));
        return missionView(office, m);
    }

    /** The director rewrites a ticket before work starts on it. */
    @PUT
    @Path("/tickets/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public MissionView editTicket(@PathParam("id") String id, TicketEdit edit) {
        TicketId t = parse(() -> new TicketId(id));
        MissionId m = missionOf(office, t);
        if (edit == null) throw RefusedException.invalid("MISSING_FIELDS", "title is required");
        office.execute(c -> c.tickets()
                .edit(asDirector(office, m), t, edit.title(), edit.description(), edit.acceptanceCriteria()));
        return missionView(office, m);
    }

    /** The director drops a ticket: it is cancelled, with the reason, and leaves the board. */
    @POST
    @Path("/tickets/{id}/drop")
    @Consumes(MediaType.APPLICATION_JSON)
    public MissionView dropTicket(@PathParam("id") String id, Reason reason) {
        TicketId t = parse(() -> new TicketId(id));
        MissionId m = missionOf(office, t);
        String why = reason == null || blank(reason.reason()) ? "dropped by the director" : reason.reason();
        office.execute(c -> c.tickets().cancel(asDirector(office, m), t, why));
        return missionView(office, m);
    }
}
