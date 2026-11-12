package dev.floor42.server.app;

import static dev.floor42.server.app.MissionReports.ESTIMATED;
import static dev.floor42.server.app.MissionReports.MEASURED;
import static dev.floor42.server.app.MissionReports.SIMULATED;
import static dev.floor42.server.app.MissionReports.UPPER_BOUND;
import static dev.floor42.server.app.MissionReports.firstLine;
import static dev.floor42.server.app.MissionReports.producerVerdict;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.CheckRun;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Committee;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Retro;
import dev.floor42.domain.ReviewVerdict;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.app.MissionReports.Measure;
import dev.floor42.server.app.MissionReports.MissionReport;
import dev.floor42.server.app.MissionReports.ReportCheck;
import dev.floor42.server.app.MissionReports.ReportLesson;
import dev.floor42.server.app.MissionReports.ReportOutput;
import dev.floor42.server.app.MissionReports.ReportRetro;
import dev.floor42.server.app.MissionReports.ReportReview;
import dev.floor42.server.app.MissionReports.ReportReviews;
import dev.floor42.server.app.MissionReports.ReportTicket;
import dev.floor42.server.app.MissionReports.ReportTotals;
import dev.floor42.server.app.MissionReports.ReportWarning;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The end-of-mission report composed from one reading of the company (ADR-0039): one section per method, each from
 * the journal only. git's answers come in already read ({@link ReportOutput}).
 */
final class MissionReportComposer {

    /** What a ticket is for in the report: the work itself, the committee's decision, the retrospective, a staffing review. */
    enum Kind {
        WORK,
        DECISION,
        RETRO,
        STAFFING
    }

    private static final Pattern NOT_DONE =
            Pattern.compile("(?i)^\\W*(PARTIAL|FAILED|FAIL|BLOCKED|INCOMPLETE|NOT DONE)");

    /** Where a directive would send the ticket, or why none can be given; all null when the report does not ask. */
    private record Reopen(String to, String code, String message) {
        static final Reopen NOT_ASKED = new Reopen(null, null, null);
    }

    private final Company c;
    private final Mission m;
    private final MissionId id;
    private final Instant now;
    private final CommandContext director;
    private final TicketId retroTicket;
    private final TicketId decisionTicket;
    private final List<AgentSession> sessions;

    MissionReportComposer(Company c, Mission m, Instant now) {
        this.c = c;
        this.m = m;
        this.id = m.id();
        this.now = now;
        this.director = new CommandContext(Actor.director(), m.origin(), now, null);
        this.retroTicket = c.retros().find(id).map(Retro::ticket).orElse(null);
        this.decisionTicket = c.committees().latest(id).map(Committee::ticket).orElse(null);
        this.sessions = c.sessions().of(id);
    }

    MissionReport compose(String projectBranch, List<ReportOutput> outputs) {
        List<Ticket> all = c.tickets().of(id);
        List<ReportTicket> tickets = all.stream().map(this::ticket).toList();
        List<CheckRun> runs = c.verification().checkRuns(id);
        // Loudest first: work that exists only in a worktree.
        List<ReportWarning> warnings = new ArrayList<>();
        outputs.forEach(o -> warnings.addAll(outputWarnings(o)));
        all.forEach(t -> warnings.addAll(ticketWarnings(t)));
        warnings.addAll(checkWarnings(runs));
        return new MissionReport(
                id.value(),
                m.title(),
                m.status().name(),
                m.origin().name(),
                m.outcome(),
                m.workspace(),
                projectBranch,
                now.toString(),
                totals(all),
                tickets,
                reviews(all),
                runs.stream().map(MissionReportComposer::check).toList(),
                retro(),
                outputs,
                warnings,
                MissionNextSteps.next(c, m, director, tickets, outputs),
                MissionNextSteps.followUp(m, tickets, warnings),
                MissionReports.objections(c, id),
                MissionReports.ReportAskedBack.of(c, id));
    }

    // ---- tickets ------------------------------------------------------------------------------------------------

    private Kind kind(Ticket t) {
        if (t.id().equals(retroTicket)) return Kind.RETRO;
        if (t.id().equals(decisionTicket)) return Kind.DECISION;
        return c.staff().isStaffingTicket(t.id()) ? Kind.STAFFING : Kind.WORK;
    }

    private ReportTicket ticket(Ticket t) {
        Kind kind = kind(t);
        List<AgentSession> own =
                sessions.stream().filter(s -> s.ticket().equals(t.id())).toList();
        Reopen reopen = kind == Kind.RETRO ? Reopen.NOT_ASKED : reopen(t);
        return new ReportTicket(
                t.id().value(),
                t.title(),
                kind.name(),
                t.status().name(),
                name(t.assignee()),
                t.reviews().stream().map(MissionReportComposer::review).toList(),
                (int) t.reviews().stream()
                        .filter(r -> r.verdict() == ReviewVerdict.REJECTED)
                        .count(),
                producerVerdict(t.deliverable()),
                t.directives().size(),
                own.size(),
                failed(own),
                new Measure(work(own), MEASURED),
                new Measure(elapsed(t), MEASURED),
                costOf(own),
                boundOf(own),
                doneWork(t) ? c.queries().commitFor(t.id()) : null,
                reopen.to(),
                reopen.code(),
                reopen.message(),
                t.environmentBlocker().orElse(null));
    }

