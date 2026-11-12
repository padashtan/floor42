package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;

import dev.floor42.domain.ProofRecords.Run;
import dev.floor42.domain.ProofRecords.Verdict;
import dev.floor42.domain.ProofRecords.Witness;
import dev.floor42.domain.event.DeliveryEvent.ProofRan;
import dev.floor42.domain.event.DeliveryEvent.ProofStepWitnessed;
import dev.floor42.domain.event.DeliveryEvent.ProofVerdictGiven;
import dev.floor42.domain.event.DeliveryEvent.UnprovenDeliveryAccepted;
import dev.floor42.domain.event.EventEnvelope;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A delivery's proof as the journal holds it (ADR-0058): Floor42's last run of each producer's recipe, what the
 * director saw running their own steps, their verdict, and an acceptance without proof. Floor42 records its runs; only
 * the director records the rest.
 */
final class Proofs {

    static final int MAX_SEEN = 4_000;
    static final int MAX_NOTE = 4_000;
    static final Set<String> VERDICTS = Set.of("PROVEN", "NOT_PROVEN");
    static final Set<String> UNPROVEN = Set.of("NO_RECIPE", "NOT_RUN", "FAILED", "NOT_PROVEN", "NO_VERDICT");

    private final CompanyView view;
    private final Map<MissionId, Map<AgentId, Run>> runs = new HashMap<>();
    private final Map<MissionId, Map<String, Witness>> witnesses = new HashMap<>();
    private final Map<MissionId, Verdict> verdictsByMission = new HashMap<>();
    private final Map<MissionId, String> unprovenByMission = new HashMap<>();

    Proofs(CompanyView view) {
        this.view = view;
    }

    List<Run> runs(MissionId mission) {
        return List.copyOf(runs.getOrDefault(mission, Map.of()).values());
    }

    List<Witness> witnesses(MissionId mission) {
        return List.copyOf(witnesses.getOrDefault(mission, Map.of()).values());
    }

    Optional<Verdict> verdict(MissionId mission) {
        return Optional.ofNullable(verdictsByMission.get(mission));
    }

    Optional<String> unprovenAccepted(MissionId mission) {
        return Optional.ofNullable(unprovenByMission.get(mission));
    }

    List<EventEnvelope> recordRun(CommandContext ctx, MissionId id, ProofTarget target, List<ProofStep> steps) {
        require(ctx.actor().kind() == Actor.Kind.SYSTEM, Refusal.FORBIDDEN, "Only Floor42 records what it ran");
        open(ctx, id);
        require(target != null && target.wellFormed(), Refusal.INVALID_ARGUMENT, "The proof's target is malformed");
        List<ProofStep> list = steps == null ? List.of() : List.copyOf(steps);
        require(
                !list.isEmpty()
                        && list.size() <= ProofStep.MAX_STEPS
                        && list.stream().allMatch(ProofStep::wellFormed),
                Refusal.INVALID_ARGUMENT,
                "1 to " + ProofStep.MAX_STEPS + " steps, each with a known runner and outcome, capped texts");
        return view.record(ctx, id).emit(new ProofRan(id, target, list)).events();
    }

    List<EventEnvelope> witness(
            CommandContext ctx, MissionId id, ProofTarget target, int step, boolean works, String seen) {
        director(ctx, "Only the director says what they saw");
        open(ctx, id);
        require(target != null && target.wellFormed(), Refusal.INVALID_ARGUMENT, "The proof's target is malformed");
        require(step >= 1 && step <= ProofStep.MAX_STEPS, Refusal.INVALID_ARGUMENT, "No step " + step);
        String text = seen == null ? "" : seen.strip();
        require(text.length() <= MAX_SEEN, Refusal.INVALID_ARGUMENT, "At most " + MAX_SEEN + " characters");
        require(works || !text.isEmpty(), Refusal.INVALID_ARGUMENT, "Say what you see when it does not work");
        return view.record(ctx, id)
                .emit(new ProofStepWitnessed(id, target, step, works, text))
                .events();
    }

    List<EventEnvelope> verdict(
            CommandContext ctx, MissionId id, String verdict, List<ProofTarget> targets, String note) {
        director(ctx, "Only the director gives the verdict");
        open(ctx, id);
        require(verdict != null && VERDICTS.contains(verdict), Refusal.INVALID_ARGUMENT, "PROVEN or NOT_PROVEN");
        List<ProofTarget> list = targets == null ? List.of() : List.copyOf(targets);
        require(
                !list.isEmpty()
                        && list.size() <= ProofStep.MAX_STEPS
                        && list.stream().allMatch(ProofTarget::wellFormed),
                Refusal.INVALID_ARGUMENT,
                "The verdict names the worktrees it is about, as measured");
        String text = note == null ? "" : note.strip();
        require(text.length() <= MAX_NOTE, Refusal.INVALID_ARGUMENT, "At most " + MAX_NOTE + " characters");
        return view.record(ctx, id)
                .emit(new ProofVerdictGiven(id, verdict, list, text))
                .events();
    }

    List<EventEnvelope> acceptUnproven(CommandContext ctx, MissionId id, String state, String token) {
        director(ctx, "Only the director accepts a delivery without proof");
        open(ctx, id);
        require(state != null && UNPROVEN.contains(state), Refusal.INVALID_ARGUMENT, "Unknown proof state " + state);
        require(token != null && token.matches("[0-9a-f]{16}"), Refusal.INVALID_ARGUMENT, "A token of 16 hex digits");
        return view.record(ctx, id)
                .emit(new UnprovenDeliveryAccepted(id, state, token))
                .events();
    }

    private static void director(CommandContext ctx, String message) {
        require(ctx.actor().isDirector(), Refusal.FORBIDDEN, message);
    }

    private void open(CommandContext ctx, MissionId id) {
        Mission m = view.missionFor(ctx, id);
        require(!m.status.isTerminal(), Refusal.MISSION_CLOSED, id + " is over");
    }

    void on(ProofRan e, Instant at) {
        runs.computeIfAbsent(e.mission(), k -> new LinkedHashMap<>())
                .put(e.target().workspace(), new Run(e.target(), List.copyOf(e.steps()), at));
    }

    void on(ProofStepWitnessed e, Instant at) {
        witnesses
                .computeIfAbsent(e.mission(), k -> new LinkedHashMap<>())
                .put(e.target() + "#" + e.step(), new Witness(e.target(), e.step(), e.works(), e.seen(), at));
    }

    void on(ProofVerdictGiven e, Instant at) {
        verdictsByMission.put(e.mission(), new Verdict(e.verdict(), List.copyOf(e.targets()), e.note(), at));
    }

    void on(UnprovenDeliveryAccepted e) {
        unprovenByMission.put(e.mission(), e.state());
    }
}
