package dev.floor42.domain;

import java.util.List;

/** A member of staff. Identity and contract only; what the agent is doing is derived by {@link QueriesFacade#statusOf}. */
public final class Agent {

    private final AgentId id;
    private final String displayName;
    private final String role;
    private final String provider;
    String model;
    String effort;
    private final List<String> skills;
    boolean suspended;
    WorkRole workRole;
    boolean dismissed;
    boolean core;
    TrustLevel trust = TrustLevel.STANDARD;
    java.time.Instant trustSince;
    TrustCause trustCause;
    String trustCauseEvent;
    int acceptedSinceTrustChange;

    Agent(
            AgentId id,
            String displayName,
            String role,
            String provider,
            String model,
            String effort,
            List<String> skills) {
        this.id = id;
        this.displayName = displayName;
        this.role = role;
        this.provider = provider;
        this.model = model;
        this.effort = effort;
        this.skills = List.copyOf(skills);
    }

    public AgentId id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String role() {
        return role;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    /** Reasoning effort as configured; null when the provider has no such setting. */
    public String effort() {
        return effort;
    }

    public List<String> skills() {
        return skills;
    }

    /** The work Floor42 gives this agent; null for staff hired before roles were recorded (ADR-0019). */
    public WorkRole workRole() {
        return workRole;
    }

    /** True once the director dismissed the agent: it takes no work and has left the office. */
    public boolean dismissed() {
        return dismissed;
    }

    /** True while the director keeps the agent starred as a core member: a dismissal of everyone else keeps it. */
    public boolean core() {
        return core;
    }

    /** True when the director suspended new assignments for this agent. */
    public boolean suspended() {
        return suspended;
    }

    /** The trust cap on this agent (ADR-0082): STANDARD for staff hired before levels existed. */
    public TrustLevel trust() {
        return trust;
    }

    /** When the level last changed; null while it never did. */
    public java.time.Instant trustSince() {
        return trustSince;
    }

    /** Why the level last changed; null while it never did. */
    public TrustCause trustCause() {
        return trustCause;
    }

    /** The id of the journal event that caused the last change; null when none did (a hand change, a hire). */
    public String trustCauseEvent() {
        return trustCauseEvent;
    }

    /** Patches the director accepted since the level last changed (an incident resets it). */
    public int acceptedSinceTrustChange() {
        return acceptedSinceTrustChange;
    }

    /** True when the record earned the director a proposal to raise the level one step. */
    public boolean trustRaiseProposed() {
        return !dismissed && Probation.raiseProposed(trust, acceptedSinceTrustChange);
    }
}
