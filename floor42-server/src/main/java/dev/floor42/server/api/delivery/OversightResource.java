package dev.floor42.server.api.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.app.core.RefusedException;
import dev.floor42.server.app.delivery.OversightReport;
import dev.floor42.server.app.delivery.OversightReports;
import dev.floor42.server.security.LogSafe;
import dev.floor42.server.security.SessionToken;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.logging.Logger;

/**
 * The human oversight report (ADR-0075), for the director only: it sits behind the session token agents never receive,
 * the shell guard refuses a command naming this route, and Floor42's MCP server offers agents no such tool. Read only,
 * journals nothing. Always masked: no query parameter changes that.
 */
@Path("/api/missions/{id}/oversight-report")
public class OversightResource {

    private static final Logger LOG = Logger.getLogger(OversightResource.class);

    static final String MARKDOWN = "text/markdown;charset=utf-8";

    @Inject
    OversightReports reports;

    @Inject
    ObjectMapper json;

    @Inject
    SessionToken token;

    @GET
    @Produces({MediaType.APPLICATION_JSON, "text/markdown"})
    @APIResponse(
            responseCode = "200",
            description = "The mission's oversight report, always masked",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = OversightReport.class)))
    @RunOnVirtualThread
    public Response report(
            @PathParam("id") String id,
            @Parameter(description = "json (the default) or md, the same report as a Markdown file.")
                    @QueryParam("format")
                    @DefaultValue("json")
                    String format,
            @Parameter(description = "True to answer as a file to save (Content-Disposition attachment).")
                    @QueryParam("download")
                    boolean download) {
        if (!"json".equals(format) && !"md".equals(format))
            throw RefusedException.invalid("INVALID_ARGUMENT", "format is json or md");
        boolean md = "md".equals(format);
        String body = md ? reports.markdown(id) : jsonOf(reports.build(id));
        body = failClosed(body, token.value());
        LOG.infof("Oversight report of %s read by the director", LogSafe.of(id));
        Response.ResponseBuilder r =
                Response.ok(body, md ? MARKDOWN : MediaType.APPLICATION_JSON).header("Cache-Control", "no-store");
        if (download)
            r.header(
                    "Content-Disposition",
                    "attachment; filename=\"floor42-oversight-" + id + (md ? ".md" : ".json") + "\"");
        return r.build();
    }

    private String jsonOf(OversightReport report) {
        try {
            return json.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write the oversight report", e);
        }
    }

    /** The report must never leave holding the session token: refuse with an empty 500 rather than return it. */
    static String failClosed(String body, String token) {
        if (token != null && !token.isEmpty() && body.contains(token))
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR).build());
        return body;
    }
}
