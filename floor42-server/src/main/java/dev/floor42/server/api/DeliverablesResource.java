package dev.floor42.server.api;

import dev.floor42.domain.MissionId;
import dev.floor42.server.app.Deliverables;
import dev.floor42.server.app.Delivered.Blob;
import dev.floor42.server.app.Delivered.DeliverablesView;
import dev.floor42.server.app.Delivered.Source;
import dev.floor42.server.app.RefusedException;
import jakarta.inject.Inject;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.util.Locale;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

/**
 * The deliverables of a mission, open or closed (ADR-0037): what it delivered, one file to view or download, the whole
 * mission as a ZIP, and the worktree opened in the director's file explorer. Director only, like every route under
 * {@code /api} (an agent's session credential gets 403); reading is never journaled.
 *
 * <p>The daemon's origin never runs what an agent wrote: a viewed file is sent with a sandboxing Content-Security-Policy
 * and {@code nosniff}; HTML and scripts go out as plain text, an SVG only as an image to put in an {@code <img>}
 * (ADR-0051); anything that is not text, an image or a PDF is a download only.
 */
@Path("/api/missions/{id}/deliverables")
public class DeliverablesResource {

    public record OpenFolderRequest(String agent) {}

    /** The query of {@link #file} and {@link #fileHead}. */
    public record FileQuery(
            @Parameter(description = "The agent whose branch or worktree holds the file; required.")
            @QueryParam("agent")
            String agent,

            @Parameter(description = "The file path inside the worktree, relative with forward slashes; required.")
            @QueryParam("path")
            String path,

            @Parameter(
                    description =
                            "COMMITTED (read at the branch tip) or WORKTREE (a file not committed yet); required.")
            @QueryParam("source")
            String source,

            @Parameter(description = "true sends the file as an attachment instead of showing it (default false).")
            @QueryParam("download")
            @DefaultValue("false")
            boolean download,

            @Parameter(
                    description =
                            "true answers whether the viewer may show the file, in a JSON body, instead of the file (default false).")
            @QueryParam("check")
            @DefaultValue("false")
            boolean check) {}

    @Inject
    Deliverables deliverables;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public DeliverablesView view(
            @PathParam("id") String id,
            @Parameter(description = "true also lists the files the repository ignores (default false).")
                    @QueryParam("ignored")
                    @DefaultValue("false")
                    boolean ignored) {
        return deliverables.view(new MissionId(id), ignored);
    }

    /** One file: shown when {@code download} is false and the file is text, an image or a PDF; downloaded otherwise. */
    @GET
    @Path("/file")
    @Produces(MediaType.WILDCARD)
    public Response file(@PathParam("id") String id, @BeanParam FileQuery q) {
        return FileResponses.of(blob(id, q), q.download(), q.check());
    }

    /** The headers of {@link #file}, without its body: the file checked is released at once (ADR-0051). */
    @HEAD
    @Path("/file")
    @Produces(MediaType.WILDCARD)
    public Response fileHead(@PathParam("id") String id, @BeanParam FileQuery q) {
        return FileResponses.head(blob(id, q), q.download(), q.check());
    }

    private Blob blob(String id, FileQuery q) {
        return deliverables.file(new MissionId(id), q.agent(), q.path(), parse(q.source()));
    }

    /** Everything the mission delivered as one ZIP, streamed; files past the cap are listed in MANIFEST.txt. */
    @GET
    @Path("/zip")
    @Produces("application/zip")
    public Response zip(
            @PathParam("id") String id,
            @Parameter(description = "true also lists the files the repository ignores (default false).")
                    @QueryParam("ignored")
                    @DefaultValue("false")
                    boolean ignored) {
        MissionId mission = new MissionId(id);
        // The mission must exist before the first byte goes out: a 404 cannot follow a 200.
        deliverables.view(mission, false);
        StreamingOutput body = out -> deliverables.zip(mission, ignored, out);
        return Response.ok(body, "application/zip")
                .header(
                        "Content-Disposition",
                        FileResponses.disposition("attachment", "floor42-" + id + "-deliverables.zip"))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", FileResponses.SANDBOX)
                .header("Cache-Control", "no-store")
                .build();
    }

    /** Opens the worktree in the file explorer of the machine the daemon runs on: the director's own. */
    @POST
    @Path("/open-folder")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response openFolder(@PathParam("id") String id, OpenFolderRequest request) {
        deliverables.openFolder(new MissionId(id), request == null ? null : request.agent());
        return Response.noContent().build();
    }

    private static Source parse(String source) {
        if (source == null) throw RefusedException.invalid("INVALID_ARGUMENT", "source is COMMITTED or WORKTREE");
        try {
            return Source.valueOf(source.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw RefusedException.invalid("INVALID_ARGUMENT", "source is COMMITTED or WORKTREE", e);
        }
    }
}
