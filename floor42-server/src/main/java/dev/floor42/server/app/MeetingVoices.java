package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Meeting;
import dev.floor42.domain.MeetingRound;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.SpokenTurn;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.CostCap;
import dev.floor42.server.runtime.RunningSessions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Turns of speech at the director's meeting, live (ADR-0049): the round's speakers one at a time, in the director's
 * order, each a short read-only session on the meeting's own ticket, within the meeting's cost cap. Every speaker's
 * turn is recorded, heard or not, so a round always ends.
 */
@ApplicationScoped
public class MeetingVoices {

    private static final Logger LOG = Logger.getLogger(MeetingVoices.class);
    private static final Duration POLL = Duration.ofMillis(500);

    static final String CLOSED = "the director closed the meeting";
    static final String MISSION_ENDED = "the mission is no longer in progress";
    static final String HELD = "the mission is held: its budget is reached or the plan's usage limit is near";
    static final String CAP = "the meeting's cost cap is reached";
    static final String RESTART = "the daemon stopped during the round";
    static final String STOPPED = "stopped by Floor42: the director closed the meeting";

    private final OfficeService office;
    private final SessionGate gate;
    private final MissionScheduler scheduler;
    private final RunningSessions sessions;
    private final CostCap costCap;
    private final Duration patience;

    /** The round running per meeting, done once its thread has recorded every turn. */
    private final Map<String, CompletableFuture<Void>> running = new ConcurrentHashMap<>();
    /** Who speaks now per meeting: the one process a close may stop. Set under the office's lock (see speak). */
    private final Map<String, AgentId> speaking = new ConcurrentHashMap<>();
    /** Test seam: runs between a turn's start being decided and its session being launched. */
    private volatile Runnable beforeLaunch = () -> {};

    @Inject
    MeetingVoices(
            OfficeService office,
            SessionGate gate,
            MissionScheduler scheduler,
            RunningSessions sessions,
            CostCap costCap,
            @ConfigProperty(name = "floor42.reservation.patience", defaultValue = "PT2H") Duration patience) {
        this.office = office;
        this.gate = gate;
        this.scheduler = scheduler;
        this.sessions = sessions;
        this.costCap = costCap;
        this.patience = patience;
    }

