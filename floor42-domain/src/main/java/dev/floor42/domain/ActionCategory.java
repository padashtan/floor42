package dev.floor42.domain;

/** What kind of effect an action has. Permissions are granted per category, optionally narrowed to a command. */
public enum ActionCategory {
    READ,
    WRITE,
    EXECUTE,
    NETWORK,
    /** Floor42 tools (create a ticket, ask the director, submit a report): they turn into domain commands. */
    FLOOR42,
    OTHER
}
