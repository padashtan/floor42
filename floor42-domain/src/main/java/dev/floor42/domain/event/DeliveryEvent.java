package dev.floor42.domain.event;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.DeliveryCopy;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.ProofStep;
import dev.floor42.domain.ProofTarget;
import dev.floor42.domain.UncommittedWorktree;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pull requests, mission assets and plugin commands. */
public sealed interface DeliveryEvent extends DomainEvent {

    /**
     * On the director's click and confirmation, Floor42 pushed a branch of the mission to the repository's remote and a
     * pull request exists for it on GitHub (ADR-0036). Only ever real, only ever the director's.
     *
     * @param workspace the agent's worktree whose branch was pushed; null for the director's own branch of the project
     * @param found false when {@code gh pr create} answered with the pull request; true when Floor42 found it afterwards
     *     with {@code gh pr list --head} (an earlier attempt failed or timed out, or it already existed)
     */
    record PullRequestOpened(
            MissionId mission,
            AgentId workspace,
            String url,
            int number,
            String branch,
            String base,
            String remote,
            boolean found)
            implements DeliveryEvent {}

    /**
     * The checks GitHub reports on a pull request, read with {@code gh pr checks} on the director's click (no polling).
     * {@code checks} are lines {@code name|bucket|state}, bucket as gh gives it (pass, fail, pending, skipping, cancel);
     * empty when GitHub reports no check.
     */
    record PullRequestCheckState(MissionId mission, int number, List<String> checks) implements DeliveryEvent {
        public PullRequestCheckState {
            checks = checks == null ? null : Collections.unmodifiableList(new ArrayList<>(checks));
        }
    }

    /**
     * On the director's click and confirmation, Floor42 ran Claude Code's own plugin commands ({@code claude plugin
     * ...}) to install or uninstall a plugin. Only ever real, only ever the director's. What it says happened is what
     * Floor42 read again afterwards ({@code present}), never the commands' exit code alone.
     *
     * @param target {@code FLOOR42} (Floor42's own folder, reaching no session until selected for a mission) or {@code
     *     CLAUDE} (the director's own Claude Code)
     * @param action {@code INSTALL} or {@code UNINSTALL}
     * @param commands the commands as run, in order (the last one may have failed)
     * @param exitCode the last command's exit code; null when it timed out or could not start
     * @param present whether the plugin is installed in the target, read from disk after the commands
     * @param version the installed version read after; null when none
     */
    record PluginCommandRan(
            String target,
            String action,
            String plugin,
            String marketplace,
            List<String> commands,
            Integer exitCode,
            boolean present,
            String version)
            implements DeliveryEvent {
        public PluginCommandRan {
            commands = commands == null ? null : Collections.unmodifiableList(new ArrayList<>(commands));
        }
    }

    /**
     * The director chose which installed skills and agents the mission's sessions receive (ADR-0040); it replaces any
     * earlier choice. Each item is {@code <target>:<plugin>@<marketplace>/<skill|agent>/<name>}, target {@code floor42}
     * or {@code claude}. Empty: none. Floor42 gives the sessions started afterwards exactly these, and nothing else.
     */
    record MissionAssetsSelected(MissionId mission, List<String> items) implements DeliveryEvent {
        public MissionAssetsSelected {
            items = items == null ? null : Collections.unmodifiableList(new ArrayList<>(items));
        }
    }

    /**
     * The folder the director chose, when creating the mission, for its accepted deliverables (ADR-0051): absolute, as
     * Floor42 resolved and checked it. Recorded in the command that creates the mission. A mission without it keeps
     * the behaviour from before 1.10.0: nothing is copied.
     */
    record DeliverablesDestinationSet(MissionId mission, String folder) implements DeliveryEvent {}

    /**
     * On the director's acceptance, just before the mission completed, Floor42 copied the mission's documents into
     * {@code folder} (the destination's folder for this mission), never over an existing file (ADR-0051).
     *
     * @param files each file's outcome, as measured on disk, at most {@link DeliveryCopy#MAX_FILES}
     * @param leftOut files past that count, left out and not listed
     * @param truncated a listing of the mission was cut at its cap, so some files were never considered
     */
    record DeliverablesCopied(
            MissionId mission, String folder, List<DeliveryCopy> files, int leftOut, boolean truncated)
            implements DeliveryEvent {
        public DeliverablesCopied {
            files = files == null ? null : Collections.unmodifiableList(new ArrayList<>(files));
        }
    }

    /**
     * The director accepted the mission although Floor42 measured work in its worktrees that is not committed, or could
     * not tell (ADR-0055), in the same command as the {@code MissionCompleted} it precedes. Only ever the director's.
     *
     * @param worktrees each worktree that was not clean, as measured just before
     */
    record UncommittedWorkAccepted(MissionId mission, List<UncommittedWorktree> worktrees) implements DeliveryEvent {
        public UncommittedWorkAccepted {
            worktrees = worktrees == null ? null : Collections.unmodifiableList(new ArrayList<>(worktrees));
        }
    }

    /**
     * Floor42 ran the steps of one producer's recipe it can run, in the sandbox (ADR-0058): what it measured, and the
     * steps it left to the director or did not reach. Recorded by Floor42 (system), with the mission's origin.
     */
    record ProofRan(MissionId mission, ProofTarget target, List<ProofStep> steps) implements DeliveryEvent {
        public ProofRan {
            steps = steps == null ? null : Collections.unmodifiableList(new ArrayList<>(steps));
        }
    }

    /**
     * The director ran one of their steps and says whether it works, and what they saw when it does not (ADR-0058).
     *
     * @param step the step's number in the recipe, from 1
     */
    record ProofStepWitnessed(MissionId mission, ProofTarget target, int step, boolean works, String seen)
            implements DeliveryEvent {}

    /**
     * The director's verdict on the delivery's proof (ADR-0058), about the targets as they were.
     *
     * @param verdict {@code PROVEN} or {@code NOT_PROVEN}
     */
    record ProofVerdictGiven(MissionId mission, String verdict, List<ProofTarget> targets, String note)
            implements DeliveryEvent {
        public ProofVerdictGiven {
            targets = targets == null ? null : Collections.unmodifiableList(new ArrayList<>(targets));
        }
    }

    /**
     * The director accepted the mission although its delivery was not proven (ADR-0058), in the same command as the
     * {@code MissionCompleted} it precedes.
     *
     * @param state {@code NO_RECIPE}, {@code NOT_RUN}, {@code FAILED}, {@code NOT_PROVEN} or {@code NO_VERDICT}
     * @param token the proof state the director saw
     */
    record UnprovenDeliveryAccepted(MissionId mission, String state, String token) implements DeliveryEvent {}
}
