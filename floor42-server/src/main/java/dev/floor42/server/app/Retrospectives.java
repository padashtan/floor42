package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Company;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PlanUsage;
import dev.floor42.domain.Retro;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCompleted;
import dev.floor42.domain.workflow.MissionInterpreter;
import dev.floor42.domain.workflow.WorkflowInterpreter.AfterEnd;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The end-of-mission retrospective (ADR-0020). When a real mission ends, completed or cancelled, the agents who had a
 * session on it meet at the table: each speaks once from the facts Floor42 composed from the journal, then the chair
 * concludes and proposes lessons. Only the director makes a lesson binding; accepted lessons reach later briefs.
 *
 * <p>Every session is short, read-only by construction (no tool at all), on a light model, and goes through the same
 * runtime, hooks and journal as any other. A session the plan's usage window would likely cut waits for the reset;
 * a mission whose budget is spent gets no retrospective session, and the record says so.
 */
@ApplicationScoped
public class Retrospectives {

    private static final Logger LOG = Logger.getLogger(Retrospectives.class);
    private final MissionScheduler scheduler;
    private final OfficeService office;
    /** Every live session goes through the gate's origin rule (ADR-0045); a retrospective's holds are its own. */
    private final SessionGate gate;

    private final Workspaces workspaces;
    private final LiveMissions live;
    /** Whether an ended mission holds its retrospective is the workflow's say (ADR-0046). */
    private final MissionInterpreter workflow;
    /** The retrospective model and the plan-limit threshold in force (a light model keeps the cost low). */
    private final OfficeSettings settings;
    /** The mission's delegation lives until its retrospective has ended (IAM mode; a no-op otherwise). */
    private final RetroDelegations delegations;

    private final long resumeMarginSeconds;
    private final boolean enabled; // off only in tests that drive the runtime by hand
    private final boolean retroEnabled; // on by default; off in the tests that do not check retrospectives

