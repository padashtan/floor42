package dev.floor42.domain;

import dev.floor42.domain.ProofRecords.Run;
import dev.floor42.domain.ProofRecords.Verdict;
import dev.floor42.domain.ProofRecords.Witness;
import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import java.util.Optional;

/**
 * A delivery's proof (ADR-0058), reached by {@code Company.deliveries().proofs()}: Floor42's runs of the producers'
 * recipes, the director's own runs and verdict, and an acceptance without proof.
 */
public final class ProofsFacade {

    private final Proofs proofs;

    ProofsFacade(Proofs proofs) {
        this.proofs = proofs;
    }

    /** Floor42's last run of each worktree's recipe. */
    public List<Run> runs(MissionId mission) {
        return proofs.runs(mission);
    }

    /** What the director last said of each of their steps, per target. */
    public List<Witness> witnesses(MissionId mission) {
        return proofs.witnesses(mission);
    }

    public Optional<Verdict> verdict(MissionId mission) {
        return proofs.verdict(mission);
    }

    /** The proof state the director accepted the mission in, when it was not proven. */
    public Optional<String> unprovenAccepted(MissionId mission) {
        return proofs.unprovenAccepted(mission);
    }

    /** Floor42 records what it ran in the sandbox: system only, while the mission is open. */
    public List<EventEnvelope> recordRun(CommandContext ctx, MissionId id, ProofTarget target, List<ProofStep> steps) {
        return proofs.recordRun(ctx, id, target, steps);
    }

    /** The director says whether one of their steps works, and what they saw when it does not. */
    public List<EventEnvelope> witness(
            CommandContext ctx, MissionId id, ProofTarget target, int step, boolean works, String seen) {
        return proofs.witness(ctx, id, target, step, works, seen);
    }

    /** The director's verdict, PROVEN or NOT_PROVEN, about the targets as measured. */
    public List<EventEnvelope> verdict(
            CommandContext ctx, MissionId id, String verdict, List<ProofTarget> targets, String note) {
        return proofs.verdict(ctx, id, verdict, targets, note);
    }

    /** The director accepts the mission unproven: recorded in the same command as the completion. */
    public List<EventEnvelope> acceptUnproven(CommandContext ctx, MissionId id, String state, String token) {
        return proofs.acceptUnproven(ctx, id, state, token);
    }
}
