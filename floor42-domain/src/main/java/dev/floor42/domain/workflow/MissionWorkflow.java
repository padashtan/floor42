package dev.floor42.domain.workflow;

import java.util.Optional;

/** The workflow a mission follows (ADR-0050), read once when its pin is applied. */
public sealed interface MissionWorkflow {

    /** The workflow to run, empty when this build cannot run the mission's. */
    Optional<Workflow> runnable();

    /** No pin: a mission journaled before 1.9.0, or a simulated one. It follows {@link Workflow#BUILT_IN}. */
    record Unpinned() implements MissionWorkflow {
        @Override
        public Optional<Workflow> runnable() {
            return Optional.of(Workflow.BUILT_IN);
        }
    }

    /** Pinned at the mission's creation: it follows this workflow for good, whatever is edited later. */
    record Pinned(Workflow workflow) implements MissionWorkflow {
        @Override
        public Optional<Workflow> runnable() {
            return Optional.of(workflow);
        }
    }

    /** Pinned with a document this build cannot read: the mission is read and shown, never run. */
    record Unrunnable(int schemaVersion, String reason) implements MissionWorkflow {
        @Override
        public Optional<Workflow> runnable() {
            return Optional.empty();
        }
    }

    /** What a pin's document reads as. */
    public static MissionWorkflow of(String document, int schemaVersion) {
        return switch (WorkflowDocument.read(document, schemaVersion)) {
            case WorkflowDocument.Reading.Read r -> new Pinned(r.workflow());
            case WorkflowDocument.Reading.Refused r -> new Unrunnable(schemaVersion, r.reason());
        };
    }
}
