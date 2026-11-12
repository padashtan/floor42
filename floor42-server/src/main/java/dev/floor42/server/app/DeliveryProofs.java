package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.ProofRecords;
import dev.floor42.domain.ProofTarget;
import dev.floor42.server.app.ProofViews.ProofAcceptanceView;
import dev.floor42.server.app.ProofViews.ProofRunView;
import dev.floor42.server.app.ProofViews.ProofStepResult;
import dev.floor42.server.app.ProofViews.ProofStepView;
import dev.floor42.server.app.ProofViews.ProofVerdictView;
import dev.floor42.server.app.ProofViews.ProofView;
import dev.floor42.server.app.ProofViews.ProofWitnessView;
import dev.floor42.server.app.ProofViews.ProofWorktree;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A mission's delivery proof as it stands now (ADR-0058): each producer's recipe and code, Floor42's last journaled run,
 * what the director said and their verdict, each bound to the recipe and code it was about. Records the director's
 * reports and verdict; Floor42's runs are {@link ProofRunner}'s.
 */
@ApplicationScoped
public class DeliveryProofs {

    @Inject
    OfficeService office;

    @Inject
    ProofReader reader;

    @Inject
    ProofShell shell;

    @Inject
    ProofRunner runner;

    public ProofView view(MissionId id) {
        Mission m = reader.mission(id);
        boolean works = shell.works();
        List<ProofReader.Current> now = reader.current(id);
        List<ProofWorktree> worktrees =
                now.stream().map(c -> worktree(id, c, works)).toList();
        int director = (int) worktrees.stream()
                .flatMap(w -> w.steps().stream())
                .filter(s -> s.runner().equals(ProofPlan.DIRECTOR))
                .count();
        return new ProofView(
                id.value(),
                m.origin().name(),
                works,
                runner.running(id),
                runner.error(id),
                ProofStatus.of(worktrees),
                director,
                worktrees,
                verdictView(id, targets(now)),
                ProofStatus.targets(
                        now.stream().map(ProofReader.Current::target).toList()),
                office.query(c -> c.deliveries().proofs().unprovenAccepted(id)).orElse(null));
    }

    /** What an acceptance asks about now, and the token that names it (ADR-0058). */
    public ProofAcceptanceView acceptance(MissionId id) {
        ProofView v = view(id);
        String state = ProofStatus.unproven(v);
        return new ProofAcceptanceView(state, ProofStatus.token(state, List.of(v.targets()), v.verdict()));
    }

    /** The director says whether one of their steps works on the recipe and code as they are now. */
    public ProofView witness(MissionId id, String agent, int step, boolean works, String seen, String saw) {
        ProofReader.Current c = producer(id, agent);
        ProofTarget target = known(c);
        same(ProofStatus.target(target), saw);
        boolean theirs = ProofPlan.assign(c.recipe().steps(), shell.works()).stream()
                .anyMatch(a -> a.step().index() == step && a.runner().equals(ProofPlan.DIRECTOR));
        if (!theirs) throw new DomainException("NOT_A_DIRECTOR_STEP", "Step " + step + " is not one of yours");
        office.execute(c2 -> c2.deliveries()
                .proofs()
                .witness(office.context(Actor.director(), reader.mission(id).origin()), id, target, step, works, seen));
        return view(id);
    }

    /** The director's verdict, about every producer's recipe and code as they are now. */
    public ProofView verdict(MissionId id, String verdict, String note, String saw) {
        List<ProofReader.Current> now = reader.current(id);
        List<ProofTarget> targets = now.stream().map(this::known).toList();
        same(ProofStatus.targets(targets), saw);
        office.execute(c -> c.deliveries()
                .proofs()
                .verdict(office.context(Actor.director(), reader.mission(id).origin()), id, verdict, targets, note));
        return view(id);
    }

    ProofReader.Current producer(MissionId id, String agent) {
        return reader.current(id).stream()
                .filter(x -> x.workspace().agent().value().equals(agent))
                .findFirst()
                .orElseThrow(() -> new DomainException("UNKNOWN_WORKSPACE", agent + " has no worktree on " + id));
    }

    /**
     * What the director saw is what is there now (ADR-0058): a report or a verdict about a recipe or code that changed
     * since the page was read is refused (409 PROOF_STALE), never recorded against the new one.
     */
    private static void same(String now, String saw) {
        if (!now.equals(saw))
            throw RefusedException.conflict(
                    "PROOF_STALE", "The recipe or the code changed since you read it: read it again");
    }

    private ProofTarget known(ProofReader.Current c) {
        ProofTarget t = c.target();
        if (t == null)
            throw new DomainException(
                    "PROOF_UNKNOWN", c.workspace().agent() + ": no recipe, or git did not answer for the worktree");
        return t;
    }

    private ProofWorktree worktree(MissionId id, ProofReader.Current c, boolean works) {
        ProofTarget target = c.target();
        ProofSnapshot.Copy copy = runner.lastCopy(id, c.workspace().agent().value());
        List<ProofRecords.Run> runs = office.query(q -> q.deliveries().proofs().runs(id));
        ProofRunView run = runs.stream()
                .filter(r -> r.target().workspace().equals(c.workspace().agent()))
                .findFirst()
                .map(r -> run(r, target))
                .orElse(null);
        List<ProofWitnessView> said = office.query(q -> q.deliveries().proofs().witnesses(id)).stream()
                .filter(w -> w.target().equals(target))
                .map(w -> new ProofWitnessView(w.step(), w.works(), w.seen(), w.at().toString()))
                .toList();
        return new ProofWorktree(
                c.workspace().agent().value(),
                c.workspace().path(),
                c.recipe().state(),
                c.recipe().digest(),
                c.code() == null ? null : c.code().head(),
                c.code() == null ? null : c.code().changesDigest(),
                c.recipe().problems(),
                ProofPlan.assign(c.recipe().steps(), works).stream()
                        .map(DeliveryProofs::step)
                        .toList(),
                run,
                said,
                target == null ? null : ProofStatus.target(target),
                copy == null ? List.of() : copy.skipped(),
                copy == null ? 0 : copy.skippedCount());
    }

    private static ProofRunView run(ProofRecords.Run r, ProofTarget now) {
        List<ProofStepResult> steps = r.steps().stream()
                .map(s -> new ProofStepResult(
                        s.step(),
                        s.command(),
                        s.outcome(),
                        s.failure(),
                        s.exitCode(),
                        s.timedOut(),
                        s.durationMs(),
                        s.output()))
                .toList();
        return new ProofRunView(
                r.at().toString(),
                r.target().recipeDigest(),
                r.target().head(),
                r.target().changesDigest(),
                !r.target().equals(now),
                steps);
    }

    private ProofVerdictView verdictView(MissionId id, Set<ProofTarget> now) {
        return office.query(c -> c.deliveries().proofs().verdict(id))
                .map(v -> new ProofVerdictView(
                        v.verdict(),
                        v.note(),
                        v.at().toString(),
                        !now.contains(null) && Set.copyOf(v.targets()).equals(now)))
                .orElse(null);
    }

    private static Set<ProofTarget> targets(List<ProofReader.Current> now) {
        return now.stream().map(ProofReader.Current::target).collect(Collectors.toSet());
    }

    private static ProofStepView step(ProofPlan.Assigned a) {
        ProofRecipe.Step s = a.step();
        return new ProofStepView(
                s.index(),
                s.title(),
                s.command(),
                s.expectExit(),
                s.expectOutput(),
                a.runner(),
                a.reason(),
                s.problems());
    }
}
