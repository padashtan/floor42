package dev.floor42.domain.workflow;

/**
 * The workflow reader of Floor42 1.14.0 and 1.14.1, kept to prove what a rollback reads (ADR-0061): it reads schema
 * versions 1 and 2 and refuses any newer one, so a version 3 pin (a task) is {@code Unrunnable} there, shown and never
 * run. Versions 1 and 2 are frozen readers: their code is the one this build keeps, so it is called, not copied.
 * {@link MissionWorkflow#of} at 1.14.0 turned a refused reading into {@code Unrunnable}, as {@link #pin} does here.
 */
final class Reader114 {

    /** The highest schema version 1.14.0 reads. */
    static final int CURRENT = 2;

    private Reader114() {}

    /** As 1.14.0's {@code MissionWorkflow.of}: what a pin's document reads as. */
    static MissionWorkflow pin(String document, int schemaVersion) {
        return switch (read(document, schemaVersion)) {
            case WorkflowDocument.Reading.Read r -> new MissionWorkflow.Pinned(r.workflow());
            case WorkflowDocument.Reading.Refused r -> new MissionWorkflow.Unrunnable(schemaVersion, r.reason());
        };
    }

    static WorkflowDocument.Reading read(String document, int schemaVersion) {
        if (schemaVersion > CURRENT) {
            return new WorkflowDocument.Reading.Refused("Written by a newer Floor42 (workflow schema " + schemaVersion
                    + "; this build reads " + CURRENT + "): upgrade to run it");
        }
        return WorkflowDocument.read(document, schemaVersion);
    }
}
