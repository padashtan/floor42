package dev.floor42.domain.workflow;

import dev.floor42.domain.MissionId;
import java.time.Instant;
import java.util.Optional;

/**
 * A workflow the planner proposed for the company's list while planning a mission (ADR-0050). The director saves it,
 * edited or not, or dismisses it; it never changes the mission it was proposed on.
 */
public record WorkflowProposal(
        int number, MissionId mission, String by, Instant at, WorkflowDocument.Reading reading, Status status) {

    public enum Status {
        OPEN,
        ADOPTED,
        DISMISSED
    }

    public Optional<Workflow> workflow() {
        return reading instanceof WorkflowDocument.Reading.Read r ? Optional.of(r.workflow()) : Optional.empty();
    }

    public WorkflowProposal closed(boolean adopted) {
        return new WorkflowProposal(number, mission, by, at, reading, adopted ? Status.ADOPTED : Status.DISMISSED);
    }
}
