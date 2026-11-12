package dev.floor42.server.api;

import dev.floor42.domain.Action;
import dev.floor42.domain.Actor;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.server.app.LiveMissions;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.RefusedException;
import dev.floor42.server.app.SessionContinuity;
import dev.floor42.server.app.Stall;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the director can pick up again: missions not closed, and agent sessions that ended before their report. Reading
 * it starts nothing. Resuming goes through the paths that already exist (answering "Retry", resuming a suspended
 * mission or agent, asking the dispatcher to look again); the one new thing is the director's choice, for a
 * developer's interrupted session, to continue it with Claude Code's {@code --resume} when that is safe (ADR-0038).
 */
@Path("/api/resume")
@Produces(MediaType.APPLICATION_JSON)
public class ResumeResource {

    /**
     * @param state RUNNING (a task of it runs now), OPEN (planning or awaiting approval), SUSPENDED, INTERRUPTED (a
     *     session of it was cut and "Retry" is asked), WAITING (a question of yours holds it), IDLE (in progress and
     *     nothing runs nor waits)
     * @param action what "Resume" does, through an existing route: RESUME_MISSION ({@code POST
     *     /api/missions/{id}/resume}), RETRY (answers {@code retryQuestion} with "Retry"), RESUME_AGENTS ({@code POST
     *     /api/agents/{id}/resume} for each of {@code suspendedAgents}), ANSWER (the desk), APPROVE (the desk), NUDGE
     *     ({@code POST /api/missions/{id}/nudge}), NONE
     * @param doing the ticket under way (in progress or in review), null if none
     */
    public record ResumableMission(
            String id,
            String title,
            String origin,
            String status,
            String workspace,
            String lastActivity,
            String state,
            String action,
            String doing,
            String doingTitle,
            String doingStatus,
            String retryQuestion,
            int openQuestions,
            List<String> suspendedAgents) {
        public ResumableMission {
            suspendedAgents =
                    suspendedAgents == null ? null : Collections.unmodifiableList(new ArrayList<>(suspendedAgents));
        }
    }

    /**
     * @param continuable whether continuing it with Claude Code's {@code --resume} is safe now; the other way, always
     *     offered when {@code retryQuestion} is set, is a fresh session from the journal's brief
     * @param continuity why not ({@code ok} when it can): see {@link SessionContinuity.Continuity}
     * @param lastAction the agent's last tool call on the ticket before the session ended, as Floor42 summarised it
     * @param continuedFrom the session this one already continued, null for a fresh one
     */
    public record ResumableSession(
            String session,
            String mission,
            String ticket,
            String ticketTitle,
            String agent,
            String agentName,
            String startedAt,
            String endedAt,
            String outcome,
            boolean interrupted,
            String lastAction,
            String retryQuestion,
            boolean continuable,
            String continuity,
            String continuedFrom) {}

    public record ResumeView(List<ResumableMission> missions, List<ResumableSession> sessions) {
        public ResumeView {
            missions = missions == null ? null : Collections.unmodifiableList(new ArrayList<>(missions));
            sessions = sessions == null ? null : Collections.unmodifiableList(new ArrayList<>(sessions));
        }
    }

    /** @param mode FRESH (a new session from the journal's brief) or CONTINUE (Claude Code's --resume) */
    public record ResumeSessionRequest(String session, String mode) {}

    @Inject
    OfficeService office;

    @Inject
    LiveMissions live;

    @Inject
    SessionContinuity continuity;

    @GET
    public ResumeView list() {
        Instant now = Instant.now();
        List<Mission> open = office.query(c -> c.missions().all().stream()
                .filter(m -> !m.status().isTerminal())
                .toList());
        // The dispatcher's view, outside the company's lock, as the progress panel reads it.
        Map<String, Stall> stalls = new LinkedHashMap<>();
        for (Mission m : open) stalls.put(m.id().value(), live.stall(m.id(), now));
        return office.query(c -> {
            List<ResumableMission> missions = new ArrayList<>();
            List<ResumableSession> sessions = new ArrayList<>();
            for (Mission m : open) {
                missions.add(mission(c, m, stalls.get(m.id().value())));
                if (m.origin() == Origin.REAL) sessions.addAll(sessions(c, m));
            }
            missions.sort(Comparator.comparing(
                    ResumableMission::lastActivity, Comparator.nullsLast(Comparator.reverseOrder())));
            sessions.sort(
                    Comparator.comparing(ResumableSession::endedAt, Comparator.nullsLast(Comparator.reverseOrder())));
            return new ResumeView(missions, sessions);
        });
    }

