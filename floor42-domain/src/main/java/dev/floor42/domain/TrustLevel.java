package dev.floor42.domain;

/**
 * How far Floor42 lets an agent act beyond the mission's permission table (ADR-0082). Declared from the most
 * restricted to the most trusted. A level is a cap applied after the table ({@link TrustCap}), never a rule.
 */
public enum TrustLevel {
    /** Every write, run and network call is denied and shown to the director as a proposal. */
    SHADOW,
    /** Writes ask the director once, runs and network calls ask every time. */
    PROBATION,
    /** The table decides. */
    STANDARD;

    /** One level down; SHADOW stays. */
    public TrustLevel lowered() {
        return this == STANDARD ? PROBATION : SHADOW;
    }
}
