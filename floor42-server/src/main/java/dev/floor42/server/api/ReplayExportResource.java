package dev.floor42.server.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * The director exports one mission as a replay to share (ADR-0035): first a preview of what the file will hold and
 * what was masked, then the file itself, only for the very content the preview showed (its digest). Both are routes
 * of the director's API, behind the session token agents never receive; Floor42's MCP server offers agents no such
 * tool, and the shell guard refuses a command that names this route.
 */
@Path("/api/missions/{id}/replay-export")
public class ReplayExportResource {

    private static final Logger LOG = Logger.getLogger(ReplayExportResource.class);

    /** How many replacements of one kind ({@code SECRET}, {@code PATH}, {@code TOKEN}, ...). */
    public record MaskCount(String kind, int count) {}

    /**
     * One place where something was masked or left out.
     *
     * @param event the event's number in the replay, 0 for the mission itself
     */
    public record MaskedItem(int event, String type, String field, String kind) {}

    /**
     * What the file will hold.
     *
     * @param agents the names of the agents seen in the replay
     * @param outputs events that carry a tool or check output (clipped)
     * @param costs sessions whose cost the journal holds (Claude Code's estimate); no other cost is shown
     * @param masked every replacement and omission, by kind
     * @param details where they were, the first 200
     * @param detailsTruncated more places than listed
     * @param digest of the content; the export must name it, so the file is the one previewed
     */
    public record ReplayExportPreview(
            String mission,
            String title,
            String origin,
            String version,
            int events,
            int real,
            int simulated,
            List<String> agents,
            int tickets,
            int outputs,
            int costs,
            List<MaskCount> masked,
            List<MaskedItem> details,
            boolean detailsTruncated,
            String digest) {
        public ReplayExportPreview {
            agents = agents == null ? null : Collections.unmodifiableList(new ArrayList<>(agents));
            details = details == null ? null : Collections.unmodifiableList(new ArrayList<>(details));
            masked = masked == null ? null : Collections.unmodifiableList(new ArrayList<>(masked));
        }
    }

    /**
     * @param digest the preview's digest: the director confirmed this content
     * @param language the file's language, {@code en} or {@code fr}
     * @param labels the player's words in that language, from the screen's catalogue
     */
    public record ReplayExportRequest(String digest, String language, Map<String, String> labels) {
        public ReplayExportRequest {
            labels = labels == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(labels));
        }
    }

    @Inject
    ReplayExports exports;

    @GET
    @Path("/preview")
    @Produces(MediaType.APPLICATION_JSON)
    public ReplayExportPreview preview(@PathParam("id") String id) {
        return exports.preview(exports.build(id));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_HTML)
    public Response export(@PathParam("id") String id, ReplayExportRequest request) {
        if (request == null || request.digest() == null || request.digest().isBlank()) {
            throw problem(
                    Response.Status.BAD_REQUEST,
                    "REPLAY_NOT_PREVIEWED",
                    "Preview the replay first and confirm it: the export names the preview's digest");
        }
        ReplayExports.Built built = exports.build(id);
        if (!built.digest().equals(request.digest())) {
            throw problem(
                    Response.Status.CONFLICT,
                    "REPLAY_CHANGED",
                    "The mission changed since the preview: preview it again before exporting");
        }
        String html = exports.html(built, request.language(), request.labels(), Instant.now());
        LOG.infof(
                "Replay of %s exported by the director (%d events)",
                id, built.body().events().size());
        return Response.ok(html, "text/html; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"floor42-replay-" + id + ".html\"")
                .header("Cache-Control", "no-store")
                .build();
    }

    private static WebApplicationException problem(Response.Status status, String code, String message) {
        return new WebApplicationException(Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorMappers.Problem(code, message))
                .build());
    }
}
