package dev.floor42.domain;

public enum ReviewVerdict {
    VALIDATED,
    VALIDATED_WITH_RESERVATIONS,
    REJECTED,
    /** The reviewer could not verify with the evidence available: escalated to the director. */
    UNVERIFIABLE
}
