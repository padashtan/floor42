package dev.floor42.server.api;

import static dev.floor42.server.api.DirectorRequests.parse;

import dev.floor42.domain.MissionId;
import dev.floor42.server.iam.IdentityAudit;
import dev.floor42.server.journal.Journal;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

/** A mission's identity events (issued, refused, brokered), one JSON line each, read back from the journal. */
@Path("/api/missions/{id}/identity-audit")
public class IdentityAuditResource {

    private final Journal journal;

    public IdentityAuditResource(Journal journal) {
        this.journal = journal;
    }

    @GET
    @Produces("application/x-ndjson")
    public Response audit(@PathParam("id") String id) {
        MissionId m = parse(() -> new MissionId(id));
        String body = IdentityAudit.ndjson(IdentityAudit.lines(m, journal.readAll()));
        return Response.ok(body, "application/x-ndjson")
                .header("Content-Disposition", "attachment; filename=\"" + id + "-identity-audit.ndjson\"")
                .build();
    }
}