    /**
     * Resumes one session's work on the director's click: answers its open "Retry" question, after recording, with
     * {@code CONTINUE}, that the next session continues it with Claude Code's --resume. Refused when nothing asks for
     * it (no open "Retry") or when continuing is not safe now; the dispatcher starts the session as for any "Retry".
     */
    @POST
    @Path("/session")
    @Consumes(MediaType.APPLICATION_JSON)
    public ResumeView resumeSession(ResumeSessionRequest request) {
        if (request == null || request.session() == null || request.session().isBlank()) {
            throw new BadRequestException("session is required");
        }
        String mode = request.mode() == null ? "FRESH" : request.mode().strip().toUpperCase(java.util.Locale.ROOT);
        if (!mode.equals("FRESH") && !mode.equals("CONTINUE")) {
            throw RefusedException.invalid("INVALID_ARGUMENT", "mode is FRESH or CONTINUE");
        }
        AgentSession s = office.query(c -> c.sessions().find(request.session()))
                .orElseThrow(() -> new NotFoundException("No session " + request.session()));
        office.execute(c -> {
            Question retry = retryQuestion(c, s);
            if (retry == null) {
                throw RefusedException.conflict(
                        "NOTHING_TO_RESUME", "No open \"Retry\" question for " + s.ticket() + ": nothing waits to run");
            }
            var ctx = office.context(Actor.director(), Origin.REAL);
            List<EventEnvelope> events = new ArrayList<>();
            if (mode.equals("CONTINUE")) {
                SessionContinuity.Continuity k = continuity.assess(c, s);
                if (!k.possible()) {
                    throw RefusedException.conflict(
                            "CONTINUE_UNSAFE", s.id() + " cannot be continued safely now: " + k.reason());
                }
                // Two commands in one lambda: everything the second one checks is checked here first, since the
                // continuation is applied as soon as it is emitted.
                c.desk().checkAnswer(ctx, retry.id(), LiveMissions.RETRY);
                events.addAll(c.sessions().requestContinuation(ctx, s.id()));
            }
            events.addAll(c.desk().answer(ctx, retry.id(), LiveMissions.RETRY));
            return events;
        });
        return list();
    }

