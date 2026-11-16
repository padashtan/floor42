package dev.floor42.server.app.office.standup;

import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.DeskEvent.QuestionAnswered;
import dev.floor42.domain.event.DeskEvent.QuestionAsked;
import dev.floor42.domain.event.DeskEvent.QuestionWithdrawn;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.RosterEvent.AgentAssignmentsResumed;
import dev.floor42.domain.event.RosterEvent.AgentAssignmentsSuspended;
import dev.floor42.domain.event.RosterEvent.AgentDismissed;
import dev.floor42.domain.event.RosterEvent.AgentHired;
import dev.floor42.domain.event.SessionEvent.AgentSessionEnded;
import dev.floor42.domain.event.SessionEvent.AgentSessionStarted;
import dev.floor42.domain.event.TicketEvent.TicketAssigned;
import dev.floor42.domain.event.TicketEvent.TicketCancelled;
import dev.floor42.domain.event.TicketEvent.TicketCompleted;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.event.TicketEvent.TicketEdited;
import dev.floor42.domain.event.TicketEvent.TicketReleased;
import dev.floor42.domain.event.TicketEvent.TicketReopened;
import dev.floor42.domain.event.TicketEvent.TicketStarted;
import dev.floor42.domain.event.TicketEvent.TicketSubmittedForReview;
import dev.floor42.server.app.office.standup.Standup.AgentStandup;
import dev.floor42.server.app.office.standup.Standup.Line;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composes the standup (ADR-0078) from the real events: pure, no I/O, no model, nothing journaled. Mood and persona do
 * not exist here: only the events below are read.
 */
public final class StandupComposer {

    /** A title or a subject is cut here, as in every summary of the journal. */
    static final int TEXT_MAX = 120;

    private static final class Who {
        final String name;
        boolean suspended;
        boolean dismissed;
        final List<Line> yesterday = new ArrayList<>();
        int sessionsYesterday;
        int failedYesterday;
        int sessionsToday;

        Who(String name) {
            this.name = name;
        }
    }

    private static final class Tk {
        String mission;
        String title;
        String assignee;
        TicketStatus status = TicketStatus.TODO;
        List<String> deps = List.of();
    }

    private record Ask(String mission, String agent, String ticket, String subject) {}

    private final Map<String, Who> staff = new LinkedHashMap<>();
    private final Map<String, Tk> tickets = new HashMap<>();
    private final Map<String, String> sessionAgent = new HashMap<>();
    private final Map<String, Ask> open = new LinkedHashMap<>();
    private final ZoneId zone;
    private final LocalDate today;
    private final LocalDate previous;

    private StandupComposer(List<EventEnvelope> real, ZoneId zone, LocalDate today) {
        this.zone = zone;
        this.today = today;
        this.previous = real.stream()
                .map(e -> day(e.occurredAt()))
                .filter(d -> d.isBefore(today))
                .max(LocalDate::compareTo)
                .orElse(null);
    }

    /** The standup as of {@code now}, the days counted in {@code zone}. Events not REAL are left out. */
    public static Standup compose(List<EventEnvelope> events, Instant now, ZoneId zone) {
        List<EventEnvelope> real =
                events.stream().filter(e -> e.origin() == Origin.REAL).toList();
        LocalDate today = now.atZone(zone).toLocalDate();
        StandupComposer c = new StandupComposer(real, zone, today);
        real.forEach(c::read);
        return c.result();
    }

    private LocalDate day(Instant at) {
        return at.atZone(zone).toLocalDate();
    }

    private static String cut(String s) {
        return s == null ? null : s.length() <= TEXT_MAX ? s : s.substring(0, TEXT_MAX);
    }

    private void read(EventEnvelope e) {
        LocalDate d = day(e.occurredAt());
        boolean yesterday = d.equals(previous);
        switch (e.event()) {
            case AgentHired h ->
                staff.put(
                        h.agent().value(),
                        new Who(h.displayName() == null ? h.agent().value() : h.displayName()));
            case AgentDismissed x -> staff.computeIfPresent(x.agent().value(), (k, w) -> flag(w, true));
            case AgentAssignmentsSuspended s -> staff.computeIfPresent(s.agent().value(), (k, w) -> suspend(w, true));
            case AgentAssignmentsResumed s -> staff.computeIfPresent(s.agent().value(), (k, w) -> suspend(w, false));
            default -> readSession(e, d, yesterday);
        }
    }

    private void readSession(EventEnvelope e, LocalDate d, boolean yesterday) {
        switch (e.event()) {
            case AgentSessionStarted s -> {
                sessionAgent.put(s.session(), s.agent().value());
                if (d.equals(today)) who(s.agent().value(), w -> w.sessionsToday++);
            }
            case AgentSessionEnded s
            when yesterday ->
                who(sessionAgent.get(s.session()), w -> {
                    w.sessionsYesterday++;
                    if (s.failed()) w.failedYesterday++;
                });
            default -> readQuestion(e, yesterday);
        }
    }

    private void readQuestion(EventEnvelope e, boolean yesterday) {
        switch (e.event()) {
            case QuestionAsked q
            when q.blocking() ->
                open.put(
                        q.question().value(),
                        new Ask(
                                q.mission() == null ? null : q.mission().value(),
                                q.askedBy() == null ? null : q.askedBy().value(),
                                q.ticket() == null ? null : q.ticket().value(),
                                cut(q.subject())));
            case QuestionAnswered a -> open.remove(a.question().value());
            case QuestionWithdrawn w -> open.remove(w.question().value());
            default -> readTicket(e, yesterday);
        }
    }

