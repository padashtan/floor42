package dev.floor42.server.api;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Intervention;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.Workspace;
import dev.floor42.server.app.Checks;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.Workshop;
import dev.floor42.server.app.Workshop.Change;
import dev.floor42.server.app.WorkshopFiles;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

/**
 * The director's workshop (spec section 11, ADR-0013): see what agents changed, edit, direct, accept or reject. Every
 * write goes through Floor42, is confined to the agent's worktree and is recorded in the journal.
 */
@Path("/api/missions/{id}/workshop")
@Produces(MediaType.APPLICATION_JSON)
public class WorkshopResource {

    public record WorkspaceView(
            String agent,
            String path,
            String branch,
            String baseCommit,
            String head,
            List<Change> changes,
            List<String> editedByDirector,
            /** New build and cache artefacts (__pycache__, node_modules...) an acceptance leaves out, listed apart. */
            List<String> leftOut) {
        public WorkspaceView {
            changes = changes == null ? null : Collections.unmodifiableList(new ArrayList<>(changes));
            editedByDirector =
                    editedByDirector == null ? null : Collections.unmodifiableList(new ArrayList<>(editedByDirector));
            leftOut = leftOut == null ? null : Collections.unmodifiableList(new ArrayList<>(leftOut));
        }
    }

    public record InterventionView(
            String kind,
            String at,
            String author,
            String workspace,
            String ticket,
            String subject,
            String detail,
            List<String> files) {
        public InterventionView {
            files = files == null ? null : Collections.unmodifiableList(new ArrayList<>(files));
        }

        static InterventionView of(Intervention i) {
            return new InterventionView(
                    i.kind().name(),
                    i.at().toString(),
                    i.author().kind().name().toLowerCase() + ":" + i.author().id(),
                    i.workspace() == null ? null : i.workspace().value(),
                    i.ticket() == null ? null : i.ticket().value(),
                    i.subject(),
                    i.detail(),
                    i.files());
        }
    }

    public record WorkshopView(String mission, List<WorkspaceView> workspaces, List<InterventionView> interventions) {
        public WorkshopView {
            interventions = interventions == null ? null : Collections.unmodifiableList(new ArrayList<>(interventions));
            workspaces = workspaces == null ? null : Collections.unmodifiableList(new ArrayList<>(workspaces));
        }
    }

    public record FileView(String path, String content, boolean binary) {}

    /** @param omitted the whole diff only (no path): the changes left out of it, too large or past its bound */
    public record DiffView(String path, String diff, Integer omitted) {}

    public record Edit(String content, String note) {}

    public record Message(String message) {}

    /** @param paths the files to commit, each one of the pending changes; absent: all of them */
    public record Accept(String message, List<String> paths) {
        public Accept {
            paths = paths == null ? null : Collections.unmodifiableList(new ArrayList<>(paths));
        }
    }

    public record Directive(String ticket, String directive) {}

    @Inject
    OfficeService office;

    @Inject
    Workshop workshop;

    @Inject
    Checks checks;

    @Inject
    dev.floor42.server.app.WorkshopDiscard discard;

    @Inject
    dev.floor42.server.app.DeliverablesHome home;

    @GET
    public WorkshopView view(@PathParam("id") String id) {
        MissionId m = mission(id);
        // The director's edits are a live set of the workspace: read under the service lock, git outside it.
        record Space(Workspace workspace, List<String> edited) {}
        List<Space> spaces = office.query(c -> c.workshop().workspaces(m).stream()
                .map(w -> new Space(w, w.editedByDirector().stream().sorted().toList()))
                .toList());
        List<InterventionView> trace = office.query(c ->
                c.workshop().interventions(m).stream().map(InterventionView::of).toList());
        return new WorkshopView(
                m.value(),
                spaces.stream()
                        .map(s -> new WorkspaceView(
                                s.workspace().agent().value(),
                                s.workspace().path(),
                                s.workspace().branch(),
                                s.workspace().baseCommit(),
                                workshop.head(s.workspace()),
                                workshop.changes(s.workspace()),
                                s.edited(),
                                workshop.leftOut(s.workspace())))
                        .toList(),
                trace);
    }

    @GET
    @Path("/{agent}/files")
    public List<String> files(@PathParam("id") String id, @PathParam("agent") String agent) {
        return workshop.files(workspace(id, agent));
    }

