package dev.floor42.domain;

/**
 * Where a fact comes from. Every event and every displayed state carries one, so a synthetic event can never be
 * mistaken for something that really happened.
 */
public enum Origin {
    /** Produced by the simulation engine or a replayed transcript. Nothing was executed. */
    SIMULATED,
    /** Observed from a real agent runtime or a real tool execution. */
    REAL
}
