package dev.floor42.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.Actor;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Origin;
import dev.floor42.domain.workflow.CompanyWorkflow;
import dev.floor42.domain.workflow.StepRefused;
import dev.floor42.domain.workflow.StepText;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.WorkflowDocument;
import dev.floor42.domain.workflow.WorkflowFile;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.RefusedException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The company's workflows (ADR-0050): the director writes them in the list editor, the planner may propose one. A
 * mission pins a copy when it is created: nothing here changes a mission already created.
 */
@Path("/api/workflows")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkflowsResource {

    /**
     * @param steps in Floor42's order; a step's settings beside its name, as the document holds them: a key the
     *     reader does not know ({@code autoAccept} included) is refused with its reason, never dropped
     * @param replace true to replace a workflow of the same name
     * @param proposal the planner's proposal this saves (closed with it); null for none
     */
    public record SaveWorkflow(
            String name, @NoNullItems List<Map<String, Object>> steps, Boolean replace, Integer proposal) {
        public SaveWorkflow {
            steps = steps == null ? null : Collections.unmodifiableList(new ArrayList<>(steps));
        }
    }

    public record RemoveWorkflow(String name) {}

    /** @param text the file as read from disk, whatever it holds: it is validated here, never trusted */
    public record ImportWorkflow(String text) {}

    private final OfficeService office;
    private final ObjectMapper json;

    WorkflowsResource(OfficeService office, ObjectMapper json) {
        this.office = office;
        this.json = json;
    }

    @GET
    public WorkflowViews.WorkflowsView list() {
        return office.query(WorkflowViews.WorkflowsView::of);
    }

    /** Read exactly as a pinned document is read: what the reader refuses is refused here, with its reason. */
    @POST
    public WorkflowViews.WorkflowsView save(com.fasterxml.jackson.databind.JsonNode body) {
        SaveWorkflow request = request(body);
        if (request == null || request.steps() == null) {
            throw RefusedException.invalid("MISSING_FIELDS", "name and steps are required");
        }
        Workflow w;
        try {
            w = WorkflowDocument.readCurrent(document(request));
        } catch (StepRefused e) {
            // The step's own code, which the editor shows under the step's row.
            throw RefusedException.invalid(e.code(), e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw RefusedException.invalid("INVALID_WORKFLOW", e.getMessage(), e);
        }
        office.execute(
                c -> c.workflows().save(director(), w, Boolean.TRUE.equals(request.replace()), request.proposal()));
        return list();
    }

    @POST
    @Path("/remove")
    public WorkflowViews.WorkflowsView remove(RemoveWorkflow request) {
        if (request == null || request.name() == null)
            throw RefusedException.invalid("MISSING_NAME", "name is required");
        office.execute(c -> c.workflows().remove(director(), request.name()));
        return list();
    }

    @POST
    @Path("/proposals/{number}/dismiss")
    public WorkflowViews.WorkflowsView dismiss(@PathParam("number") int number) {
        office.execute(c -> c.workflows().dismissProposal(director(), number));
        return list();
    }

    /** The workflow as a file to keep or share (ADR-0052): the document as saved, in the export format, as written. */
    @GET
    @Path("/export")
    @APIResponse(
            responseCode = "200",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = WorkflowViews.WorkflowFileView.class)))
    public String export(
            @Parameter(description = "The name of the company workflow to export.") @QueryParam("name") String name) {
        return office.query(c -> {
            CompanyWorkflow w = c.workflows()
                    .named(name)
                    .orElseThrow(() -> new DomainException("UNKNOWN_WORKFLOW", "No workflow named \"" + name + "\""));
            Workflow readable = w.workflow()
                    .orElseThrow(
                            () -> new DomainException("WORKFLOW_UNREADABLE", "This build cannot read that workflow"));
            return WorkflowFile.export(WorkflowDocument.write(readable), WorkflowDocument.versionOf(readable));
        });
    }

    /** Validates a file in full and answers its workflow or its problems by code; nothing is saved. */
    @POST
    @Path("/import")
    @org.eclipse.microprofile.openapi.annotations.parameters.RequestBody(
            content =
                    @org.eclipse.microprofile.openapi.annotations.media.Content(
                            mediaType = "application/json",
                            schema =
                                    @org.eclipse.microprofile.openapi.annotations.media.Schema(
                                            implementation = ImportWorkflow.class)))
    public WorkflowViews.ImportView importFile(
            @jakarta.ws.rs.HeaderParam("Content-Length") Long length, java.io.InputStream body) {
        // The cap is enforced on the stream, before anything is parsed: six times the file's cap leaves room for the
        // JSON escaping of its text (a control character takes six bytes, as \u0001), and the domain then holds the
        // file itself to its own cap in bytes.
        byte[] bytes = BoundedBody.read(body, length, IMPORT_BODY_LIMIT, "IMPORT_TOO_LARGE");
        try {
            com.fasterxml.jackson.databind.JsonNode text = json.readTree(bytes).path("text");
            return WorkflowViews.ImportView.of(WorkflowFile.read(text.isTextual() ? text.asText() : null));
        } catch (java.io.IOException e) {
            throw RefusedException.invalid("INVALID_WORKFLOW", "The request is not JSON", e);
        }
    }

    /**
     * What an import request may weigh: a file at the cap made only of characters JSON escapes in six bytes, and the
     * request's own envelope.
     */
    static final int IMPORT_BODY_LIMIT = 6 * WorkflowFile.MAX_BYTES + 64;

    private static final java.util.Set<String> TEXTS = java.util.Set.of("title", "does", "why", "handsOver");

    private static final java.util.Set<String> FIELDS = java.util.Set.of("name", "steps", "replace", "proposal");

    /** The body as a request; a field the route does not know is refused by name, never dropped. */
    private SaveWorkflow request(com.fasterxml.jackson.databind.JsonNode body) {
        if (body == null || !body.isObject()) return null;
        for (java.util.Iterator<String> names = body.fieldNames(); names.hasNext(); ) {
            String field = names.next();
            if (!FIELDS.contains(field))
                throw RefusedException.invalid("INVALID_WORKFLOW", "Unknown field \"" + field + "\"");
        }
        try {
            return json.treeToValue(body, SaveWorkflow.class);
        } catch (JsonProcessingException e) {
            throw RefusedException.invalid(
                    "INVALID_WORKFLOW", "The request is not a workflow: " + e.getOriginalMessage(), e);
        }
    }

    private dev.floor42.domain.CommandContext director() {
        return office.context(Actor.director(), Origin.REAL);
    }

    /** The request as a document of the current schema, every key kept for the reader; a null value means not given. */
    private String document(SaveWorkflow r) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Map<String, Object> s : r.steps()) {
            if (s == null) throw RefusedException.invalid("INVALID_WORKFLOW", "A step is empty");
            Map<String, Object> step = new LinkedHashMap<>();
            // A step's texts are written NFKC (ADR-0052): the reader then refuses what normalising cannot clean.
            s.forEach((k, v) -> {
                if (v != null)
                    step.put(k, v instanceof String text && TEXTS.contains(k) ? StepText.normalised(text) : v);
            });
            steps.add(step);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("name", r.name() == null ? "" : r.name());
        doc.put("steps", steps);
        try {
            return json.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A map of strings and numbers is always written", e);
        }
    }
}
