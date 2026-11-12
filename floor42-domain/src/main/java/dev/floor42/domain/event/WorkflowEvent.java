package dev.floor42.domain.event;

import dev.floor42.domain.MissionId;

/**
 * Workflows (ADR-0050): the one a mission pins, the company's list the director writes, the planner's proposals. A
 * document is opaque to the journal: a JSON text and its schema version, read by the domain's {@code WorkflowDocument}.
 */
public sealed interface WorkflowEvent extends DomainEvent {

    /** Recorded with the mission's creation, before its first ticket: the mission follows this workflow for good. */
    record WorkflowPinned(MissionId mission, String document, int schemaVersion) implements WorkflowEvent {
        public WorkflowPinned {
            java.util.Objects.requireNonNull(mission, "mission");
        }
    }

    /** The director saved a company workflow under this name, replacing one of the same name. */
    record CompanyWorkflowSaved(String name, String document, int schemaVersion) implements WorkflowEvent {
        /** A row without a name is corrupt (quarantined by the codec), never a crash of the replay. */
        public CompanyWorkflowSaved {
            java.util.Objects.requireNonNull(name, "name");
        }
    }

    /** The director removed a company workflow: missions that pinned it keep their copy. */
    record CompanyWorkflowRemoved(String name) implements WorkflowEvent {
        public CompanyWorkflowRemoved {
            java.util.Objects.requireNonNull(name, "name");
        }
    }

    /** The planner of the mission proposed a workflow for the company's list; numbered from 1 across the company. */
    record WorkflowProposed(MissionId mission, int proposal, String document, int schemaVersion)
            implements WorkflowEvent {}

    /**
     * The plan's approval opened this ticket for the workflow's task of this key (ADR-0061), right after its {@code
     * TicketCreated} and {@code TicketApproved}. The link is what makes a plain {@code WORK} ticket a task: a build that
     * does not know the type quarantines this row only, and a task exists in a version 3 pin only, which such a build
     * does not run.
     */
    record WorkflowStepTicketOpened(MissionId mission, dev.floor42.domain.TicketId ticket, String step)
            implements WorkflowEvent {
        public WorkflowStepTicketOpened {
            java.util.Objects.requireNonNull(mission, "mission");
            java.util.Objects.requireNonNull(ticket, "ticket");
            java.util.Objects.requireNonNull(step, "step");
        }
    }

    /**
     * The plan's approval settled this node of a graph mission (ADR-0062), right after its {@code TicketApproved}: its
     * risk ({@code low} or {@code high}, the director's last word), its tier ({@code light} or {@code standard}) and
     * the test commands Floor42 runs once the node is delivered. Written at the approval, not at the proposal, so the
     * director's change of a risk is final without a second event. A build that does not know the type quarantines this
     * row only.
     */
    record NodePlanned(dev.floor42.domain.TicketId ticket, String risk, String tier, java.util.List<String> tests)
            implements WorkflowEvent {
        public NodePlanned {
            java.util.Objects.requireNonNull(ticket, "ticket");
            java.util.Objects.requireNonNull(risk, "risk");
            java.util.Objects.requireNonNull(tier, "tier");
            tests = tests == null ? java.util.List.of() : java.util.List.copyOf(tests);
        }
    }

    /** The director saved the proposal (edited or not) or dismissed it. */
    record WorkflowProposalClosed(int proposal, boolean adopted) implements WorkflowEvent {}
}