    private ResumableMission mission(Company c, Mission m, Stall stall) {
        List<Ticket> tickets = c.tickets().of(m.id());
        Ticket doing = tickets.stream()
                .filter(t -> t.status() == TicketStatus.IN_PROGRESS || t.status() == TicketStatus.IN_REVIEW)
                .findFirst()
                .orElse(null);
        List<Question> questions = c.desk().of(m.id()).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN)
                .toList();
        Question retry = questions.stream()
                .filter(q -> q.offers(dev.floor42.domain.OptionCode.RETRY))
                .reduce((a, b) -> b)
                .orElse(null);
        List<String> suspended = suspendedAgents(c, tickets);
        boolean running = stall != null && "RUNNING".equals(stall.state());
        Next next = next(c, m, running, retry, !questions.isEmpty(), !suspended.isEmpty());
        return new ResumableMission(
                m.id().value(),
                m.title(),
                m.origin().name(),
                m.status().name(),
                m.workspace(),
                c.queries().lastActivity(m.id()).orElse(m.createdAt()).toString(),
                next.state(),
                next.action(),
                doing == null ? null : doing.id().value(),
                doing == null ? null : doing.title(),
                doing == null ? null : doing.status().name(),
                retry == null ? null : retry.id().value(),
                questions.size(),
                suspended);
    }

    /** Where a mission stands for the resume panel, and the one action that moves it. */
    private record Next(String state, String action) {}

    private Next next(Company c, Mission m, boolean running, Question retry, boolean asked, boolean agentsSuspended) {
        return switch (m.status()) {
            case SUSPENDED -> new Next("SUSPENDED", "RESUME_MISSION");
            case PLANNING, AWAITING_APPROVAL ->
                new Next("OPEN", m.status() == MissionStatus.AWAITING_APPROVAL ? "APPROVE" : "NONE");
            default -> {
                if (running) yield new Next("RUNNING", "NONE");
                if (retry != null) yield new Next(interrupted(c, retry) ? "INTERRUPTED" : "WAITING", "RETRY");
                if (asked) yield new Next("WAITING", "ANSWER");
                if (agentsSuspended) yield new Next("WAITING", "RESUME_AGENTS");
                yield new Next("IDLE", m.origin() == Origin.REAL ? "NUDGE" : "NONE");
            }
        };
    }

    /** The agents of the open tickets whom the director suspended. */
    private static List<String> suspendedAgents(Company c, List<Ticket> tickets) {
        return tickets.stream()
                .filter(t -> !t.status().isTerminal() && t.assignee() != null)
                .map(Ticket::assignee)
                .distinct()
                .filter(a -> c.staff().find(a).map(Agent::suspended).orElse(false))
                .map(a -> a.value())
                .toList();
    }

    /** The latest session of each agent on each open ticket, when it ended before its report. */
    private List<ResumableSession> sessions(Company c, Mission m) {
        Map<String, AgentSession> latest = new LinkedHashMap<>();
        for (AgentSession s : c.sessions().of(m.id()))
            latest.put(s.agent().value() + "/" + s.ticket().value(), s);
        List<ResumableSession> out = new ArrayList<>();
        for (AgentSession s : latest.values()) {
            if (s.isOpen() || !Boolean.TRUE.equals(s.failed())) continue;
            Ticket t = c.tickets().find(s.ticket()).orElse(null);
            if (t == null || t.status().isTerminal() || t.kind() == dev.floor42.domain.TicketKind.MEETING) continue;
            Question retry = retryQuestion(c, s);
            SessionContinuity.Continuity k = continuity.assess(c, s);
            Action last = c.actions().of(m.id()).stream()
                    .filter(a -> s.agent().equals(a.agent()) && s.ticket().equals(a.ticket()))
                    .filter(a -> !a.requestedAt().isBefore(s.startedAt()))
                    .reduce((a, b) -> b)
                    .orElse(null);
            out.add(new ResumableSession(
                    s.id(),
                    m.id().value(),
                    t.id().value(),
                    t.title(),
                    s.agent().value(),
                    c.staff()
                            .find(s.agent())
                            .map(Agent::displayName)
                            .orElse(s.agent().value()),
                    s.startedAt().toString(),
                    s.endedAt() == null ? null : s.endedAt().toString(),
                    s.outcome(),
                    AgentSession.INTERRUPTED.equals(s.outcome()),
                    last == null ? null : last.tool() + ": " + last.summary(),
                    retry == null ? null : retry.id().value(),
                    k.possible(),
                    k.reason(),
                    s.continuedFrom()));
        }
        return out;
    }

    /** The open "Retry" question about this session's ticket, asked after it started; null if none. */
    private static Question retryQuestion(Company c, AgentSession s) {
        return c.desk().of(s.mission()).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN
                        && q.offers(dev.floor42.domain.OptionCode.RETRY)
                        && s.ticket().equals(q.ticket())
                        && !q.askedAt().isBefore(s.startedAt()))
                .reduce((a, b) -> b)
                .orElse(null);
    }

    /** Whether the latest session on the retry question's ticket was cut by a restart. */
    private static boolean interrupted(Company c, Question retry) {
        if (retry.ticket() == null) return false;
        return c.sessions().of(retry.mission()).stream()
                .filter(s -> s.ticket().equals(retry.ticket()))
                .reduce((a, b) -> b)
                .map(s -> AgentSession.INTERRUPTED.equals(s.outcome()))
                .orElse(false);
    }
}
