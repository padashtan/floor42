package dev.floor42.server.app;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * What runs now, across missions, all in memory (after a restart the journal says what is next): the tickets a task is
 * on, per mission, and the agents whose session runs, one session per agent at a time. The dispatcher claims both
 * under the {@link DispatchLock}; work outside it (a retrospective turn, a staffing review) holds an agent through a
 * {@link Reservation} only its holder ends.
 */
@ApplicationScoped
public class AgentReservations {

    private static final Logger LOG = Logger.getLogger(AgentReservations.class);
    private static final Duration POLL = Duration.ofSeconds(2);

    private final DispatchLock lock;
    private final OfficeService office;
    private final OfficeSettings settings;
    private final Duration usualPatience;
    private final Dispatcher dispatcher;

    private final Map<MissionId, Set<TicketId>> running = new ConcurrentHashMap<>();
    private final Set<AgentId> busy = ConcurrentHashMap.newKeySet();
    /** The mission each agent the dispatcher reserved works for: "waits for X, busy on M-n". */
    private final Map<AgentId, MissionId> reservedOn = new ConcurrentHashMap<>();

    private final Map<AgentId, Reservation> reservations = new ConcurrentHashMap<>();
    /** Missions whose workspaces the director is committing in: the dispatcher starts nothing on them meanwhile. */
    private final Set<MissionId> heldForWorkshop = ConcurrentHashMap.newKeySet();

    @Inject
    AgentReservations(
            DispatchLock lock,
            OfficeService office,
            OfficeSettings settings,
            Dispatcher dispatcher,
            @ConfigProperty(name = "floor42.reservation.patience", defaultValue = "PT2H") Duration usualPatience) {
        this.lock = lock;
        this.office = office;
        this.settings = settings;
        this.usualPatience = usualPatience;
        this.dispatcher = dispatcher;
    }

    /** The tickets a task runs on in this mission, live. */
    Set<TicketId> tickets(MissionId m) {
        return running.computeIfAbsent(m, k -> ConcurrentHashMap.newKeySet());
    }

    /** True while a task of this mission runs. Read only: records nothing. */
    boolean running(MissionId m) {
        Set<TicketId> mine = running.get(m);
        return mine != null && !mine.isEmpty();
    }

    /** The mission is at its parallel limit: nothing more starts on it. Under the dispatch lock. */
    boolean full(MissionId m) {
        return heldForWorkshop.contains(m) || tickets(m).size() >= Math.max(1, settings.parallelSessions());
    }

    /**
     * The director commits in a workspace (Workshop.accept): under the dispatch lock, where every task of the
     * dispatcher is chosen, the mission must have none running and the workspace's agent must be free. Both are then
     * held: the agent as by {@link #reserve}, the mission as full, so nothing starts on it until {@link
     * #releaseWorkshop}. Empty when someone is at work.
     */
    public Optional<Reservation> holdForWorkshop(MissionId m, AgentId agent) {
        return lock.holding(() -> {
            if (running(m) || heldForWorkshop.contains(m)) return Optional.empty();
            Optional<Reservation> r = reserve(agent);
            r.ifPresent(x -> heldForWorkshop.add(m));
            return r;
        });
    }

    /** Ends {@link #holdForWorkshop}; the dispatcher may start work on the mission again. */
    public void releaseWorkshop(MissionId m, Reservation held) {
        lock.holding(() -> {
            heldForWorkshop.remove(m);
            return release(held);
        });
        // A decision taken during the hold found the mission full: look again now it is free.
        dispatcher.nudge(m);
    }

    /** The agents at work now, live. */
    Set<AgentId> busyAgents() {
        return Collections.unmodifiableSet(busy);
    }

    MissionId reservedOn(AgentId agent) {
        return reservedOn.get(agent);
    }

    /** Takes the task's ticket and agents for this mission. Under the dispatch lock. */
    void claim(MissionId m, Task task) {
        tickets(m).add(task.ticket());
        busy.addAll(task.reserved());
        task.reserved().forEach(a -> reservedOn.put(a, m));
    }

    /** The task ended: its ticket and agents are free again. */
    void free(MissionId m, Task task) {
        task.reserved().forEach(a -> reservedOn.remove(a, m));
        tickets(m).remove(task.ticket());
        task.reserved().forEach(busy::remove);
    }

    /**
     * Reserves an agent for work outside the dispatcher (a retrospective turn, ADR-0020): empty while it runs a session
     * elsewhere, and while it attends an open meeting of the director (ADR-0047), whoever asks: the journal says so,
     * so this holds from the first instant of a start-up too. The dispatcher gives it nothing until {@link #release}.
     */
    public Optional<Reservation> reserve(AgentId agent) {
        // Dispatch lock first, then the office monitor: the order every caller keeps.
        return lock.holding(() -> {
            if (office.query(c -> c.meetings().attending(agent).isPresent())) return Optional.empty();
            return take(agent);
        });
    }

    /** The meeting's own hold on its attendee (DirectorMeetings): the one reservation a meeting does not refuse. */
    Optional<Reservation> reserveForMeeting(AgentId agent) {
        return lock.holding(() -> take(agent));
    }

    private Optional<Reservation> take(AgentId agent) {
        return lock.holding(() -> {
            if (!busy.add(agent)) return Optional.empty();
            Reservation r = new Reservation(agent);
            reservations.put(agent, r);
            return Optional.of(r);
        });
    }

    /** Waits until the agent can be reserved, at most {@code patience}: a task elsewhere may run long, not forever. */
    public Reservation await(AgentId agent, Duration patience) throws InterruptedException {
        Instant until = Instant.now().plus(patience);
        while (true) {
            Optional<Reservation> r = reserve(agent);
            if (r.isPresent()) return r.get();
            if (!Instant.now().isBefore(until)) throw timeout(agent, patience);
            Thread.sleep(POLL);
        }
    }

    /** The wait's end, with the true reason: the director's meeting when that is what held the agent (ADR-0047). */
    private ReservationTimeout timeout(AgentId agent, Duration patience) {
        boolean atTheMeeting = office.query(c -> c.meetings().attending(agent).isPresent());
        return new ReservationTimeout(
                agent, patience, atTheMeeting ? "stayed at the director's meeting" : "stayed busy elsewhere");
    }

    /**
     * Ends a reservation, only by its holder: a stale or repeated release never frees an agent another task took
     * since. False when it was not this holder's to end.
     */
    public boolean release(Reservation reservation) {
        boolean mine = lock.holding(() -> {
            boolean held = reservations.remove(reservation.agent(), reservation);
            if (held) busy.remove(reservation.agent());
            return held;
        });
        if (!mine) {
            LOG.warnf("A reservation of %s was released twice or by another holder: ignored", reservation.agent());
        }
        return mine;
    }

    /** True while a task of this agent is chosen or running: it cannot be dismissed in the middle. */
    public boolean busy(AgentId agent) {
        return busy.contains(agent);
    }

    /** How long work outside the dispatcher (a retrospective turn, a staffing review) waits for a busy agent. */
    public Duration patience() {
        return usualPatience;
    }

    /** A reservation outside the dispatcher: only this object releases it. */
    public static final class Reservation {
        private final AgentId agent;

        private Reservation(AgentId agent) {
            this.agent = agent;
        }

        public AgentId agent() {
            return agent;
        }
    }

    /** An agent stayed busy elsewhere, or at the director's meeting, longer than a waiting task accepts. */
    public static final class ReservationTimeout extends RuntimeException {
        ReservationTimeout(AgentId agent, Duration patience, String what) {
            super(agent + " " + what + " for more than " + patience.toMinutes() + " min: not reserved");
        }
    }
}
