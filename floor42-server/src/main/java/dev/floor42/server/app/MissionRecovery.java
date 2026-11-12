package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.QuestionStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * After a restart, the director's last answer to a held or failed step is honoured again: the journal says what was
 * decided, and no session has started since. "Wait for the reset" waits again; "Retry" and a raised budget resume now
 * (D9). A session the previous daemon left open is recorded as interrupted, and the director is asked.
 */
@ApplicationScoped
public class MissionRecovery {

    private static final Logger LOG = Logger.getLogger(MissionRecovery.class);

    private final OfficeService office;
    private final Dispatcher dispatcher;
    private final HoldRulings rulings;
    private final RetryDesk retry;
    private final Staffing staffing;
    private final dev.floor42.server.runtime.CostCap costCap;
    private final dev.floor42.server.runtime.UnnamedLaunches unnamed;
    private final Workspaces workspaces;

    @Inject
    MissionRecovery(
            OfficeService office,
            Dispatcher dispatcher,
            HoldRulings rulings,
            RetryDesk retry,
            Staffing staffing,
            dev.floor42.server.runtime.CostCap costCap,
            dev.floor42.server.runtime.UnnamedLaunches unnamed,
            Workspaces workspaces) {
        this.workspaces = workspaces;
        this.unnamed = unnamed;
        this.staffing = staffing;
        this.costCap = costCap;
        this.office = office;
        this.dispatcher = dispatcher;
        this.rulings = rulings;
        this.retry = retry;
    }

    /**
     * Records the launches the last daemon left without a session, each once. Called at start-up before the meetings
     * close their unfinished rounds, so that a turn's launch is the turn's session and its cap counts once (ADR-0049).
     */
    public void countUnnamedLaunches() {
        unnamed.take().forEach(this::countUnnamedLaunch);
    }

    /** Public for the tests, which play a restart. */
    public void recover() {
        countUnnamedLaunches();
        // A suspended mission can hold a session too (suspended while it ran): it is closed the same way, or its agent
        // would stay at work for good; the work itself waits for the director to resume the mission.
        missions(MissionStatus.SUSPENDED).forEach(m -> {
            clearStaleLocks(m);
            closeInterruptedSessions(m);
        });
        for (MissionId m : missions(MissionStatus.IN_PROGRESS)) {
            clearStaleLocks(m);
            closeInterruptedSessions(m);
            Question q = office.query(c -> lastResumingAnswer(c, m));
            if (q == null) {
                dispatcher.produce(m);
                continue;
            }
            LOG.infof("Mission %s: resuming after a restart (%s answered \"%s\")", m, q.id(), q.answer());
            if (q.kind() == QuestionKind.PLAN_LIMIT) rulings.ruleOnPlanLimit(m, q.answerCode());
            else dispatcher.produce(m);
        }
    }

    /** A git command killed with the previous daemon leaves its lock: the mission's next git call would fail on it. */
    private void clearStaleLocks(MissionId m) {
        boolean stopped = workspaces.previousSessionsStopped();
        workspaces.worktrees(m).values().forEach(w -> StaleIndexLocks.clear(w, stopped));
    }

    private List<MissionId> missions(MissionStatus status) {
        return office.query(c -> c.missions().all().stream()
                .filter(m -> m.origin() == Origin.REAL && m.status() == status)
                .map(Mission::id)
                .toList());
    }

    /** A turn of speech at the director's meeting: its session is no step of the mission's work. */
    private static boolean meetingTicket(Company c, AgentSession s) {
        return c.tickets()
                .find(s.ticket())
                .map(t -> t.kind() == dev.floor42.domain.TicketKind.MEETING)
                .orElse(false);
    }

    /** The last answer since the last session that sends a step back to work; null while the mission still waits. */
    private static Question lastResumingAnswer(Company c, MissionId m) {
        Instant lastSession = c.sessions().of(m).stream()
                .filter(s -> !meetingTicket(c, s))
                .map(AgentSession::startedAt)
                .max(Instant::compareTo)
                .orElse(Instant.MIN);
        if (MissionHolds.waiting(c, m)) return null;
        return c.desk().of(m).stream()
                .filter(x ->
                        x.status() == QuestionStatus.ANSWERED && x.askedAt().isAfter(lastSession))
                .filter(MissionRecovery::resumes)
                .reduce((a, b) -> b)
                .orElse(null);
    }

