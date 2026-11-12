package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.Company;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Meeting;
import dev.floor42.domain.MeetingNoteUse;
import dev.floor42.domain.MeetingPlace;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.MeetingEvent.MeetingCalled;
import dev.floor42.domain.event.MissionEvent.MissionCancelled;
import dev.floor42.domain.event.MissionEvent.MissionCompleted;
import dev.floor42.server.app.AgentReservations.Reservation;
import dev.floor42.server.runtime.RunningSessions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The director's meeting at the table, live (ADR-0047). Calling it holds each attendee: a free one at once (a
 * reservation, so no work of any kind starts for it), a busy one once its session has ended. That session's next tool
 * call is refused by {@link MeetingHold}; the call under way finishes; a session still running after the wind-down is
 * stopped between two calls, never in the middle of one. Closing releases every hold, asks for {@code --resume} of a
 * stopped session when it is measured safe, and the dispatcher gives the attendees their work back.
 */
@ApplicationScoped
public class DirectorMeetings {

    private static final Logger LOG = Logger.getLogger(DirectorMeetings.class);
    private static final Duration POLL = Duration.ofMillis(500);

    /** Written in the stopped session's notes. */
    static final String STOPPED =
            "stopped by Floor42 between two tool calls: the director called the agent to a meeting";

    private final OfficeService office;
    private final AgentReservations reservations;
    private final RunningSessions sessions;
    private final MissionScheduler scheduler;
    private final Dispatcher dispatcher;
    private final SessionContinuity continuity;
    private final HeldSessions live;
    private final MeetingVoices voices;
    private final Duration windDown;

    /** The holds of each open meeting, released at its close. */
    private final Map<String, List<Reservation>> held = new ConcurrentHashMap<>();
    /** The work whose session Floor42 stopped, per meeting and attendee: the mission and ticket it ran on. */
    private final Map<String, Map<AgentId, Stopped>> stoppedSessions = new ConcurrentHashMap<>();
    /** The subscription that frees the attendees of a mission ending during its meeting, made once. */
    private final java.util.concurrent.atomic.AtomicBoolean listening = new java.util.concurrent.atomic.AtomicBoolean();

    /** Where a stopped session ran: its continuation is looked for there, never on another mission. */
    private record Stopped(MissionId mission, TicketId ticket) {}

    @Inject
    DirectorMeetings(
            OfficeService office,
            AgentReservations reservations,
            RunningSessions sessions,
            MissionScheduler scheduler,
            Dispatcher dispatcher,
            SessionContinuity continuity,
            HeldSessions live,
            MeetingVoices voices,
            @ConfigProperty(name = "floor42.meeting.wind-down", defaultValue = "PT2M") Duration windDown) {
        this.voices = voices;
        this.office = office;
        this.reservations = reservations;
        this.sessions = sessions;
        this.scheduler = scheduler;
        this.dispatcher = dispatcher;
        this.continuity = continuity;
        this.live = live;
        this.windDown = windDown;
    }

    /** Calls the meeting (the domain's refusals apply), then gathers each attendee on its own thread. */
    public String call(MissionId mission, List<AgentId> attendees, String agenda) {
        return call(mission, attendees, agenda, MeetingPlace.TABLE);
    }

    /** As {@link #call}, at the table the director chose (ADR-0049). */
    public String call(MissionId mission, List<AgentId> attendees, String agenda, MeetingPlace place) {
        String id =
                office.execute(c -> c.meetings().call(director(c, mission), mission, attendees, agenda, place)).stream()
                        .map(e -> e.event())
                        .filter(MeetingCalled.class::isInstance)
                        .map(e -> ((MeetingCalled) e).meeting())
                        .findFirst()
                        .orElseThrow();
        hold(id);
        return id;
    }

