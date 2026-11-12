package dev.floor42.domain;

import java.util.Set;

public enum MissionStatus {
    PLANNING,
    AWAITING_APPROVAL,
    IN_PROGRESS,
    SUSPENDED,
    COMPLETED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }

    boolean canMoveTo(MissionStatus next) {
        return switch (this) {
            case PLANNING -> Set.of(AWAITING_APPROVAL, CANCELLED).contains(next);
            case AWAITING_APPROVAL -> Set.of(IN_PROGRESS, PLANNING, CANCELLED).contains(next);
            case IN_PROGRESS -> Set.of(SUSPENDED, COMPLETED, CANCELLED).contains(next);
            case SUSPENDED -> Set.of(IN_PROGRESS, CANCELLED).contains(next);
            case COMPLETED, CANCELLED -> false;
        };
    }
}