    private static ReportReview review(Ticket.Review r) {
        return new ReportReview(r.reviewer().value(), r.verdict().name(), firstLine(r.findings()));
    }

    /** A directive is a query here: where it would go, or the refusal it would meet. */
    private Reopen reopen(Ticket t) {
        return c.workshop()
                .refusalToDirect(director, t.id())
                .map(r -> new Reopen(null, r.code(), r.message()))
                .orElseGet(() ->
                        new Reopen(c.workshop().checkDirective(director, t.id()).value(), null, null));
    }

    /**
     * From its start to its end, or to now while it runs; null for a ticket never started, even one that ended (a
     * ticket cancelled before anyone took it has an end and no start).
     */
    private Double elapsed(Ticket t) {
        if (t.startedAt() == null) return null;
        Instant end = t.endedAt() != null ? t.endedAt() : now;
        return (double) Duration.between(t.startedAt(), end).toMillis();
    }

    /** Work the mission delivered: its commit is named, and its warnings are raised. */
    private boolean doneWork(Ticket t) {
        return t.status() == TicketStatus.DONE && kind(t) == Kind.WORK;
    }

    // ---- warnings -----------------------------------------------------------------------------------------------

    private static List<ReportWarning> outputWarnings(ReportOutput o) {
        if (!o.readable())
            return List.of(new ReportWarning("WORKTREE_UNREADABLE", null, o.agent(), o.path(), null, null));
        List<ReportWarning> out = new ArrayList<>();
        if (o.ignoredCount() > 0) out.add(outputWarning("OUTPUTS_IGNORED", o, o.ignoredCount(), o.ignored()));
        if (o.uncommittedCount() > 0)
            out.add(outputWarning("OUTPUTS_NOT_COMMITTED", o, o.uncommittedCount(), o.uncommitted()));
        return out;
    }

    private static ReportWarning outputWarning(String code, ReportOutput o, int count, List<String> paths) {
        return new ReportWarning(
                code,
                null,
                o.agent(),
                o.path(),
                count,
                String.join(", ", paths.stream().limit(5).toList()));
    }

    /** Done work the producer said was not done, or whose last review did not validate it. */
    private List<ReportWarning> ticketWarnings(Ticket t) {
        if (!doneWork(t)) return List.of();
        List<ReportWarning> out = new ArrayList<>();
        String verdict = producerVerdict(t.deliverable());
        if (verdict != null && NOT_DONE.matcher(verdict).find())
            out.add(new ReportWarning("PRODUCER_PARTIAL", t.id().value(), name(t.assignee()), null, null, verdict));
        Ticket.Review last = t.reviews().isEmpty() ? null : t.reviews().getLast();
        boolean failing = last != null
                && (last.verdict() == ReviewVerdict.REJECTED || last.verdict() == ReviewVerdict.UNVERIFIABLE);
        // A validation warns only when its reservations stand (ADR-0053): notes alone leave nothing open.
        if (failing || (last != null && last.reservationsStand())) {
            out.add(new ReportWarning(
                    failing ? "REVIEW_" + last.verdict().name() : "REVIEW_VALIDATED_WITH_RESERVATIONS",
                    t.id().value(),
                    last.reviewer().value(),
                    null,
                    null,
                    firstLine(last.findings())));
        }
        return out;
    }

    private static List<ReportWarning> checkWarnings(List<CheckRun> runs) {
        if (runs.isEmpty()) return List.of(new ReportWarning("NO_CHECKS", null, null, null, null, null));
        CheckRun last = runs.getLast();
        return last.passed()
                ? List.of()
                : List.of(new ReportWarning(
                        "CHECKS_FAILED", null, last.workspace().value(), null, null, last.command()));
    }

    // ---- totals, reviews, checks, retrospective -----------------------------------------------------------------

    /**
     * The mission's figures; the retrospective's ticket and sessions do not count as the mission's work or time, nor do
     * the turns of speech at the director's meeting (ADR-0049), which count in its cost only.
     */
    private ReportTotals totals(List<Ticket> all) {
        List<Ticket> work =
                all.stream().filter(t -> !t.id().equals(retroTicket)).toList();
        List<AgentSession> notTurns = TurnSessions.work(c, sessions);
        List<AgentSession> worked =
                notTurns.stream().filter(s -> !s.ticket().equals(retroTicket)).toList();
        return new ReportTotals(
                (int) work.stream().filter(t -> t.status() == TicketStatus.DONE).count(),
                (int) work.stream()
                        .filter(t -> t.status() == TicketStatus.CANCELLED)
                        .count(),
                (int) work.stream().filter(t -> !t.status().isTerminal()).count(),
                notTurns.size(),
                failed(notTurns),
                new Measure(span(work), MEASURED),
                new Measure(work(worked), MEASURED),
                costOf(sessions),
                totalBound(),
                m.budgetUsd());
    }

