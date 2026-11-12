package dev.floor42.domain;

import java.util.List;
import java.util.Set;

/**
 * One mission worktree whose work is not all committed, as Floor42 measured it when the director accepted the mission
 * anyway (ADR-0055). Codes only: the screen words them.
 *
 * @param agent whose worktree
 * @param state {@code DIRTY} (files changed or never committed) or {@code UNKNOWN} (Floor42 could not tell)
 * @param reason for {@code UNKNOWN}: {@code GIT_FAILED}, {@code WORKTREE_MISSING} or {@code NOT_A_MISSION_WORKTREE};
 *     null for {@code DIRTY}
 * @param count how many files are not committed; 0 when unknown
 * @param paths the first of them, at most {@link #MAX_PATHS}
 */
public record UncommittedWorktree(String agent, String state, String reason, int count, List<String> paths) {

    public static final int MAX_PATHS = 20;
    public static final String DIRTY = "DIRTY";
    public static final String UNKNOWN = "UNKNOWN";
    public static final Set<String> REASONS = Set.of("GIT_FAILED", "WORKTREE_MISSING", "NOT_A_MISSION_WORKTREE");

    public UncommittedWorktree {
        paths = paths == null ? List.of() : List.copyOf(paths);
    }

    /** Whether the record says what it should: a known state, a reason exactly when unknown, bounded lists. */
    boolean wellFormed() {
        boolean dirty = DIRTY.equals(state) && reason == null && count > 0;
        boolean unknown =
                UNKNOWN.equals(state) && reason != null && REASONS.contains(reason) && count == 0 && paths.isEmpty();
        return agent != null && !agent.isBlank() && (dirty || unknown) && paths.size() <= MAX_PATHS;
    }
}
