package dev.floor42.domain.workflow;

/**
 * The workflow reader of Floor42 1.15.x, kept to prove what a rollback reads (ADR-0062): it reads schema versions 1 to
 * 3 and refuses any newer one, so a version 4 pin (a graph mission) is {@code Unrunnable} there, shown and never run.
 * Versions 1 to 3 are frozen readers: their code is the one this build keeps, so it is called, not copied.
 */
final class Reader115 {

    /** The highest schema version 1.15.0 reads. */
    static final int CURRENT = 3;

    private Reader115() {}

    /** As 1.15.0's {@code MissionWorkflow.of}: what a pin's document reads as. */
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
