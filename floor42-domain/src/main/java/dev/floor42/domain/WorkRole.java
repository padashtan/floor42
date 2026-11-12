package dev.floor42.domain;

/**
 * The work Floor42 gives an agent, chosen by the director when hiring. What each role may do is enforced by Floor42's
 * code (permission rules, the tools offered to a session), never by the brief alone.
 */
public enum WorkRole {
    /** Works approved tickets in its own git worktree. */
    DEVELOPER,
    /** Reviews someone else's deliverable, reading only. */
    REVIEWER,
    /** Researches the web for a reviewer, without the repository. */
    RESEARCHER,
    /** Splits a mission into tickets the director approves, reading only. */
    PLANNER,
    /**
     * Business analyst: gathers the questions a committee's members raise, asks the director in business terms, and
     * brings the answers back to the committee. Reads only.
     */
    ANALYST
}
