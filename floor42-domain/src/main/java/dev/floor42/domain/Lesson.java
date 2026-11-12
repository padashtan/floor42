package dev.floor42.domain;

import java.time.Instant;

/**
 * A lesson drawn by a retrospective (ADR-0020). The chair proposes it; only the director accepts it, and only an
 * accepted lesson reaches the briefs of later missions, for the role it names (or everyone).
 */
public final class Lesson {

    public enum Status {
        PROPOSED,
        ACCEPTED,
        REJECTED,
        /** Accepted once, withdrawn by the director since: it no longer reaches any brief. */
        RETIRED
    }

    /** A lesson as the chair states it; {@code audience} null means every role. */
    public record Proposal(WorkRole audience, String text) {}

    private final String id;
    private final MissionId mission;
    private final WorkRole audience;
    private final AgentId proposedBy;
    private final Instant proposedAt;
    String text;
    Status status = Status.PROPOSED;
    String reason;

    Lesson(String id, MissionId mission, WorkRole audience, String text, AgentId proposedBy, Instant at) {
        this.id = id;
        this.mission = mission;
        this.audience = audience;
        this.text = text;
        this.proposedBy = proposedBy;
        this.proposedAt = at;
    }

    public String id() {
        return id;
    }

    /** The mission whose retrospective drew it. */
    public MissionId mission() {
        return mission;
    }

    /** The role it is for; null for every role. */
    public WorkRole audience() {
        return audience;
    }

    public String text() {
        return text;
    }

    /** Null when Floor42 closed the retrospective itself. */
    public AgentId proposedBy() {
        return proposedBy;
    }

    public Instant proposedAt() {
        return proposedAt;
    }

    public Status status() {
        return status;
    }

    /** Why the director rejected or retired it; null otherwise. */
    public String reason() {
        return reason;
    }

    /** True when it belongs in the brief of an agent working in this role. */
    public boolean reaches(WorkRole role) {
        return status == Status.ACCEPTED && (audience == null || audience == role);
    }
}
