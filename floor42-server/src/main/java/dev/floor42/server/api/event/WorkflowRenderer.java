package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.Company;
import dev.floor42.domain.event.WorkflowEvent;
import dev.floor42.domain.event.WorkflowEvent.CompanyWorkflowRemoved;
import dev.floor42.domain.event.WorkflowEvent.CompanyWorkflowSaved;
import dev.floor42.domain.event.WorkflowEvent.NodePlanned;
import dev.floor42.domain.event.WorkflowEvent.WorkflowPinned;
import dev.floor42.domain.event.WorkflowEvent.WorkflowProposalClosed;
import dev.floor42.domain.event.WorkflowEvent.WorkflowProposed;
import dev.floor42.domain.event.WorkflowEvent.WorkflowStepTicketOpened;
import dev.floor42.domain.workflow.WorkflowDocument;

/** The lines of workflows (ADR-0050): a mission's pin, the company's list, the planner's proposals. */
final class WorkflowRenderer implements EventRenderer<WorkflowEvent> {

    @Override
    public EventLine render(Company c, WorkflowEvent event) {
        return switch (event) {
            case WorkflowPinned e -> {
                String name = name(e.document(), e.schemaVersion());
                yield line(name == null ? "unreadable" : "readable")
                        .param("mission", e.mission().value())
                        .param("name", name == null ? "" : name)
                        .build();
            }
            case CompanyWorkflowSaved e -> line().param("name", e.name()).build();
            case CompanyWorkflowRemoved e -> line().param("name", e.name()).build();
            case WorkflowProposed e ->
                line().param("mission", e.mission().value())
                        .param("proposal", e.proposal())
                        .param("name", String.valueOf(name(e.document(), e.schemaVersion())))
                        .build();
            case WorkflowStepTicketOpened e ->
                line().ticket(e.ticket())
                        .param("mission", e.mission().value())
                        .param("ticket", e.ticket().value())
                        .param("step", e.step())
                        .build();
            case NodePlanned e -> node(e);
            case WorkflowProposalClosed e ->
                line(e.adopted() ? "adopted" : "dismissed")
                        .param("proposal", e.proposal())
                        .build();
        };
    }

    private static EventLine node(NodePlanned e) {
        return line().ticket(e.ticket())
                .param("ticket", e.ticket().value())
                .param("risk", e.risk())
                .param("tier", e.tier())
                .param("count", e.tests().size())
                .build();
    }

    /** The workflow's name, or null when this build cannot read the document. */
    private static String name(String document, int schemaVersion) {
        return WorkflowDocument.read(document, schemaVersion) instanceof WorkflowDocument.Reading.Read r
                ? r.workflow().name()
                : null;
    }
}