    /** Runs the round just asked on its own thread; {@code seated} says when the meeting holds a speaker. */
    void start(String meeting, Predicate<AgentId> seated) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        running.put(meeting, done);
        int round = office.query(
                c -> c.meetings().find(meeting).orElseThrow().rounds().size());
        scheduler.fork("meeting-round-" + meeting, () -> {
            try {
                // This round only: a later ask has its own thread, never this one's.
                while (round(meeting).map(r -> r.number() == round).orElse(false))
                    speakGuarded(meeting, next(meeting).orElseThrow(), seated);
            } finally {
                running.remove(meeting, done);
                done.complete(null);
            }
        });
    }

    /** Test seam (LiveSeams): what runs between a turn's start being decided and its launch. */
    void beforeLaunch(Runnable pause) {
        beforeLaunch = pause;
    }

    /** True while this agent takes its turn at this meeting: its session is a turn, not its work. */
    public boolean speaking(String meeting, AgentId agent) {
        return agent.equals(speaking.get(meeting));
    }

    /**
     * Once the meeting is closed (or its mission ended) in the journal: the turn under way is stopped, and a turn whose
     * start was decided before the close but whose process is not registered yet is stopped the moment it is (the
     * speaker is barred). Returns only once the round's thread has ended, every turn recorded and no turn process left:
     * a timeout is no proof that a process ended, so the speaker is never let go before.
     */
    void stop(String meeting) {
        CompletableFuture<Void> done = running.get(meeting);
        AgentId now = speaking.get(meeting);
        if (now != null) sessions.bar(now, STOPPED);
        try {
            if (now != null) sessions.stop(now, STOPPED);
            if (done != null) awaitEnd(meeting, done);
        } finally {
            if (now != null) sessions.unbar(now);
        }
    }

    /** Waits for the round's thread, stopping the speaker's process again each second while it runs. */
    private void awaitEnd(String meeting, CompletableFuture<Void> done) {
        for (int seconds = 1; ; seconds++) {
            try {
                done.get(1, TimeUnit.SECONDS);
                return;
            } catch (TimeoutException e) {
                // Handled: the round still runs; its speaker is stopped again and the wait goes on
                AgentId now = speaking.get(meeting);
                if (now != null) sessions.stop(now, STOPPED);
                if (seconds % 60 == 0)
                    LOG.warnf("Meeting %s: its round still runs %d s after the close", meeting, seconds);
            } catch (InterruptedException e) {
                // Handled: the interrupt is kept for the caller, who stops waiting
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                // Handled: logged; the round's own thread failed and has ended
                LOG.warnf("Meeting %s: the round ended in error: %s", meeting, e.getCause());
                return;
            }
        }
    }

    /** The round's next speaker, if a round is under way. */
    private Optional<MeetingRound> round(String meeting) {
        return office.query(c -> c.meetings().find(meeting).flatMap(Meeting::roundUnderWay));
    }

    private Optional<AgentId> next(String meeting) {
        return round(meeting).flatMap(MeetingRound::next);
    }

    /** {@link #speak}, and when anything fails around the turn, the speaker recorded as not heard with the reason. */
    private void speakGuarded(String meeting, AgentId speaker, Predicate<AgentId> seated) {
        try {
            speak(meeting, speaker, seated);
        } catch (RuntimeException e) {
            // Handled: logged; the turn is recorded as not heard, so the round ends instead of staying under way
            LOG.warnf("Meeting %s: %s's turn could not be taken: %s", meeting, speaker, e.getMessage());
            int round = round(meeting).orElseThrow().number();
            if (next(meeting).map(speaker::equals).orElse(false))
                record(meeting, SpokenTurn.notHeard(round, speaker, null, "the turn failed: " + e.getMessage(), null));
        }
    }

    /** One speaker: not heard for the first reason that holds, otherwise a turn. Always recorded. */
    private void speak(String meeting, AgentId speaker, Predicate<AgentId> seated) {
        int round = round(meeting).orElseThrow().number();
        String why = obstacle(meeting, speaker, seated);
        if (why == null) why = waitForSeat(meeting, speaker, seated);
        if (why == null) why = office.query(c -> overCap(c, meeting));
        if (why != null) {
            record(meeting, SpokenTurn.notHeard(round, speaker, null, why, null));
            return;
        }
        // Decided under the office's lock, which the close takes too: a close either comes first (and no turn starts)
        // or
        // finds the speaker registered (and stops its process, even one launched after it looked). The seam right after
        // lets a test close the meeting there: a registration moved out of the lock would come too late for that close.
        String late = office.query(c -> {
            String w = obstacle(c, meeting);
            if (w == null) speaking.put(meeting, speaker);
            return w;
        });
        beforeLaunch.run();
        if (late != null) {
            record(meeting, SpokenTurn.notHeard(round, speaker, null, late, null));
            return;
        }
        try {
            record(meeting, turn(meeting, round, speaker));
        } finally {
            // Its own entry only: a later round's speaker is never cleared by this one.
            speaking.remove(meeting, speaker);
        }
    }

    /** Why the speaker cannot be heard now, whatever the wait: the meeting closed, the mission ended or is held. */
    private String obstacle(String meeting, AgentId speaker, Predicate<AgentId> seated) {
        return office.query(c -> obstacle(c, meeting));
    }

    private static String obstacle(Company c, String meeting) {
        Meeting mt = c.meetings().find(meeting).orElseThrow();
        if (!mt.open()) return CLOSED;
        MissionId m = mt.mission();
        if (c.missions()
                .find(m)
                .map(x -> x.status() != MissionStatus.IN_PROGRESS)
                .orElse(true)) return MISSION_ENDED;
        return held(c, m) ? HELD : null;
    }

    /** Budget reached, or a plan-limit question open: read from the journal, nothing asked (MissionHolds asks). */
    private static boolean held(Company c, MissionId m) {
        return c.verification().budgetReached(m)
                || c.desk().of(m).stream()
                        .anyMatch(q -> q.status() == dev.floor42.domain.QuestionStatus.OPEN
                                && (q.kind() == dev.floor42.domain.QuestionKind.PLAN_LIMIT
                                        || q.kind() == dev.floor42.domain.QuestionKind.BUDGET_REACHED));
    }

    /** Waits until the meeting holds the speaker (its work session ended), at most the reservation's patience. */
    private String waitForSeat(String meeting, AgentId speaker, Predicate<AgentId> seated) {
        Instant until = Instant.now().plus(patience);
        while (!seated.test(speaker)) {
            String why = obstacle(meeting, speaker, seated);
            if (why != null) return why;
            if (!Instant.now().isBefore(until))
                return "still finishing its work after " + patience.toMinutes() + " min";
            if (!pause()) return RESTART;
        }
        return null;
    }

    /** The least a turn may be allowed: below it, a turn is not worth starting and is not started. */
    static final double MIN_TURN_USD = dev.floor42.domain.MeetingsFacade.MIN_TURN_USD;

    /** The cap's reason when less than {@link #MIN_TURN_USD} is left of it, as the meeting is charged so far. */
    static String overCap(Company c, String meeting) {
        return left(c, meeting) < MIN_TURN_USD ? CAP : null;
    }

    /** What is left of the meeting's cap, floored to the cent: the most the next turn may be allowed. */
    static double left(Company c, String meeting) {
        Meeting mt = c.meetings().find(meeting).orElseThrow();
        double cap = mt.rounds().getLast().capUsd();
        return Math.floor((cap - c.meetings().spentUsdCharged(mt)) * 100 + 1e-9) / 100;
    }

    /** The turn's session; its answer, or the reason it gave none (a failure, a refusal mid-turn). */
    private SpokenTurn turn(String meeting, int round, AgentId speaker) {
        Double budget = null;
        try {
            SessionSpec spec = office.query(c -> spec(c, meeting, round, speaker));
            budget = spec.capUsd();
            Unheld.Answer a = Unheld.answerOf(gate.runTurn(spec));
            String session = office.query(c -> lastTurnSession(c, meeting, speaker));
            return a.heard()
                    ? SpokenTurn.heard(round, speaker, session, a.text(), spec.capUsd())
                    : SpokenTurn.notHeard(round, speaker, session, a.why(), budget);
        } catch (RuntimeException e) {
            // Handled: the turn is recorded as not heard with the reason, its budget charged
            LOG.warnf("Meeting %s: %s's turn failed: %s", meeting, speaker, e.getMessage());
            String session = office.query(c -> lastTurnSession(c, meeting, speaker));
            return SpokenTurn.notHeard(round, speaker, session, "the turn failed: " + e.getMessage(), budget);
        }
    }

    private SessionSpec spec(Company c, String meeting, int round, AgentId speaker) {
        Meeting mt = c.meetings().find(meeting).orElseThrow();
        var agent = c.staff().find(speaker).orElseThrow();
        String folder = MeetingFolders.of(c, mt.mission(), speaker);
        String brief = MeetingTurnBrief.of(c, mt, mt.rounds().get(round - 1), agent, folder);
        double budget = Math.min(left(c, meeting), costCap.sessionCapUsd(mt.mission()));
        return SessionGate.spec(
                        c,
                        mt.mission(),
                        mt.ticket(),
                        agent,
                        java.nio.file.Path.of(folder),
                        brief,
                        MissionGovernor.TURN_TOOLS)
                .capped(Math.floor(budget * 100 + 1e-9) / 100);
    }

    /**
     * The speaker's session of this turn, if one started: on the meeting's ticket, since the round was asked, named by
     * no turn already. An earlier round's session is never taken for this one.
     */
    private static String lastTurnSession(Company c, String meeting, AgentId speaker) {
        Meeting mt = c.meetings().find(meeting).orElseThrow();
        MeetingRound round = mt.rounds().getLast();
        java.util.Set<String> named = mt.rounds().stream()
                .flatMap(r -> r.turns().stream())
                .map(dev.floor42.domain.MeetingTurn::session)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        return c.sessions().of(mt.mission()).stream()
                .filter(s -> s.agent().equals(speaker) && s.ticket().equals(mt.ticket()))
                .filter(s -> !s.startedAt().isBefore(round.askedAt()) && !named.contains(s.id()))
                .reduce((a, b) -> b)
                .map(s -> s.id())
                .orElse(null);
    }

    private void record(String meeting, SpokenTurn turn) {
        office.execute(c -> c.meetings().recordTurn(office.context(Actor.system(), Origin.REAL), meeting, turn));
    }

    /**
     * At start-up, before anything runs: every round left unfinished, of any meeting whatever its mission's state, has its
     * waiting speakers recorded not heard, and a turn's session left open is ended as interrupted (no "Retry").
     */
    void closeOut() {
        for (String meeting : office.query(c -> c.meetings().all().stream()
                .filter(m -> m.roundUnderWay().isPresent())
                .map(Meeting::id)
                .toList())) {
            endOpenTurns(meeting);
            // The round's next speaker may have been launched before the crash, journaled or not: it is charged what
            // it would have been allowed (an upper bound). The speakers after it were not reached: turns go one at a
            // time.
            boolean first = true;
            while (next(meeting).isPresent()) {
                int round = round(meeting).orElseThrow().number();
                AgentId speaker = next(meeting).get();
                String session = office.query(c -> lastTurnSession(c, meeting, speaker));
                Double charged = first ? budgetOf(meeting) : null;
                record(meeting, SpokenTurn.notHeard(round, speaker, session, RESTART, charged));
                first = false;
            }
        }
    }

    /**
     * What an interrupted turn is charged: the most its session could have been allowed, as {@link #spec} computes it.
     * A missing session record is no proof that nothing ran; nothing is charged when nothing could have started.
     */
    private Double budgetOf(String meeting) {
        return office.query(c -> overCap(c, meeting) != null
                ? null
                : Math.min(
                        left(c, meeting),
                        costCap.sessionCapUsd(
                                c.meetings().find(meeting).orElseThrow().mission())));
    }

    private void endOpenTurns(String meeting) {
        var open = office.query(c -> {
            Meeting mt = c.meetings().find(meeting).orElseThrow();
            return c.sessions().of(mt.mission()).stream()
                    .filter(s -> s.isOpen() && s.ticket().equals(mt.ticket()))
                    .map(s -> s.id())
                    .toList();
        });
        for (String s : open) {
            try {
                office.execute(c -> c.sessions()
                        .end(
                                office.context(Actor.system(), Origin.REAL),
                                s,
                                true,
                                dev.floor42.domain.AgentSession.INTERRUPTED,
                                null,
                                null,
                                null,
                                null));
            } catch (DomainException e) {
                // Handled: logged; the session was ended already
                LOG.infof("Meeting %s: turn session %s already ended (%s)", meeting, s, e.getMessage());
            }
        }
    }

    private static boolean pause() {
        try {
            Thread.sleep(POLL);
            return true;
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller, who stops waiting
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
