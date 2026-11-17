package dev.floor42.domain;

/** Why an agent's trust level changed (journaled by {@code AgentTrustChanged}). */
public enum TrustCause {
    /** A call ran without Floor42's authorization. */
    INCIDENT,
    /** A session of the agent reached ALERT or above. */
    RISK,
    /** The director quarantined a session of the agent. */
    QUARANTINE,
    /** The director raised or lowered the level by hand. */
    DIRECTOR,
    /** The agent was hired: new staff start on probation. */
    HIRE
}
