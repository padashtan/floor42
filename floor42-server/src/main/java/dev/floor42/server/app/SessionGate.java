package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.ClaudeCodeRuntime;
import dev.floor42.server.runtime.SessionReport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Every live session goes through here: the mission's holds are checked first (a held session never starts), then it
 * runs under the {@link MissionGovernor}. The report methods also settle the common failure: no report, the director
 * is asked whether to retry.
 */
@ApplicationScoped
public class SessionGate {

    private static final Logger LOG = Logger.getLogger(SessionGate.class);

    /** Said first to a session that continues an earlier one: the journal's brief, not its memory, is the state. */
    static final String CONTINUED_PREFIX = "Floor42 continues your earlier session on this ticket, which ended before"
            + " its report. What you did then is in this conversation, but the work may have moved on since: the brief"
            + " below is the current state, and it wins over your memory. Every tool call is decided again by Floor42.\n\n";

    private final OfficeService office;
    private final ClaudeCodeRuntime runtime;
    private final MissionHolds holds;
    private final SessionContinuity continuity;
    private final RetryDesk retry;
    private final HeldSessions held;
    private final Duration longestSilence;
    private final Duration longestLongSilence;

    @Inject
    SessionGate(
            OfficeService office,
            ClaudeCodeRuntime runtime,
            MissionHolds holds,
            SessionContinuity continuity,
            RetryDesk retry,
            HeldSessions held,
            // How long an allowed call may stay silent and still count as running (ADR-0047): its heartbeat comes about
            // every 30 s; a result that never comes no longer keeps a meeting's attendee from being stopped.
            @ConfigProperty(name = "floor42.meeting.longest-silent-call", defaultValue = "PT2M")
                    Duration longestSilence,
            // The same for a subagent or an MCP tool: Claude Code's heartbeat is not proven for them (ADR-0047).
            @ConfigProperty(name = "floor42.meeting.longest-silent-long-call", defaultValue = "PT30M")
                    Duration longestLongSilence) {
        this.longestLongSilence = longestLongSilence;
        this.office = office;
        this.runtime = runtime;
        this.holds = holds;
        this.continuity = continuity;
        this.retry = retry;
        this.held = held;
        this.longestSilence = longestSilence;
    }

    /** A live session of this agent, briefed in the director's language. Read under the office monitor. */
    static SessionSpec spec(
            Company c, MissionId m, TicketId ticket, Agent agent, Path dir, String brief, List<String> tools) {
        return new SessionSpec(
                m,
                ticket,
                agent.id(),
                Origin.REAL,
                dir.toString(),
                Briefs.inLanguage(c, brief),
                agent.model(),
                agent.effort(),
                tools);
    }

    /** The session's report, failed or not; empty when the mission's holds kept it from starting. */
    Optional<SessionReport> run(SessionSpec spec) {
        return run(spec, UnaryOperator.identity());
    }

    /** The final text of a session that delivered one; otherwise empty, the director asked about a failure. */
    Optional<String> report(SessionSpec spec) {
        return run(spec).flatMap(r -> textOf(spec, r));
    }

    /**
     * As {@link #report}, continuing the earlier session (ADR-0038) when the director asked for it and it is still safe
     * now, checked again at this moment; otherwise fresh, as always.
     */
    Optional<String> reportContinuing(SessionSpec spec) {
        return run(spec, this::continued).flatMap(r -> textOf(spec, r));
    }

    /**
     * A session the mission's holds do not apply to (a retrospective's turn, a staffing review), still under the origin
     * rule: never one for a simulated mission, nor for an attendee of the director's meeting (ADR-0047; the
     * reservation these callers hold refuses an attendee already, this is the gate's own word on it). The refusal
     * says which, so the caller journals the true reason.
     */
    Unheld runUnheld(SessionSpec spec) {
        if (!live(spec)) return new Unheld.NotStarted(Unheld.Reason.SIMULATED_MISSION);
        if (attending(spec)) return new Unheld.NotStarted(Unheld.Reason.AT_THE_MEETING);
        return new Unheld.Ran(runtime.run(spec, new MissionGovernor(office, spec)));
    }

