package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.event.WorkbenchEvent;
import dev.floor42.domain.event.WorkbenchEvent.BranchesMerged;
import dev.floor42.domain.event.WorkbenchEvent.DirectorEditedFile;
import dev.floor42.domain.event.WorkbenchEvent.DirectorFilesCommitted;
import dev.floor42.domain.event.WorkbenchEvent.PatchAccepted;
import dev.floor42.domain.event.WorkbenchEvent.PatchRejected;
import dev.floor42.domain.event.WorkbenchEvent.ProjectMerged;
import dev.floor42.domain.event.WorkbenchEvent.WorkspacePrepared;

/** Workspaces' lines and what the director did in them. The director's diff stays out. */
final class WorkbenchRenderer implements EventRenderer<WorkbenchEvent> {

    @Override
    public EventLine render(Company c, WorkbenchEvent event) {
        return switch (event) {
            case WorkspacePrepared e -> prepared(e);
            case DirectorEditedFile e ->
                workspace(null, e.mission(), e.workspace(), "workspace")
                        .param("path", e.path())
                        .param("note", e.note())
                        .build();
            case PatchAccepted e -> accepted(e);
            case PatchRejected e -> rejected(e);
            case BranchesMerged e -> merged(e);
            case DirectorFilesCommitted e ->
                line().param("mission", e.mission())
                        .param("commit", e.commit())
                        .param("files", e.files())
                        .build();
            case ProjectMerged e ->
                workspace(e.clean() ? "clean" : "conflict", e.mission(), e.agent(), "agent")
                        .param("commit", e.commit())
                        .param("clean", e.clean())
                        .param("detail", e.detail())
                        .build();
        };
    }

    private static EventLine prepared(WorkspacePrepared e) {
        return workspace(null, e.mission(), e.agent(), "agent")
                .param("path", e.path())
                .param("branch", e.branch())
                .param("baseCommit", e.baseCommit())
                .build();
    }

    private static EventLine rejected(PatchRejected e) {
        return workspace(null, e.mission(), e.workspace(), "workspace")
                .param("reason", e.reason())
                .param("archive", e.archive())
                .param("files", e.files())
                .build();
    }

    private static EventLine accepted(PatchAccepted e) {
        return workspace(null, e.mission(), e.workspace(), "workspace")
                .param("commit", e.commit())
                .param("short", Lookups.shortId(e.commit()))
                .param("message", e.message())
                .param("files", e.files())
                .param("count", e.files().size())
                .build();
    }

    private static EventLine merged(BranchesMerged e) {
        return line((e.clean() ? "clean" : "failed") + (e.into() == null ? "" : ".into"))
                .agent(e.into())
                .ticket(e.ticket())
                .param("mission", e.mission())
                .param("ticket", e.ticket())
                .param("into", e.into())
                .param("branches", e.branches())
                .param("clean", e.clean())
                .param("detail", e.detail())
                .build();
    }

    /** A line about one agent's workspace, its field named as the event names it: the scene points at the agent. */
    private static EventLine.Builder workspace(String variant, MissionId mission, AgentId agent, String field) {
        return line(variant).agent(agent).param("mission", mission).param(field, agent);
    }
}
