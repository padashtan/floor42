package dev.floor42.server.api;

import dev.floor42.domain.MissionId;
import dev.floor42.server.app.DirectorFileReader;
import dev.floor42.server.app.DirectorFileReader.DirectorFileView;
import dev.floor42.server.app.DirectorFiles;
import dev.floor42.server.app.DirectorFiles.MergeResult;
import dev.floor42.server.app.DirectorFiles.ProjectState;
import dev.floor42.server.app.DirectorFiles.Upload;
import dev.floor42.server.app.RefusedException;
import dev.floor42.server.security.LocalAccessGuard;
import dev.floor42.server.security.SessionRegistry;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

/**
 * The director's files for a mission: given from the browser or put in the project folder, committed on the director's
 * branch, then merged into the agents' worktrees.
 */
@Path("/api/missions/{id}/files")
@Produces(MediaType.APPLICATION_JSON)
public class DirectorFilesResource {

    /** @param folder where in the project, empty for its root */
    public record Gift(String folder, @NoNullItems List<Upload> files) {
        public Gift {
            files = files == null ? null : Collections.unmodifiableList(new ArrayList<>(files));
        }
    }

    /** @param paths the pending paths the director confirmed, as {@code GET .../files} lists them */
    public record Confirmed(@NoNullItems List<String> paths) {
        public Confirmed {
            paths = paths == null ? null : Collections.unmodifiableList(new ArrayList<>(paths));
        }
    }

    @Inject
    DirectorFiles files;

    @Inject
    DirectorFileReader reader;

    @Inject
    CurrentVertxRequest request;

    @GET
    public ProjectState state(@PathParam("id") String id) {
        return files.state(mission(id));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public ProjectState give(@PathParam("id") String id, Gift gift) {
        return files.give(mission(id), gift == null ? null : gift.folder(), gift == null ? null : gift.files());
    }

    /** Commits only the paths the director confirmed: a file nobody chose (a stray .env) is never swept in. */
    @POST
    @Path("/commit")
    @Consumes(MediaType.APPLICATION_JSON)
    public ProjectState commitPending(@PathParam("id") String id, Confirmed confirmed) {
        return files.commitPending(mission(id), confirmed == null ? null : confirmed.paths());
    }

    @POST
    @Path("/update-agents")
    public List<MergeResult> updateAgents(@PathParam("id") String id) {
        return files.updateAgents(mission(id));
    }

    /** One pending file as the viewer panel needs it first: its kind, its size, the view caps (ADR-0051). */
    @GET
    @Path("/entry")
    public DirectorFileView entry(
            @PathParam("id") String id,
            @Parameter(
                            description =
                                    "The file path inside the director's project, relative with forward slashes; required.")
                    @QueryParam("path")
                    String path) {
        refuseANarrowScope();
        return reader.entry(mission(id), path);
    }

    /** The query of {@link #file} and {@link #fileHead}. */
    public record FileQuery(
            @Parameter(
                    description =
                            "The file path inside the director's project, relative with forward slashes; required.")
            @QueryParam("path")
            String path,

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

    /**
     * One pending file of the director's project: shown under the same rules as a deliverable (the type from its
     * bytes, a sandbox, {@code nosniff}), or downloaded as an attachment.
     */
    @GET
    @Path("/file")
    @Produces(MediaType.WILDCARD)
    public Response file(@PathParam("id") String id, @BeanParam FileQuery q) {
        refuseANarrowScope();
        return FileResponses.of(reader.file(mission(id), q.path()), q.download(), q.check());
    }

    /** The headers of {@link #file}, without its body: the file checked is released at once. */
    @HEAD
    @Path("/file")
    @Produces(MediaType.WILDCARD)
    public Response fileHead(@PathParam("id") String id, @BeanParam FileQuery q) {
        refuseANarrowScope();
        return FileResponses.head(reader.file(mission(id), q.path()), q.download(), q.check());
    }

    /**
     * The director's checkout holds more than what a mission delivered (a stray {@code .env}): its bytes go only to the
     * director's browser or a token of scope {@code all}, never to a read-only script token.
     */
    private void refuseANarrowScope() {
        if (LocalAccessGuard.scopeOf(request.getCurrent()) == SessionRegistry.Scope.ALL) return;
        throw new RefusedException(403, "SCOPE", "Reading the director's own files needs a token of scope all");
    }

    private static MissionId mission(String id) {
        try {
            return new MissionId(id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(e.getMessage(), e);
        }
    }
}
