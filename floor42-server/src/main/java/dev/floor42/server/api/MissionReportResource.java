package dev.floor42.server.api;

import dev.floor42.domain.MissionId;
import dev.floor42.server.app.MissionReports;
import dev.floor42.server.app.MissionReports.MissionReport;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The end-of-mission report (ADR-0039): read from the journal and git when asked, never stored; nothing is changed.
 */
@Path("/api/missions/{id}/report")
@Produces(MediaType.APPLICATION_JSON)
public class MissionReportResource {

    @Inject
    MissionReports reports;

    @GET
    @RunOnVirtualThread
    public MissionReport report(@PathParam("id") String id) {
        MissionId m;
        try {
            m = new MissionId(id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(e.getMessage(), e);
        }
        return reports.report(m);
    }
}
