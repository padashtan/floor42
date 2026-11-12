package dev.floor42.domain;

import java.util.Set;

/**
 * Lifecycle of an agent action (ADR-0005). Each step is backed by an observed signal: the runtime request, an Floor42
 * decision, the tool result, an independent confirmation.
 */
public enum ActionStage {
    REQUESTED,
    AUTHORIZED,
    DENIED,
    EXECUTED,
    FAILED,
    CONFIRMED;

    public boolean isTerminal() {
        return this == DENIED || this == FAILED || this == CONFIRMED;
    }

    /** True once the tool really ran. Only these stages may animate a machine. */
    public boolean hasRun() {
        return this == EXECUTED || this == FAILED || this == CONFIRMED;
    }

    boolean canMoveTo(ActionStage next) {
        return switch (this) {
            // REQUESTED -> EXECUTED exists only to record an execution Floor42 never authorized (an incident).
            case REQUESTED -> Set.of(AUTHORIZED, DENIED, EXECUTED).contains(next);
            case AUTHORIZED -> Set.of(EXECUTED, FAILED).contains(next);
            case EXECUTED -> Set.of(CONFIRMED).contains(next);
            case DENIED, FAILED, CONFIRMED -> false;
        };
    }
}