    private void readTicket(EventEnvelope e, boolean yesterday) {
        switch (e.event()) {
            case TicketCreated c -> create(c);
            case TicketEdited t -> tickets.computeIfPresent(t.ticket().value(), (k, v) -> retitle(v, t.title()));
            case TicketAssigned a ->
                tickets.computeIfPresent(
                        a.ticket().value(), (k, v) -> assign(v, a.agent().value()));
            case TicketStarted t -> status(t.ticket().value(), TicketStatus.IN_PROGRESS);
            case TicketReopened t -> status(t.ticket().value(), TicketStatus.IN_PROGRESS);
            case TicketCancelled t -> status(t.ticket().value(), TicketStatus.CANCELLED);
            case TicketReleased t -> {
                status(t.ticket().value(), TicketStatus.TODO);
                tickets.computeIfPresent(t.ticket().value(), (k, v) -> assign(v, null));
            }
            case TicketSubmittedForReview t -> {
                status(t.ticket().value(), TicketStatus.IN_REVIEW);
                if (yesterday) credit("SUBMITTED", t.ticket().value());
            }
            case TicketCompleted t -> {
                status(t.ticket().value(), TicketStatus.DONE);
                if (yesterday) credit("COMPLETED", t.ticket().value());
            }
            default -> {}
        }
    }

    private static Who flag(Who w, boolean dismissed) {
        w.dismissed = dismissed;
        return w;
    }

    private static Who suspend(Who w, boolean suspended) {
        w.suspended = suspended;
        return w;
    }

    private static Tk retitle(Tk t, String title) {
        t.title = cut(title);
        return t;
    }

    private static Tk assign(Tk t, String agent) {
        t.assignee = agent;
        return t;
    }

    private void create(TicketCreated c) {
        Tk t = new Tk();
        t.mission = c.mission().value();
        t.title = cut(c.title());
        t.deps = c.dependsOn() == null
                ? List.of()
                : c.dependsOn().stream().map(x -> x.value()).toList();
        tickets.put(c.ticket().value(), t);
    }

    private void status(String ticket, TicketStatus s) {
        Tk t = tickets.get(ticket);
        if (t != null) t.status = s;
    }

    private void who(String agent, java.util.function.Consumer<Who> f) {
        Who w = agent == null ? null : staff.get(agent);
        if (w != null) f.accept(w);
    }

    /** A line of yesterday for the agent the ticket belongs to at that moment. */
    private void credit(String code, String ticket) {
        Tk t = tickets.get(ticket);
        if (t != null) who(t.assignee, w -> w.yesterday.add(new Line(code, t.mission, ticket, t.title, null)));
    }

    private Standup result() {
        List<AgentStandup> agents = new ArrayList<>();
        staff.forEach((id, w) -> {
            if (!w.dismissed) agents.add(agent(id, w));
        });
        return new Standup(today.toString(), previous == null ? null : previous.toString(), zone.getId(), agents);
    }

    private AgentStandup agent(String id, Who w) {
        List<Line> yesterday = new ArrayList<>(w.yesterday);
        if (w.sessionsYesterday > 0) yesterday.add(new Line("SESSIONS", null, null, null, w.sessionsYesterday));
        if (w.failedYesterday > 0) yesterday.add(new Line("SESSIONS_FAILED", null, null, null, w.failedYesterday));
        List<Line> now = new ArrayList<>();
        List<Line> blocked = new ArrayList<>();
        if (w.suspended) blocked.add(new Line("SUSPENDED", null, null, null, null));
        ticketLines(id, now, blocked);
        if (w.sessionsToday > 0) now.add(new Line("SESSIONS", null, null, null, w.sessionsToday));
        open.values().stream()
                .filter(q -> id.equals(q.agent()) || (q.ticket() != null && id.equals(assigneeOf(q.ticket()))))
                .forEach(q -> blocked.add(new Line("QUESTION", q.mission(), q.ticket(), q.subject(), null)));
        return new AgentStandup(id, w.name, yesterday, now, blocked);
    }

    /** The agent's tickets: what is on its plate, and the queued ones still waiting for another. */
    private void ticketLines(String id, List<Line> now, List<Line> blocked) {
        tickets.forEach((tid, t) -> {
            if (!id.equals(t.assignee)) return;
            String code = switch (t.status) {
                case IN_PROGRESS -> "IN_PROGRESS";
                case IN_REVIEW -> "IN_REVIEW";
                case TODO -> "QUEUED";
                default -> null;
            };
            if (code != null) now.add(new Line(code, t.mission, tid, t.title, null));
            long unmet = t.status == TicketStatus.TODO
                    ? t.deps.stream().filter(d -> !done(d)).count()
                    : 0;
            if (unmet > 0) blocked.add(new Line("DEPENDENCY", t.mission, tid, t.title, (int) unmet));
        });
    }

    private boolean done(String ticket) {
        Tk t = tickets.get(ticket);
        return t != null && t.status == TicketStatus.DONE;
    }

    private String assigneeOf(String ticket) {
        Tk t = tickets.get(ticket);
        return t == null ? null : t.assignee;
    }
}