    @GET
    @Path("/{agent}/file")
    public FileView file(
            @PathParam("id") String id,
            @PathParam("agent") String agent,
            @Parameter(description = "The file path inside the agent worktree, relative with forward slashes.")
                    @QueryParam("path")
                    String path) {
        String content = workshop.read(workspace(id, agent), path);
        return new FileView(path, content, content == null);
    }

    @GET
    @Path("/{agent}/diff")
    public DiffView diff(
            @PathParam("id") String id,
            @PathParam("agent") String agent,
            @Parameter(description = "The file path inside the agent worktree, relative with forward slashes.")
                    @QueryParam("path")
                    String path) {
        // Without a path, every pending change at once.
        if (path == null || path.isBlank()) {
            WorkshopFiles.WholeDiff whole = workshop.diff(workspace(id, agent));
            return new DiffView(null, whole.diff(), whole.omitted());
        }
        return new DiffView(path, workshop.diff(workspace(id, agent), path), null);
    }

    @PUT
    @Path("/{agent}/file")
    @Consumes(MediaType.APPLICATION_JSON)
    public WorkshopView edit(
            @PathParam("id") String id,
            @PathParam("agent") String agent,
            @Parameter(description = "The file path inside the agent worktree, relative with forward slashes.")
                    @QueryParam("path")
                    String path,
            Edit edit) {
        if (edit == null || edit.content() == null)
            throw dev.floor42.server.app.RefusedException.invalid("INVALID_ARGUMENT", "content is required");
        // Under the mission's close lock, shared: never while the mission is being accepted (ADR-0055).
        home.changing(
                mission(id), () -> workshop.edit(mission(id), new AgentId(agent), path, edit.content(), edit.note()));
        return view(id);
    }

    @POST
    @Path("/{agent}/accept")
    @Consumes(MediaType.APPLICATION_JSON)
    public WorkshopView accept(@PathParam("id") String id, @PathParam("agent") String agent, Accept accept) {
        home.changing(
                mission(id),
                () -> workshop.accept(
                        mission(id),
                        new AgentId(agent),
                        accept == null ? null : accept.message(),
                        accept == null ? null : accept.paths()));
        return view(id);
    }

    /** Gives up one pending change: the file goes back to the last commit, a new file is deleted. */
    @POST
    @Path("/{agent}/discard")
    public WorkshopView discard(
            @PathParam("id") String id,
            @PathParam("agent") String agent,
            @Parameter(description = "The pending file to give up, relative with forward slashes.") @QueryParam("path")
                    String path) {
        home.changing(mission(id), () -> discard.discard(mission(id), new AgentId(agent), path));
        return view(id);
    }

    @POST
    @Path("/{agent}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    public WorkshopView reject(@PathParam("id") String id, @PathParam("agent") String agent, Message reason) {
        home.changing(
                mission(id),
                () -> workshop.reject(mission(id), new AgentId(agent), reason == null ? null : reason.message()));
        return view(id);
    }

    /** Runs the director's checks now in this workspace; Floor42 measures and records the outcome. */
    @POST
    @Path("/{agent}/checks")
    @RunOnVirtualThread
    public MissionViews.CheckRunView runChecks(@PathParam("id") String id, @PathParam("agent") String agent) {
        return MissionViews.CheckRunView.of(checks.run(mission(id), new AgentId(agent), "director"));
    }

    /** Sends the ticket back to work with the director's words; Floor42 restarts the producer, then a new review. */
    @POST
    @Path("/directive")
    @Consumes(MediaType.APPLICATION_JSON)
    public WorkshopView directive(@PathParam("id") String id, Directive directive) {
        MissionId m = mission(id);
        if (directive == null || directive.ticket() == null)
            throw dev.floor42.server.app.RefusedException.invalid("INVALID_ARGUMENT", "ticket is required");
        var origin = office.query(c -> c.missions().find(m).orElseThrow().origin());
        office.execute(c -> c.workshop()
                .giveDirective(
                        office.context(Actor.director(), origin),
                        new TicketId(directive.ticket()),
                        directive.directive()));
        return view(id);
    }

    private MissionId mission(String id) {
        MissionId m = new MissionId(id);
        if (office.query(c -> c.missions().find(m)).isEmpty()) throw new NotFoundException("No mission " + id);
        return m;
    }

    private Workspace workspace(String id, String agent) {
        return workshop.workspace(mission(id), new AgentId(agent));
    }
}
