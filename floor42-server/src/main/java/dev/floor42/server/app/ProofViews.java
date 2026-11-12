package dev.floor42.server.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What the screen "Prove the delivery" reads (ADR-0058). */
public final class ProofViews {

    private ProofViews() {}

    /** Floor42 found no usable recipe in at least one worktree. */
    public static final String NO_RECIPE = "NO_RECIPE";
    /** A recipe has Floor42 steps not run on the code and recipe as they are now. */
    public static final String NOT_RUN = "NOT_RUN";
    /** A step Floor42 ran on the code as it is now failed. */
    public static final String FAILED = "FAILED";
    /** Every step Floor42 can run passed on the code as it is now; the director's own steps may be left. */
    public static final String PASSED = "PASSED";

    /**
     * The proof of a mission's delivery now.
     *
     * @param sandboxWorks whether Floor42 can run steps at all; when not, every step is the director's
     * @param running a run started and not ended
     * @param runError why the last run stopped before recording anything (a code), null otherwise
     * @param status {@link #NO_RECIPE}, {@link #NOT_RUN}, {@link #FAILED} or {@link #PASSED}
     * @param directorSteps steps left to the director, over every worktree
     * @param verdict the director's last verdict, null when none
     * @param targets names every worktree's recipe and code as they are now: a verdict carries it back (ADR-0058)
     * @param unprovenAccepted the proof state the director accepted the mission in, when not proven; null otherwise
     */
    public record ProofView(
            String mission,
            String origin,
            boolean sandboxWorks,
            boolean running,
            String runError,
            String status,
            int directorSteps,
            List<ProofWorktree> worktrees,
            ProofVerdictView verdict,
            String targets,
            String unprovenAccepted) {
        public ProofView {
            worktrees = worktrees == null ? null : Collections.unmodifiableList(new ArrayList<>(worktrees));
        }
    }

    /**
     * One producer's worktree, its recipe and the code they are about now.
     *
     * @param state {@link ProofRecipe#FOUND}, {@link ProofRecipe#MISSING} or {@link ProofRecipe#UNKNOWN}
     * @param head the worktree's commit now; null when git did not answer
     * @param changesDigest a digest of what is not committed now; null when git did not answer
     * @param lastRun Floor42's last recorded run of this worktree's recipe, null when none
     * @param witnesses what the director said of their steps, on this recipe and this code only
     * @param target names this recipe and code: a report carries it back; null when either is unknown
     * @param skipped files the last copy for a run left out, as {@code REASON: path} (50 named; kept in memory)
     * @param skippedCount every file it left out
     */
    public record ProofWorktree(
            String agent,
            String path,
            String state,
            String digest,
            String head,
            String changesDigest,
            List<String> problems,
            List<ProofStepView> steps,
            ProofRunView lastRun,
            List<ProofWitnessView> witnesses,
            String target,
            List<String> skipped,
            int skippedCount) {
        public ProofWorktree {
            problems = problems == null ? null : Collections.unmodifiableList(new ArrayList<>(problems));
            skipped = skipped == null ? null : Collections.unmodifiableList(new ArrayList<>(skipped));
            steps = steps == null ? null : Collections.unmodifiableList(new ArrayList<>(steps));
            witnesses = witnesses == null ? null : Collections.unmodifiableList(new ArrayList<>(witnesses));
        }
    }

    /**
     * One step, as the producer wrote it (basis REPORTED), and who runs it.
     *
     * @param runner FLOOR42 or DIRECTOR
     * @param reason why the director runs it (MARKED_FOR_DIRECTOR, NEEDS_DOCKER, NEEDS_DESKTOP, AFTER_DIRECTOR_STEP,
     *     SANDBOX_UNAVAILABLE); null for Floor42's steps
     * @param problems codes that keep the step from running (HIDDEN_CHARACTER among them: no copy then)
     */
    public record ProofStepView(
            int index,
            String title,
            String command,
            Integer expectExit,
            List<String> expectOutput,
            String runner,
            String reason,
            List<String> problems) {
        public ProofStepView {
            expectOutput = expectOutput == null ? null : Collections.unmodifiableList(new ArrayList<>(expectOutput));
            problems = problems == null ? null : Collections.unmodifiableList(new ArrayList<>(problems));
        }
    }

    /**
     * A run of Floor42's steps on a copy of the worktree (basis MEASURED), as journaled ({@code ProofRan}).
     *
     * @param stale the recipe, the commit or the uncommitted work changed since: it proves nothing about them now
     */
    public record ProofRunView(
            String at, String digest, String head, String changesDigest, boolean stale, List<ProofStepResult> steps) {
        public ProofRunView {
            steps = steps == null ? null : Collections.unmodifiableList(new ArrayList<>(steps));
        }
    }

    /**
     * One step's measure.
     *
     * @param outcome PASSED, FAILED or NOT_RUN
     * @param failure why it failed or did not run: EXIT_CODE, OUTPUT_MISSING, TIMED_OUT, DID_NOT_START,
     *     EARLIER_STEP_FAILED, NOT_RUNNABLE, DIRECTOR_STEP
     * @param exitCode null when it did not run, timed out or could not start
     */
    public record ProofStepResult(
            int index,
            String command,
            String outcome,
            String failure,
            Integer exitCode,
            boolean timedOut,
            long durationMs,
            String output) {}

    /** What the director said of one of their steps ({@code ProofStepWitnessed}). */
    public record ProofWitnessView(int step, boolean works, String seen, String at) {}

    /**
     * The director's verdict ({@code ProofVerdictGiven}).
     *
     * @param current whether it is about the recipes and the code as they are now
     */
    public record ProofVerdictView(String verdict, String note, String at, boolean current) {}

    /**
     * What an acceptance would ask about (ADR-0058).
     *
     * @param state null when the delivery is proven (or nothing is to prove); else NO_RECIPE, NOT_RUN, FAILED,
     *     NOT_PROVEN or NO_VERDICT
     * @param token names this exact state: {@code POST /complete} carries it as {@code proofAcknowledged}
     */
    public record ProofAcceptanceView(String state, String token) {}

    /** The ticket "Send back to the producer" reopened. */
    public record SentBack(String ticket, String agent) {}
}