    /** An answer that sends a held or failed step back to work. */
    private static boolean resumes(Question q) {
        return switch (q.kind()) {
            case PLAN_LIMIT -> !q.answered(OptionCode.STOP_HERE);
            case BUDGET_REACHED -> q.answered(OptionCode.RAISE_BUDGET);
            // Whatever the answer, the mission no longer waits: the next ticket, the rework or a new review starts.
            case REVIEW_ESCALATION -> true;
            default -> q.answered(OptionCode.RETRY) || q.answered(OptionCode.GO_ON_WITHOUT);
        };
    }

    /**
     * A session still open in the journal died with the previous daemon: nothing will ever end it. It is recorded as
     * failed, and the director decides whether to run the step again.
     */
    private void closeInterruptedSessions(MissionId m) {
        List<AgentSession> interrupted = office.query(
                c -> c.sessions().of(m).stream().filter(AgentSession::isOpen).toList());
        for (AgentSession s : interrupted) {
            LOG.infof("Mission %s: session %s was left open by the previous daemon", m, s.id());
            double bound = boundOf(m, s);
            office.execute(c -> c.sessions()
                    .end(
                            office.context(Actor.system(), Origin.REAL),
                            s.id(),
                            true,
                            AgentSession.INTERRUPTED,
                            null,
                            null,
                            null,
                            null,
                            bound));
            // A staffing review has its own ending below: "Retry" would name a ticket nobody works on.
            // A turn at the director's meeting asks nothing either: its round records it as not heard (ADR-0049).
            boolean review = office.query(c -> c.staff().isStaffingTicket(s.ticket()) || meetingTicket(c, s));
            if (!review)
                retry.ask(m, s.ticket(), s.agent(), "the session was interrupted: the daemon stopped while it ran");
        }
        // The planner's review the daemon left unadvised is closed: it would bar the planner from every meeting.
        staffing.closeInterrupted(m);
    }

    /**
     * The previous daemon launched this process and stopped before it named its session: nothing is in the journal, yet
     * it may have spent up to its cap. Recorded as a failed session of its own, bounded by the cap it was launched
     * with (today's for a launch record before 1.9.0, said in the log).
     */
    private void countUnnamedLaunch(dev.floor42.server.runtime.UnnamedLaunches.Launch l) {
        String record = UnnamedLaunch.idOf(l.mission(), l.folder());
        // Journaled already, under its folder or its session: that record carries the cap, counted once.
        if (office.query(c -> c.sessions().find(record).isPresent()
                || (l.sessionId() != null
                        && c.sessions().find(l.mission() + ":" + l.sessionId()).isPresent()))) return;
        try {
            MissionId m = new MissionId(l.mission());
            double cap = l.capUsd() != null ? l.capUsd() : costCap.sessionCapUsd(m);
            if (l.capUsd() == null)
                LOG.warnf("Launch %s has no cap in its record: today's %.2f USD bounds it", l.folder(), cap);
            LOG.warnf(
                    "Mission %s: a launch for %s on %s left no session in the journal; its cap of %.2f USD counts",
                    m, l.agent(), l.ticket(), cap);
            office.execute(c -> c.sessions()
                    .recordUnnamedLaunch(
                            office.context(Actor.system(), Origin.REAL),
                            record,
                            new dev.floor42.domain.TicketId(l.ticket()),
                            new dev.floor42.domain.AgentId(l.agent()),
                            l.model(),
                            cap));
        } catch (dev.floor42.domain.DomainException | IllegalArgumentException refused) {
            // Handled: a launch for a ticket or agent the journal does not know (another data folder?): logged, not
            // counted
            LOG.warnf("Launch %s could not be recorded: %s", l.folder(), refused.getMessage());
        }
    }

    /**
     * What an interrupted session may have spent at most: the cap it ran under, journaled with its start. A session
     * started before 1.9.0 has none in the journal: today's cap for the mission stands in, and the log says so.
     */
    private double boundOf(MissionId m, AgentSession s) {
        if (s.capUsd() != null) return s.capUsd();
        double today = costCap.sessionCapUsd(m);
        LOG.warnf(
                "Mission %s: session %s has no cap in the journal (started before 1.9.0): today's %.2f USD bounds it",
                m, s.id(), today);
        return today;
    }
}