    /** At every start, live missions or not: the subscription below holds whoever starts the daemon. */
    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent ignored) {
        listen();
    }

    /** A mission that ends during a meeting frees its attendees at once (Meetings.attending ignores it already). */
    void listen() {
        if (listening.getAndSet(true)) return;
        office.subscribe(events -> {
            if (events.stream()
                    .anyMatch(e -> e.event() instanceof MissionCompleted || e.event() instanceof MissionCancelled))
                scheduler.fork("meeting-mission-ended", this::releaseEnded);
        });
    }

    /**
     * After a restart, the meetings the journal says are open hold their attendees again: reserved at once (nothing
     * runs yet at start-up), so neither the dispatcher nor a retrospective or a staffing review takes one. Called by
     * {@link LiveStartup} before the recovery resumes any work.
     */
    public void regather() {
        listen();
        // A round the last daemon left unfinished ends first: its waiting speakers were not heard (ADR-0049).
        voices.closeOut();
        List<String> open = office.query(c -> c.meetings().all().stream()
                .filter(Meeting::open)
                .filter(m -> c.missions()
                        .find(m.mission())
                        .map(x -> !x.status().isTerminal())
                        .orElse(false))
                .map(Meeting::id)
                .toList());
        open.forEach(this::hold);
    }

    /** Each attendee reserved now when free, otherwise as soon as its session has ended. */
    private void hold(String meeting) {
        held.putIfAbsent(meeting, new ArrayList<>());
        for (AgentId a :
                office.query(c -> c.meetings().find(meeting).orElseThrow().attendees())) {
            Optional<Reservation> now = reservations.reserveForMeeting(a);
            if (now.isPresent()) keep(meeting, now.get());
            else scheduler.fork("meeting-" + meeting + "-" + a, () -> gather(meeting, a));
        }
    }

    /** True while this meeting holds the agent's reservation: nothing else can reserve it. */
    public synchronized boolean holds(String meeting, AgentId agent) {
        List<Reservation> mine = held.get(meeting);
        return mine != null && mine.stream().anyMatch(r -> r.agent().equals(agent));
    }

    /** The director says something at the meeting, to one attendee or to all. */
    public void note(String meeting, AgentId attendee, String text, MeetingNoteUse use) {
        MissionId m = missionOf(meeting);
        office.execute(c -> c.meetings().note(director(c, m), meeting, attendee, text, use));
    }

    /**
     * The director asks these attendees for their view, in this order, within a cap for the whole meeting (ADR-0049):
     * the round runs on its own thread, each speaker once the meeting holds it.
     */
    public void askForViews(String meeting, List<AgentId> speakers, double capUsd, String question) {
        MissionId m = missionOf(meeting);
        office.execute(c -> c.meetings().askForViews(director(c, m), meeting, speakers, capUsd, question));
        voices.start(meeting, a -> holds(meeting, a));
    }

    /**
     * Closes the meeting: a turn under way is stopped and its round recorded before the holds go (so no work starts
     * under a turn), a stopped session is continued when safe, the attendees go back to work.
     */
    public void close(String meeting) {
        MissionId m = missionOf(meeting);
        office.execute(c -> c.meetings().close(director(c, m), meeting));
        voices.stop(meeting);
        release(meeting);
        Map<AgentId, Stopped> cut =
                Optional.ofNullable(stoppedSessions.remove(meeting)).orElse(Map.of());
        cut.forEach(this::continueWhenSafe);
        scheduler.fork("meeting-closed-" + meeting, dispatcher::staffChanged);
    }

    /** Who is still finishing the call under way: their work session runs (a turn of speech is no work). */
    public boolean finishing(String meeting, AgentId agent) {
        return sessions.running(agent) && !voices.speaking(meeting, agent);
    }

    private void gather(String meeting, AgentId agent) {
        Instant until = Instant.now().plus(windDown);
        while (open(meeting)) {
            Optional<Reservation> r = reservations.reserveForMeeting(agent);
            if (r.isPresent()) {
                keep(meeting, r.get());
                return;
            }
            if (!Instant.now().isBefore(until)) stopBetweenCalls(meeting, agent);
            if (!pause()) return;
        }
    }

    /**
     * Stops the agent's session only when it runs through the gate (so a retrospective turn or a staffing review is
     * never cut) and no call it was allowed is still running: never in the middle of a tool call.
     */
    private void stopBetweenCalls(String meeting, AgentId agent) {
        Optional<MeetingHold> hold = live.of(agent);
        if (hold.isEmpty() || hold.get().toolRunning()) return;
        if (!sessions.stop(agent, STOPPED)) return;
        var spec = hold.get().spec();
        stoppedSessions
                .computeIfAbsent(meeting, k -> new ConcurrentHashMap<>())
                .put(agent, new Stopped(spec.mission(), spec.ticket()));
        LOG.infof("Meeting %s: %s did not end its turn within %s, its session was stopped", meeting, agent, windDown);
    }

    /** The holds of meetings whose mission has ended go: those meetings hold nobody any more. */
    private void releaseEnded() {
        for (String meeting : List.copyOf(held.keySet())) {
            if (open(meeting)) continue;
            // A turn under way is stopped and recorded before its speaker can be given other work (ADR-0049).
            voices.stop(meeting);
            release(meeting);
        }
        dispatcher.staffChanged();
    }

    private synchronized void keep(String meeting, Reservation r) {
        List<Reservation> mine = held.get(meeting);
        if (mine != null && open(meeting)) mine.add(r);
        else reservations.release(r);
    }

    private synchronized void release(String meeting) {
        List<Reservation> mine = held.remove(meeting);
        if (mine != null) mine.forEach(reservations::release);
    }

    /** The agent's latest session, stopped for the meeting, continues with {@code --resume} when measured safe. */
    private void continueWhenSafe(AgentId agent, Stopped where) {
        Optional<AgentSession> latest = office.query(c -> latestSession(c, agent, where));
        if (latest.isEmpty()) return;
        AgentSession s = latest.get();
        boolean safe = office.query(c -> continuity.assess(c, s).possible());
        if (!safe) return;
        try {
            office.execute(
                    c -> c.sessions().requestContinuation(office.context(Actor.director(), Origin.REAL), s.id()));
        } catch (DomainException e) {
            // Handled: the next session starts fresh from the brief, which restates the meeting.
            LOG.infof("Meeting: %s cannot be continued (%s), its next session starts fresh", s.id(), e.getMessage());
        }
    }

    /** The agent's latest session on the stopped session's mission and ticket: the stopped one, unless one followed. */
    private static Optional<AgentSession> latestSession(Company c, AgentId agent, Stopped where) {
        return c.sessions().of(where.mission()).stream()
                .filter(s -> s.agent().equals(agent) && s.ticket().equals(where.ticket()))
                .max(Comparator.comparing(AgentSession::startedAt));
    }

    /** Open and about a mission that has not ended: such a meeting holds its attendees. */
    private boolean open(String meeting) {
        return office.query(c -> c.meetings()
                .find(meeting)
                .filter(Meeting::open)
                .flatMap(m -> c.missions().find(m.mission()))
                .map(m -> !m.status().isTerminal())
                .orElse(false));
    }

    /** The meeting's mission; null for an unknown meeting, which the domain then refuses (UNKNOWN_MEETING). */
    private MissionId missionOf(String meeting) {
        return office.query(
                c -> c.meetings().find(meeting).map(Meeting::mission).orElse(null));
    }

    /** The director decides on a mission in its mode: a real mission's meeting is real. */
    private dev.floor42.domain.CommandContext director(Company c, MissionId m) {
        Origin origin = m == null
                ? Origin.REAL
                : c.missions().find(m).map(Mission::origin).orElse(Origin.REAL);
        return office.context(Actor.director(), origin);
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