    @Inject
    Retrospectives(
            MissionScheduler scheduler,
            OfficeService office,
            SessionGate gate,
            Workspaces workspaces,
            LiveMissions live,
            MissionInterpreter workflow,
            OfficeSettings settings,
            RetroDelegations delegations,
            @ConfigProperty(name = "floor42.plan-limit.resume-margin-seconds", defaultValue = "60")
                    long resumeMarginSeconds,
            @ConfigProperty(name = "floor42.live-missions.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "floor42.retro.enabled", defaultValue = "true") boolean retroEnabled) {
        this.scheduler = scheduler;
        this.office = office;
        this.gate = gate;
        this.workspaces = workspaces;
        this.live = live;
        this.workflow = workflow;
        this.settings = settings;
        this.delegations = delegations;
        this.resumeMarginSeconds = resumeMarginSeconds;
        this.enabled = enabled;
        this.retroEnabled = retroEnabled;
    }

    /** How long to wait between two looks when a participant is busy elsewhere. */
    static final Duration BUSY_POLL = Duration.ofSeconds(2);
    /** One thread per retrospective running now. */
    private final Set<MissionId> running = ConcurrentHashMap.newKeySet();

    /** Retrospectives holding their next turn until the usage window resets, and until when (for the screen). */
    private final java.util.Map<MissionId, Instant> waiting = new ConcurrentHashMap<>();

    /** When the next turn of this retrospective will run, if it waits for the plan's usage window to reset. */
    public java.util.Optional<Instant> waitingUntil(MissionId m) {
        return java.util.Optional.ofNullable(waiting.get(m));
    }

    /** The director closes it now: the turns still due are recorded as not heard. */
    public void closeNow(MissionId m) {
        office.execute(c -> c.retros().closeNow(ctx(Actor.director()), m));
        waiting.remove(m);
    }

    void listen(@Observes StartupEvent ignored) {
        if (!enabled || !retroEnabled) return;
        // Subscribers run under the service lock: only start threads here, never work.
        office.subscribe(this::react);
        // A retrospective cut by a restart goes on from what the journal says is next.
        scheduler.fork("retro-recover", () -> {
            List<MissionId> open = office.query(c -> c.retros().openRetros().stream()
                    .map(Retro::mission)
                    .filter(m -> c.missions()
                            .find(m)
                            .map(x -> x.origin() == Origin.REAL)
                            .orElse(false))
                    .toList());
            for (MissionId m : open) {
                try {
                    run(m);
                } catch (RuntimeException e) {
                    // Handled: one mission's unreadable workflow must not stop the others from resuming
                    LOG.errorf(e, "Mission %s: its retrospective could not be resumed", m);
                }
            }
        });
    }

    private void react(List<EventEnvelope> events) {
        for (EventEnvelope e : events) {
            MissionId ended = ended(e);
            if (ended != null && holdsRetro(e.origin(), ended)) scheduler.fork("retro-" + ended, () -> open(ended));
        }
    }

    /** Whether this ended mission holds its retrospective: the one decision, read by the delegation's owner too. */
    boolean holdsRetro(Origin origin, MissionId m) {
        return active()
                && origin == Origin.REAL
                && office.query(c -> workflow.afterEnd(c, m) instanceof AfterEnd.HoldRetro);
    }

    /**
     * The mission this event ends with a retrospective due, or null. A cancelled mission holds none: it opens nothing,
     * so no retro ticket is left open. A restore (ADR-0054) ends nothing: a paid session starts only on the
     * director's own click ({@link RetroConvening}).
     */
    private static MissionId ended(EventEnvelope e) {
        if (e.event() instanceof MissionCompleted c) return c.mission();
        return null;
    }

    /** Whether retrospectives run in this daemon at all (both switches on). */
    boolean active() {
        return enabled && retroEnabled;
    }

    /** Convenes those who worked on the mission; none held (nobody worked on it, or refused): the delegation ends. */
    void open(MissionId m) {
        try {
            if (openNow(m)) {
                run(m);
                return;
            }
        } catch (RuntimeException e) {
            // Handled: logged; the mission has no retrospective, nothing else depends on it
            if (e instanceof DomainException d && "MISSION_ARCHIVED".equals(d.code())) {
                // Expected: the director archived it before Floor42 convened the team (ADR-0054); the director may
                // convene it once it is restored.
                LOG.infof("Mission %s was archived before its retrospective: none is convened", m);
            } else {
                LOG.errorf(e, "Mission %s: the retrospective could not be convened", m);
            }
        }
        delegations.release(m);
    }

    /** Records the retrospective's opening; false when nobody worked on the mission. Throws the domain's refusal. */
    boolean openNow(MissionId m) {
        List<AgentId> participants = office.query(c -> RetroCast.participants(c, m));
        if (participants.isEmpty()) return false;
        AgentId chair = office.query(c -> RetroCast.chairOf(c, participants));
        String facts =
                office.query(c -> Briefs.retroFacts(c, c.missions().find(m).orElseThrow()));
        office.execute(c -> c.retros().open(ctx(Actor.system()), m, chair, participants, facts));
        return true;
    }

    /** Runs every turn still due: each participant once, then the chair's conclusion. */
    void run(MissionId m) {
        // Every path asks the mission's workflow, the restart's included: an unreadable pin runs nothing (ADR-0050).
        if (!(office.query(c -> workflow.afterEnd(c, m)) instanceof AfterEnd.HoldRetro)) {
            LOG.warnf("Mission %s: its workflow holds no retrospective this build can run; none is run", m);
            delegations.release(m);
            return;
        }
        if (!running.add(m)) return;
        boolean interrupted = false;
        try {
            while (step(m)) {
                // one turn after another, until none is due
            }
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller; the next start goes on from what the journal says is next
            interrupted = true;
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // Handled: logged; the retrospective stays open and the delegation ends below (fail closed)
            LOG.errorf(e, "Mission %s: the retrospective stopped", m);
        } finally {
            running.remove(m);
            delegations.ended(m, interrupted);
        }
    }

    /** The turn due, read under the service lock (the contributions are a live map). */
    private record Turn(boolean concluding, AgentId speaker) {}

    /** Runs the turn due, or records why it cannot be heard; false once nothing more is due. */
    private boolean step(MissionId m) throws InterruptedException {
        Turn due = office.query(c -> c.retros()
                .find(m)
                .filter(x -> !x.concluded())
                .map(x -> x.pending().isEmpty()
                        ? new Turn(true, x.chair())
                        : new Turn(false, x.pending().getFirst()))
                .orElse(null));
        if (due == null || spendsNothing(m)) return false;
        AgentId speaker = due.speaker();
        if (office.query(c -> c.staff().find(speaker).map(Agent::dismissed).orElse(true))) {
            unheard(
                    m,
                    due,
                    speaker + (due.concluding() ? " has left the company before concluding" : " has left the company"));
            return true;
        }
        waitForTheWindow(m);
        // The director may have closed it meanwhile.
        if (office.query(c -> c.retros().find(m).map(Retro::concluded).orElse(true))) return false;
        AgentReservations.Reservation reservation;
        try {
            reservation = live.await(speaker, live.reservationPatience());
        } catch (AgentReservations.ReservationTimeout e) {
            // Handled: busy elsewhere for too long: the turn is recorded as not heard, never waited for forever
            unheard(m, due, e.getMessage());
            return true;
        }
        try {
            turn(m, speaker, due.concluding());
        } finally {
            live.release(reservation);
        }
        return true;
    }

    /** Why no turn is run any more on a cancelled mission (ADR-0020 keeps its facts sheet, at no cost). */
    static final String CANCELLED = "the mission was cancelled: no retrospective session was run";

    /**
     * A cancelled mission, or one whose budget is spent, runs no retrospective session: each turn still due is recorded
     * as not heard, with the reason, and no lesson is drawn. Restarts and old journals included.
     */
    private boolean spendsNothing(MissionId m) {
        String why = office.query(c -> c.missions()
                        .find(m)
                        .map(x -> x.status() == MissionStatus.CANCELLED)
                        .orElse(false))
                ? CANCELLED
                : office.query(c -> c.verification().budgetReached(m))
                        ? "the mission's budget is spent: no retrospective session was run"
                        : null;
        if (why == null) return false;
        closeUnheard(m, why);
        return true;
    }

    private void unheard(MissionId m, Turn due, String reason) {
        if (due.concluding()) conclude(m, null, reason);
        else skip(m, due.speaker(), reason);
    }

    private void turn(MissionId m, AgentId speaker, boolean concluding) {
        SessionSpec spec = office.query(c -> turnSpec(c, m, speaker, concluding));
        settle(m, speaker, concluding, gate.runUnheld(spec));
    }

    /** No tool at all: everything the agent needs is in the brief, and nothing can change anything. */
    private SessionSpec turnSpec(Company c, MissionId m, AgentId speaker, boolean concluding) {
        Mission mission = c.missions().find(m).orElseThrow();
        Retro r = c.retros().find(m).orElseThrow();
        Agent a = c.staff().find(speaker).orElseThrow();
        String brief = concluding ? Briefs.retroChair(c, mission, r, a) : Briefs.retroParticipant(c, mission, r, a);
        return new SessionSpec(
                m,
                r.ticket(),
                speaker,
                Origin.REAL,
                workspaces.research(m).toString(),
                Briefs.inLanguage(c, brief),
                settings.retroModel(),
                "low",
                List.of());
    }

    /** What the session gave back is recorded as said; when nothing was said, the record gives the true reason. */
    void settle(MissionId m, AgentId speaker, boolean concluding, Unheld result) {
        Unheld.Answer answer = Unheld.answerOf(result);
        if (!answer.heard()) {
            if (concluding) conclude(m, null, "the chair could not conclude: " + answer.why());
            else skip(m, speaker, answer.why());
            return;
        }
        try {
            write(m, speaker, concluding, answer.text());
        } catch (DomainException e) {
            // Handled: The answer could not be recorded as said (too many lessons, for instance): say so, never rewrite
            // it.
            if (concluding) conclude(m, null, "the chair's conclusion was refused: " + e.getMessage());
            else skip(m, speaker, "what it said was refused: " + e.getMessage());
        }
    }

    private void write(MissionId m, AgentId speaker, boolean concluding, String text) {
        if (concluding) {
            Briefs.RetroConclusion out = Briefs.retroConclusion(text);
            office.execute(c ->
                    c.retros().conclude(ctx(Actor.agent(speaker)), m, out.conclusions(), text, null, out.lessons()));
        } else {
            office.execute(c -> c.retros()
                    .contribute(
                            ctx(Actor.agent(speaker)),
                            m,
                            Briefs.retroLines(text, "WENT WELL"),
                            Briefs.retroLines(text, "WENT BADLY"),
                            text));
        }
    }

    /** Ends a retrospective nobody can speak in any more: the silence of each pending participant is recorded. */
    private void closeUnheard(MissionId m, String reason) {
        List<AgentId> pending =
                office.query(c -> c.retros().find(m).orElseThrow().pending());
        pending.forEach(a -> skip(m, a, reason));
        conclude(m, null, reason);
    }

    private void skip(MissionId m, AgentId agent, String reason) {
        office.execute(c -> c.retros().skip(ctx(Actor.system()), m, agent, reason));
    }

    private void conclude(MissionId m, String text, String reason) {
        office.execute(c -> c.retros().conclude(ctx(Actor.system()), m, List.of(), text, reason, List.of()));
    }

    /** A session the usage window would likely cut waits for its reset: the retrospective is never urgent. */
    private void waitForTheWindow(MissionId m) throws InterruptedException {
        java.util.Optional<PlanUsage.Risk> risk = office.query(c ->
                c.verification().planLimitRisk(settings.retroModel(), settings.planLimitThreshold(), Instant.now()));
        if (risk.isEmpty()) return;
        Duration wait = Duration.between(Instant.now(), risk.get().resetsAt()).plusSeconds(resumeMarginSeconds);
        LOG.infof(
                "Mission %s: the retrospective waits for the usage window to reset (%s)",
                m, risk.get().resetsAt());
        if (wait.isNegative()) return;
        waiting.put(m, Instant.now().plus(wait));
        try {
            // Wakes early when the director closes the retrospective meanwhile.
            Instant until = Instant.now().plus(wait);
            while (Instant.now().isBefore(until) && waiting.containsKey(m)) Thread.sleep(BUSY_POLL);
        } finally {
            waiting.remove(m);
        }
    }

    private CommandContext ctx(Actor actor) {
        return office.context(actor, Origin.REAL);
    }
}
