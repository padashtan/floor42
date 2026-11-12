package dev.floor42.domain;

import java.util.Set;

public enum TicketStatus {
    /** Proposed by an agent or by Floor42: nothing runs until the director approves it (ADR-0017). */
    PROPOSED,
    TODO,
    IN_PROGRESS,
    IN_REVIEW,
    DONE,
    CANCELLED;

    public boolean isTerminal() {
        return this == DONE || this == CANCELLED;
    }

    /** The moves a command asks for. Release and reopen are the two named moves below, applied by their events. */
    boolean canMoveTo(TicketStatus next) {
        return switch (this) {
            case PROPOSED -> Set.of(TODO, CANCELLED).contains(next);
            case TODO -> Set.of(IN_PROGRESS, CANCELLED).contains(next);
            case IN_PROGRESS -> Set.of(IN_REVIEW, CANCELLED).contains(next);
            // A rejected review sends the ticket back to work; only a validating review closes it.
            case IN_REVIEW -> Set.of(DONE, IN_PROGRESS, CANCELLED).contains(next);
            case DONE, CANCELLED -> false;
        };
    }

    /** TicketReleased: the assignee left, the ticket goes back to the queue (IN_PROGRESS to TODO). */
    public boolean canBeReleased() {
        return this == IN_PROGRESS;
    }

    /** TicketReopened: a rejected review, a directive or a changed dependency sends it back to work. */
    public boolean canBeReopened() {
        return this == IN_REVIEW || this == DONE;
    }
}