    /**
     * A turn of speech at the director's meeting (ADR-0049): outside the meeting's hold (the speaker is an attendee),
     * under a governor that refuses every tool but a read. Never for a simulated mission.
     */
    Unheld runTurn(SessionSpec spec) {
        if (!live(spec)) return new Unheld.NotStarted(Unheld.Reason.SIMULATED_MISSION);
        return new Unheld.Ran(runtime.run(spec, new MissionGovernor(office, spec, true)));
    }

    private Optional<SessionReport> run(SessionSpec spec, UnaryOperator<SessionSpec> prepare) {
        if (!live(spec)) return Optional.empty();
        // Taken before the check: a meeting called between the two still counts as called during this session.
        Instant from = Instant.now();
        if (attending(spec)) return Optional.empty();
        if (holds.held(spec.mission(), spec.model())) return Optional.empty();
        SessionSpec ready = prepare.apply(spec);
        MeetingHold hold =
                new MeetingHold(office, ready, new MissionGovernor(office, ready), longestSilence, longestLongSilence);
        held.started(hold);
        SessionReport report;
        try {
            report = runtime.run(ready, hold);
        } finally {
            held.ended(hold);
        }
        // Held for the director's meeting (ADR-0047): what it said last is no report, and nothing failed. The ticket
        // stays as it is; the dispatcher gives it back after the meeting.
        if (hold.refusedForMeeting() || calledToMeeting(spec.agent(), from)) {
            LOG.infof(
                    "Mission %s: %s stopped for the director's meeting, %s stays as it is",
                    spec.mission(), spec.agent(), spec.ticket());
            return Optional.empty();
        }
        return Optional.of(report);
    }

    private boolean attending(SessionSpec spec) {
        if (office.query(c -> c.meetings().attending(spec.agent()).isEmpty())) return false;
        LOG.infof("%s is at the director's meeting: no session starts for it on %s", spec.agent(), spec.ticket());
        return true;
    }

    /** The agent was called to a meeting since {@code from}, open or already closed. */
    private boolean calledToMeeting(AgentId agent, Instant from) {
        return office.query(c -> c.meetings().all().stream()
                .anyMatch(m -> m.attends(agent) && !m.calledAt().isBefore(from)));
    }

    /** Defence in depth: a live session is never started for a simulated mission, even if a caller let one through. */
    private boolean live(SessionSpec spec) {
        if (realMission(spec)) return true;
        LOG.warnf("Mission %s is simulated: no live session starts for %s", spec.mission(), spec.ticket());
        return false;
    }

    private boolean realMission(SessionSpec spec) {
        return office.query(c -> c.missions()
                .find(spec.mission())
                .map(m -> m.origin() == Origin.REAL)
                .orElse(false));
    }

    private Optional<String> textOf(SessionSpec spec, SessionReport report) {
        if (report.failed() || report.finalText() == null || report.finalText().isBlank()) {
            retry.ask(spec.mission(), spec.ticket(), spec.agent(), report);
            return Optional.empty();
        }
        return Optional.of(report.finalText());
    }

    private SessionSpec continued(SessionSpec spec) {
        String from = office.query(c -> c.sessions()
                .continuationRequested(spec.mission(), spec.ticket(), spec.agent())
                .orElse(null));
        if (from == null) return spec;
        SessionContinuity.Continuity k = office.query(c -> c.sessions()
                .find(from)
                .map(s -> continuity.assess(c, s))
                .orElse(SessionContinuity.Continuity.no("unknown", null)));
        if (!k.possible()) {
            LOG.infof(
                    "Mission %s: %s cannot be continued (%s), the session starts fresh",
                    spec.mission(), from, k.reason());
            return spec;
        }
        return spec.resuming(k.claudeSession(), CONTINUED_PREFIX + spec.brief());
    }

    /** The agent takes the ticket: assigned to it, and started if it was waiting. */
    static void take(OfficeService office, TicketId id, AgentId agent) {
        record State(AgentId assignee, TicketStatus status) {}
        State t = office.query(c -> {
            Ticket x = c.tickets().find(id).orElseThrow();
            return new State(x.assignee(), x.status());
        });
        if (!agent.equals(t.assignee())) {
            office.execute(c -> c.tickets().assign(office.context(Actor.system(), Origin.REAL), id, agent));
        }
        if (t.status() == TicketStatus.TODO) {
            office.execute(c -> c.tickets().start(office.context(Actor.agent(agent), Origin.REAL), id));
        }
    }
}
