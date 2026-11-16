package dev.floor42.server.api.journal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.security.SessionToken;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
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
 * The mission proof dossier (ADR-0071), for the director only: it sits behind the session token agents never receive,
 * the shell guard refuses a command naming this route, and Floor42's MCP server offers agents no such tool. Read only,
 * journals nothing. It is always masked: no query parameter changes that.
 */
@Path("/api/missions/{id}/proof-dossier")
public class ProofDossierResource {

    private static final Logger LOG = Logger.getLogger(ProofDossierResource.class);

    @Inject
    ProofDossiers dossiers;

    @Inject
    ObjectMapper json;

    @Inject
    SessionToken token;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(
            responseCode = "200",
            description = "The mission's proof dossier, always masked",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ProofDossier.class)))
    @RunOnVirtualThread
    public Response dossier(
            @PathParam("id") String id,
            @Parameter(description = "True to answer as a file to save (Content-Disposition attachment).")
                    @QueryParam("download")
                    boolean download) {
        ProofDossier d = dossiers.build(id);
        String body;
        try {
            body = failClosed(json.writeValueAsString(d), token.value());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write the proof dossier", e);
        }
        LOG.infof("Proof dossier of %s read by the director", id);
        Response.ResponseBuilder r =
                Response.ok(body, MediaType.APPLICATION_JSON).header("Cache-Control", "no-store");
        if (download) r.header("Content-Disposition", "attachment; filename=\"floor42-dossier-" + id + ".json\"");
        return r.build();
    }

    /** The dossier must never leave holding the session token: refuse with an empty 500 rather than return it. */
    static String failClosed(String body, String token) {
        if (token != null && !token.isEmpty() && body.contains(token))
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR).build());
        return body;
    }
}