    /** From the first ticket's start to the last one's end, or to now while one is open. */
    private Double span(List<Ticket> work) {
        Instant first = work.stream()
                .map(Ticket::startedAt)
                .filter(Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(null);
        Instant last = work.stream().allMatch(t -> t.status().isTerminal())
                ? work.stream()
                        .map(Ticket::endedAt)
                        .filter(Objects::nonNull)
                        .max(Instant::compareTo)
                        .orElse(null)
                : now;
        return first == null || last == null
                ? null
                : (double) Duration.between(first, last).toMillis();
    }

    /**
     * "With reservations" counts the reviews whose reservations stand (ADR-0053: a kept one, or declared ones that could
     * not be read; before 1.13.0, the verdict); a validation whose items are only notes counts as validated.
     */
    private static ReportReviews reviews(List<Ticket> all) {
        List<Ticket.Review> reviews =
                all.stream().flatMap(t -> t.reviews().stream()).toList();
        List<ReviewVerdict> verdicts =
                reviews.stream().map(Ticket.Review::verdict).toList();
        int reserved =
                (int) reviews.stream().filter(Ticket.Review::reservationsStand).count();
        int validating =
                count(verdicts, ReviewVerdict.VALIDATED) + count(verdicts, ReviewVerdict.VALIDATED_WITH_RESERVATIONS);
        return new ReportReviews(
                verdicts.size(),
                validating - reserved,
                reserved,
                count(verdicts, ReviewVerdict.REJECTED),
                count(verdicts, ReviewVerdict.UNVERIFIABLE));
    }

    private static int count(List<ReviewVerdict> verdicts, ReviewVerdict verdict) {
        return (int) verdicts.stream().filter(v -> v == verdict).count();
    }

    private static ReportCheck check(CheckRun r) {
        return new ReportCheck(
                r.workspace().value(),
                r.command(),
                r.exitCode(),
                r.timedOut(),
                r.durationMs(),
                r.trigger(),
                r.at().toString(),
                r.passed());
    }

    private ReportRetro retro() {
        List<ReportLesson> lessons = c.retros().lessons().stream()
                .filter(l -> l.mission().equals(id))
                .map(l -> new ReportLesson(
                        l.id(),
                        l.audience() == null ? null : l.audience().name(),
                        l.text(),
                        l.status().name()))
                .toList();
        return c.retros()
                .find(id)
                .map(r -> new ReportRetro(state(r), r.chair().value(), r.conclusions(), r.reason(), lessons))
                .orElse(new ReportRetro("NONE", null, List.of(), null, lessons));
    }

    private static String state(Retro r) {
        if (!r.concluded()) return "RUNNING";
        return r.text() == null ? "CLOSED" : "CONCLUDED";
    }

    // ---- figures ------------------------------------------------------------------------------------------------

    /** The time sessions ran, a running one up to now; null when none ran. */
    private Double work(List<AgentSession> of) {
        if (of.isEmpty()) return null;
        long ms = 0;
        for (AgentSession s : of) {
            Instant end = s.endedAt() == null ? now : s.endedAt();
            ms += Math.max(0, Duration.between(s.startedAt(), end).toMillis());
        }
        return (double) ms;
    }

    /** The sum of Claude Code's estimates; null when no session reported one (never shown as zero). */
    /** A simulated mission called no model: 0, said to be simulated, whatever its recording once cost. */
    private Measure costOf(List<AgentSession> of) {
        return m.origin() == Origin.REAL ? new Measure(cost(of), ESTIMATED) : new Measure(0.0, SIMULATED);
    }

    /** The mission's one upper bound, as its budget counts it: sessions with no reported cost, charged turns, once. */
    private MissionReports.CostBound totalBound() {
        int n = c.verification().sessionsBounded(id);
        Double sum = n > 0 ? c.verification().spentUsdBound(id) : null;
        return new MissionReports.CostBound(new Measure(sum, UPPER_BOUND), n);
    }

    private static Double cost(List<AgentSession> of) {
        List<Double> known = of.stream()
                .map(AgentSession::costUsdEstimated)
                .filter(Objects::nonNull)
                .toList();
        return known.isEmpty()
                ? null
                : known.stream().mapToDouble(Double::doubleValue).sum();
    }

    /** The caps of the sessions that reported no cost, apart from the estimate; none for a simulated mission. */
    private MissionReports.CostBound boundOf(List<AgentSession> of) {
        List<Double> caps = m.origin() != Origin.REAL
                ? List.of()
                : of.stream()
                        .filter(s -> s.costUsdEstimated() == null && s.costUsdBound() != null)
                        .map(AgentSession::costUsdBound)
                        .toList();
        Double sum = caps.isEmpty()
                ? null
                : caps.stream().mapToDouble(Double::doubleValue).sum();
        return new MissionReports.CostBound(new Measure(sum, UPPER_BOUND), caps.size());
    }

    private static int failed(List<AgentSession> of) {
        return (int) of.stream().filter(s -> Boolean.TRUE.equals(s.failed())).count();
    }

    private static String name(AgentId agent) {
        return agent == null ? null : agent.value();
    }
}
