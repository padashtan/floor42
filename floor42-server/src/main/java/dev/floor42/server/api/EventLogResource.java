package dev.floor42.server.api;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.server.api.JournalViews.JournalEntryView;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.journal.Journal;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

/** The latest journal rows, and the event log's history before its live stream. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class EventLogResource {

    private final OfficeService office;
    private final Journal journal;

    public EventLogResource(OfficeService office, Journal journal) {
        this.office = office;
        this.journal = journal;
    }

    @GET
    @Path("/journal")
    public List<JournalEntryView> journal(
            @Parameter(description = "How many of the latest journal entries to return (1 to 500, default 50).")
                    @QueryParam("limit")
                    @DefaultValue("50")
                    int limit) {
        return journal.latest(Math.clamp(limit, 1, 500)).stream()
                .map(JournalEntryView::of)
                .toList();
    }

    /** The latest events as the event log shows them, oldest first: the log's history before the live stream. */
    @GET
    @Path("/events")
    public List<EventView> events(
            @Parameter(description = "How many of the latest events to return (1 to 200, default 40).")
                    @QueryParam("limit")
                    @DefaultValue("40")
                    int limit) {
        List<EventEnvelope> latest = journal.latest(Math.clamp(limit, 1, 200)).stream()
                .map(Journal.Entry::envelope)
                .toList();
        return office.query(c -> latest.stream().map(e -> EventView.of(c, e)).toList());
    }
}
