package dev.floor42.domain;

import java.util.Set;

/**
 * One step of a recipe as Floor42 ran it, or did not (ADR-0058). Exit code, duration and output are Floor42's measure;
 * title and command are the producer's words.
 *
 * @param runner {@code FLOOR42} or {@code DIRECTOR}
 * @param reason why the director runs it; null for Floor42's steps
 * @param outcome {@code PASSED}, {@code FAILED} or {@code NOT_RUN}
 * @param failure why it failed or did not run; null when it passed
 * @param exitCode null when it did not run, timed out or could not start
 */
public record ProofStep(
        int step,
        String title,
        String command,
        String runner,
        String reason,
        String outcome,
        String failure,
        Integer exitCode,
        boolean timedOut,
        long durationMs,
        String output) {

    public static final int MAX_STEPS = 20;
    public static final int MAX_COMMAND = 4_000;
    public static final int MAX_TITLE = 120;
    /** The output's last 8 000 characters, and the mark Floor42 puts before a cut. */
    public static final int MAX_OUTPUT = 8_100;

    static final Set<String> RUNNERS = Set.of("FLOOR42", "DIRECTOR");
    static final Set<String> OUTCOMES = Set.of("PASSED", "FAILED", "NOT_RUN");

    boolean wellFormed() {
        return step >= 1
                && step <= MAX_STEPS
                && title != null
                && title.length() <= MAX_TITLE
                && command != null
                && command.length() <= MAX_COMMAND
                && RUNNERS.contains(runner)
                && OUTCOMES.contains(outcome)
                && (outcome.equals("PASSED") == (failure == null))
                && durationMs >= 0
                && output != null
                && output.length() <= MAX_OUTPUT;
    }

    public boolean failed() {
        return "FAILED".equals(outcome);
    }
}
