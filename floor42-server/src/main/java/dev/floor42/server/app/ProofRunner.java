package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.ProofStep;
import dev.floor42.domain.ProofTarget;
import dev.floor42.server.app.AgentReservations.Reservation;
import dev.floor42.server.app.ProofViews.ProofStepResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Runs a delivery's proof (ADR-0058). Under the mission's change lock (refused while it closes) and holding its
 * producers (refused while a session works on the mission), Floor42 copies each worktree into a folder of its own,
 * then runs the steps on the copies in the background, in the sandbox, and journals each run ({@code ProofRan}). The
 * holds last until the run ends; the copies are deleted then. The real worktrees are never run in.
 */
@ApplicationScoped
public class ProofRunner {

    private static final Logger LOG = Logger.getLogger(ProofRunner.class);

    /** One worktree ready to run: what the run is bound to, who runs what, and the copy it runs on. */
    record Prepared(ProofTarget target, List<ProofPlan.Assigned> plan, ProofSnapshot.Copy made, Path source) {

        Path copy() {
            return made.folder();
        }
    }

    /** A hold on one producer: the first holds the whole mission as the workshop does. */
    record Hold(Reservation reservation, boolean mission) {}

    @Inject
    OfficeService office;

    @Inject
    ProofReader reader;

    @Inject
    ProofSnapshot snapshot;

    @Inject
    ProofShell shell;

    @Inject
    AgentReservations reservations;

    @Inject
    Dispatcher dispatcher;

    @Inject
    DeliverablesHome home;

    @Inject
    MissionScheduler scheduler;

    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    /** What the last copy of each worktree left out, kept in memory (the journaled run holds the steps only). */
    private final Map<String, ProofSnapshot.Copy> skipped = new ConcurrentHashMap<>();

    public boolean running(MissionId id) {
        return running.contains(id.value());
    }

    /** The files the last copy of this worktree left out, with why; null when none was made since the start. */
    public ProofSnapshot.Copy lastCopy(MissionId id, String agent) {
        return skipped.get(key(id, agent));
    }

    private static String key(MissionId id, String agent) {
        return id.value() + "/" + agent;
    }

    /** Why the last run stopped before recording all it ran (a code); null when it did not. */
    public String error(MissionId id) {
        return errors.get(id.value());
    }

    /** Starts a run and returns at once; the screen follows it through {@code running}. */
    public void start(MissionId id) {
        Origin origin = reader.mission(id).origin();
        if (!shell.works())
            throw new DomainException("SANDBOX_UNAVAILABLE", "The sandbox does not work here: nothing can run");
        if (!running.add(id.value()))
            throw new DomainException("PROOF_RUNNING", "The proof of " + id + " is already running");
        List<Hold> holds = new ArrayList<>();
        List<Prepared> prepared;
        try {
            prepared = home.changing(id, () -> prepare(id, holds));
        } catch (RuntimeException e) {
            end(id, holds, List.of());
            throw e;
        }
        errors.remove(id.value());
        // Refused while the daemon stops: nothing will run, so the holds and the copies end here.
        if (!scheduler.fork("proof-" + id.value(), () -> runAll(id, origin, prepared, holds))) {
            end(id, holds, prepared);
            throw new DomainException("SHUTTING_DOWN", "Floor42 is stopping: the proof was not started");
        }
    }

    /** Holds every producer, then copies each worktree with Floor42 steps; refused before anything runs. */
    private List<Prepared> prepare(MissionId id, List<Hold> holds) {
        List<ProofReader.Current> todo =
                reader.current(id).stream().filter(ProofRunner::runs).toList();
        if (todo.isEmpty()) throw new DomainException("NOTHING_TO_RUN", "No recipe here has a step Floor42 can run");
        for (ProofReader.Current c : todo) holds.add(hold(id, c, holds.isEmpty()));
        List<Prepared> out = new ArrayList<>();
        try {
            for (ProofReader.Current held : todo) {
                // Read again once held: the target before the copy, both while nothing can change the worktree.
                ProofReader.Current c = reader.current(held.workspace());
                if (!runs(c))
                    throw new DomainException(
                            "NOTHING_TO_RUN", "The recipe of " + c.workspace().agent() + " changed");
                if (c.target() == null) throw new DomainException("GIT_FAILED", "Git did not answer for the worktree");
                var copy = snapshot.copy(
                        c.dir(), id.value() + "-" + c.workspace().agent().value());
                out.add(new Prepared(c.target(), ProofPlan.assign(c.recipe().steps(), true), copy, c.dir()));
                skipped.put(key(id, c.workspace().agent().value()), copy);
            }
        } catch (RuntimeException e) {
            out.forEach(p -> ProofFolders.delete(p.copy()));
            throw e;
        }
        return out;
    }

    /** A recipe Floor42 runs: a usable file with a step Floor42 can run; a step with a problem is never run. */
    private static boolean runs(ProofReader.Current c) {
        return c.recipe().state().equals(ProofRecipe.FOUND)
                && c.recipe().problems().isEmpty()
                && ProofPlan.assign(c.recipe().steps(), true).stream()
                        .anyMatch(a ->
                                a.runner().equals(ProofPlan.FLOOR42) && a.step().runnable());
    }

    private Hold hold(MissionId id, ProofReader.Current c, boolean first) {
        var r = first
                ? reservations.holdForWorkshop(id, c.workspace().agent())
                : reservations.reserve(c.workspace().agent());
        return new Hold(
                r.orElseThrow(() -> RefusedException.conflict(
                        "WORKTREE_BUSY",
                        c.workspace().agent() + " or the mission is at work: run the proof once idle")),
                first);
    }

    private void runAll(MissionId id, Origin origin, List<Prepared> prepared, List<Hold> holds) {
        try {
            for (Prepared p : prepared) {
                List<ProofStepResult> results =
                        ProofSteps.run(p.plan(), command -> shell.run(p.copy(), command, p.source()));
                List<ProofStep> steps = steps(p.plan(), results);
                office.execute(c -> c.deliveries()
                        .proofs()
                        .recordRun(office.context(Actor.system(), origin), id, p.target(), steps));
            }
        } catch (RuntimeException e) {
            // Handled: the screen says why the run stopped; what was recorded before stays
            LOG.warnf(e, "Mission %s: the proof stopped", id);
            errors.put(id.value(), e instanceof DomainException d ? d.code() : "RUN_FAILED");
        } finally {
            end(id, holds, prepared);
        }
    }

    private void end(MissionId id, List<Hold> holds, List<Prepared> prepared) {
        prepared.forEach(p -> ProofFolders.delete(p.copy()));
        for (Hold h : holds) {
            if (h.mission()) reservations.releaseWorkshop(id, h.reservation());
            else if (reservations.release(h.reservation())) dispatcher.staffChanged();
        }
        running.remove(id.value());
    }

    /** The journal's form of a run: the producer's step with Floor42's measure. */
    static List<ProofStep> steps(List<ProofPlan.Assigned> plan, List<ProofStepResult> results) {
        List<ProofStep> out = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            ProofPlan.Assigned a = plan.get(i);
            ProofStepResult r = results.get(i);
            out.add(new ProofStep(
                    r.index(),
                    a.step().title(),
                    a.step().command(),
                    a.runner(),
                    a.reason(),
                    r.outcome(),
                    r.failure(),
                    r.exitCode(),
                    r.timedOut(),
                    r.durationMs(),
                    cap(r.output())));
        }
        return out;
    }

    private static String cap(String output) {
        return output.length() <= ProofStep.MAX_OUTPUT
                ? output
                : "…" + output.substring(output.length() - BoundedProcess.MAX_OUTPUT);
    }
}
