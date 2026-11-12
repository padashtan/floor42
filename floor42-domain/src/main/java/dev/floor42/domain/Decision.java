package dev.floor42.domain;

/** What a permission rule decides. Declared from the most permissive to the most restrictive. */
public enum Decision {
    /** The agent acts without asking. */
    ALLOW,
    /** Ask the director the first time; the answer is remembered for the mission (per exact command for the shell). */
    ASK,
    /** Ask the director on every call. */
    ASK_ALWAYS,
    DENY
}
