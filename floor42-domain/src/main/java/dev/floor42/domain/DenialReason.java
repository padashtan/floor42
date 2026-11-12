package dev.floor42.domain;

public enum DenialReason {
    /** A permission rule denies it, or no rule allows it. */
    POLICY,
    /** The director answered no. */
    DIRECTOR,
    MISSION_NOT_ACTIVE,
    /**
     * A file tool aimed outside the agent's workspace, or at a protected file inside it. Enforced whatever the
     * permission table says: the director's checkout and machine are never an agent's to touch.
     */
    OUTSIDE_WORKSPACE,
    /** Stopped before Floor42 could decide, for instance governance unreachable behind a fail-closed hook. */
    BLOCKED_UPSTREAM
}
