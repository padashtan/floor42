package dev.floor42.domain;

/**
 * What a delivery's proof is about (ADR-0058): one producer's worktree, the recipe as it was, and the code it ran on.
 * Any of them changing makes a run, a director's report or a verdict about this target stale.
 *
 * @param recipeDigest SHA-256 of {@code PROVE.md}, 16 hex characters
 * @param head the commit the worktree was on
 * @param changesDigest SHA-256 of the work not committed (changes to tracked files and new files), 16 hex characters
 */
public record ProofTarget(AgentId workspace, String recipeDigest, String head, String changesDigest) {

    /** Whether each field is what Floor42 measures: hex digests and a commit id. */
    boolean wellFormed() {
        return workspace != null
                && recipeDigest != null
                && recipeDigest.matches("[0-9a-f]{16}")
                && head != null
                && head.matches("[0-9a-f]{7,64}")
                && changesDigest != null
                && changesDigest.matches("[0-9a-f]{16}");
    }
}
