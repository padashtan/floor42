package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.Phrase;
import dev.floor42.domain.Refusal;
import dev.floor42.domain.ReviewVerdict;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.workflow.CheckProgress;
import dev.floor42.domain.workflow.MissionInterpreter;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.Sandbox;
import dev.floor42.server.runtime.SessionReport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An independent review, by another agent in another session, reading the producer's worktree with read-only tools. A
 * rejection sends the ticket back; one rejected too often is escalated to the director.
 */
@ApplicationScoped
public class ReviewRunner implements TaskRunner {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(ReviewRunner.class);
    public static final List<String> REVIEW_TOOLS = List.of("Read", "Glob", "Grep", "Skill", "Agent");
    private static final Pattern VERDICT = Pattern.compile(
            Pattern.quote(Briefs.VERDICT_MARKER) + "\\s*(VALIDATED_WITH_RESERVATIONS|VALIDATED|REJECTED|UNVERIFIABLE)");

    private final OfficeService office;
    private final AgentWorktrees worktrees;
    private final SessionGate gate;
    private final MissionPermissions permissions;
    private final MissionInterpreter interpreter;
    private final Sandbox sandbox;

    @Inject
    ReviewRunner(
            OfficeService office,
            AgentWorktrees worktrees,
            SessionGate gate,
            MissionPermissions permissions,
            MissionInterpreter interpreter,
            Sandbox sandbox) {
        this.office = office;
        this.worktrees = worktrees;
        this.gate = gate;
        this.permissions = permissions;
        this.interpreter = interpreter;
        this.sandbox = sandbox;
    }

    @Override
    public Work work() {
        return Work.REVIEW;
    }

    @Override
    public void run(MissionId m, Task task) {
        review(m, task.ticket(), task.agent());
    }

    void review(MissionId m, TicketId t, AgentId reviewer) {
        // The review is independent only if the reviewer cannot change what it reviews: enforced, not just unoffered.
        permissions.denyAll(m, reviewer, MissionPermissions.CHANGES);
        AgentId producer = office.query(c -> c.tickets().find(t).orElseThrow().assignee());
        // A researcher's task has no worktree: its report is read where its session ran (ADR-0061).
        boolean researched = office.query(c -> c.workflows()
                .stepOf(c.tickets().find(t).orElseThrow())
                .flatMap(k -> c.workflows().of(m).runnable().flatMap(w -> w.task(k)))
                .map(k -> k.runBy() == dev.floor42.domain.WorkRole.RESEARCHER)
                .orElse(false));
        Path worktree = researched ? worktrees.research(m) : worktrees.path(m, producer);
        // A reviewer may run the approved test commands, but only in the sandbox with the work mounted read-only
        // (ADR-0046, amended 2026-11-05); a researcher's report folder has nothing to rerun.
        boolean shell = mayShell(researched, sandbox) && !permissions.directorRefusesShell(m, reviewer, t);
        // Before the brief, which states what the table then allows; removed when the session ends, whatever happens.
        Optional<PermissionRule> shellRule = shell ? permissions.allowReviewShell(m, reviewer, t) : Optional.empty();
        Sent sent;
        Optional<SessionReport> report;
        try {
            sent = sentFor(m, t, reviewer, worktree, shell);
            report = gate.run(sent.spec());
        } finally {
            shellRule.ifPresent(r -> dropShellRule(m, r));
        }
        if (report.isEmpty()) return;
        String text = report.get().finalText() == null ? "" : report.get().finalText();
        if (!askedForHelp(t, reviewer, report.get(), text)) conclude(m, t, reviewer, report.get(), text, sent.at());
    }

    /**
     * The step and its (submission, position) are taken once, here: the verdict is recorded only if the ticket still
     * stands there when it comes back (ADR-0052). A check's session has a reviewer's tools and rules: its text is in
     * the brief as data, nothing in it reaches the spec.
     */
    private Sent sentFor(MissionId m, TicketId t, AgentId reviewer, Path worktree, boolean shell) {
        return office.query(c -> {
            Agent a = c.staff().find(reviewer).orElseThrow();
            Mission mission = c.missions().find(m).orElseThrow();
            Ticket ticket = c.tickets().find(t).orElseThrow();
            Optional<CheckProgress> at = interpreter.progress(c, ticket);
            String brief = at.flatMap(CheckProgress::check)
                    .map(k -> CheckBrief.of(c, mission, ticket, a, k, at.get().at()))
                    .orElseGet(() -> Briefs.reviewer(c, mission, ticket, a));
            List<String> tools = shell ? withBash(REVIEW_TOOLS) : REVIEW_TOOLS;
            SessionSpec spec = SessionGate.spec(c, m, t, a, worktree, brief, tools);
            return new Sent(shell ? spec.withReadOnlyShell() : spec, at);
        });
    }

    /** A reviewer's shell needs a worktree to rerun in and a sandbox that is on and works: never without it. */
    static boolean mayShell(boolean researched, Sandbox sandbox) {
        return !researched && sandbox.enabled() && sandbox.available();
    }

    /**
     * The reviewer's tools with a shell: Bash in, Skill and Agent out. Whether a skill's {@code !command} lines or a
     * sub-agent's Bash go through the sandbox wrapping is not measured (2026-11-05), so a reviewer that has a shell
     * has neither: every command it runs is one Floor42 wraps read-only and offline.
     */
    static List<String> withBash(List<String> tools) {
        List<String> out = new java.util.ArrayList<>(tools);
        out.removeAll(List.of("Skill", "Agent"));
        out.add("Bash");
        return out;
    }

    /** The reviewer's Bash line lives for one session; the mission closing meanwhile leaves nothing to remove. */
    private void dropShellRule(MissionId m, PermissionRule rule) {
        try {
            permissions.remove(m, rule);
        } catch (RuntimeException e) {
            // Handled: the mission closed or the rule is gone already; the session is over and cannot use it
            LOG.debugf("Reviewer shell line of %s not removed: %s", m, e.getMessage());
        }
    }

    /** What the session was started with: its spec, and where the ticket stood among its checks, if it has any. */
    private record Sent(SessionSpec spec, Optional<CheckProgress> at) {}

    /** In doubt, the reviewer may ask for help once per review instead of concluding (ADR-0018). */
    private boolean askedForHelp(TicketId t, AgentId reviewer, SessionReport report, String text) {
        Optional<Briefs.Help> help =
                report.failed() || VERDICT.matcher(text).find() ? Optional.empty() : Briefs.help(text);
        if (help.isEmpty()
                || !office.query(c -> c.tickets()
                        .find(t)
                        .orElseThrow()
                        .consultationThisRound()
                        .isEmpty())) return false;
        Briefs.Help h = help.get();
        office.execute(c -> c.tickets()
                .requestConsultation(
                        office.context(Actor.agent(reviewer), Origin.REAL), t, h.kind(), h.role(), h.question()));
        return true;
    }

    private void conclude(
            MissionId m, TicketId t, AgentId reviewer, SessionReport report, String text, Optional<CheckProgress> at) {
        ReviewVerdict verdict = verdictOf(text);
        String findings = report.failed()
                ? "The review session failed: " + String.join("; ", report.notes())
                : text.isBlank() ? "The reviewer gave no findings" : text;
        ReviewVerdict recorded = report.failed() ? ReviewVerdict.UNVERIFIABLE : verdict;
        List<dev.floor42.domain.ReviewClaim> claims = report.failed() ? List.of() : ReviewClaims.of(text);
        try {
            // A null position keeps the unguarded review of a mission whose workflow holds no check.
            office.execute(c -> c.tickets()
                    .review(
                            office.context(Actor.agent(reviewer), Origin.REAL),
                            t,
                            recorded,
                            findings,
                            claims,
                            at.map(CheckProgress::at).orElse(null)));
        } catch (DomainException e) {
            if (!Refusal.STEP_MOVED_ON.name().equals(e.code())) throw e;
            // Handled: the ticket moved on while this session ran (a directive, a newer verdict): nothing is recorded.
            LOG.infof("%s moved on while %s ran: its verdict is not recorded", t, reviewer);
            return;
        }
        // Rejected by the review itself: the ticket is back in progress, the dispatcher sends its developer back to it.
        // A check's rejections have their own cap, decided by the review command (ADR-0052).
        boolean review = at.isEmpty() || at.get().check().isEmpty();
        if (review && verdict == ReviewVerdict.REJECTED && !report.failed()) escalateIfRejectedTooOften(m, t);
    }

    /**
     * The review's own rejections: on a mission with checks, theirs are counted apart (the review's threshold is
     * unchanged, ADR-0052).
     */
    private long reviewRejections(Company c, Ticket ticket) {
        long all = ticket.reviews().stream()
                .filter(r -> r.verdict() == ReviewVerdict.REJECTED)
                .count();
        return all
                - interpreter
                        .progress(c, ticket)
                        .map(p -> p.rejections().values().stream()
                                .mapToLong(Integer::longValue)
                                .sum())
                        .orElse(0L);
    }

    private void escalateIfRejectedTooOften(MissionId m, TicketId t) {
        // Kept in review by a step the environment prevents: the director already has the one question that says so.
        if (office.query(c -> c.tickets().find(t).orElseThrow().status() != TicketStatus.IN_PROGRESS)) return;
        Optional<Workflow> pinned = office.query(c -> c.workflows().of(m).runnable());
        boolean together = pinned.map(Workflow::reworksTogether).orElse(false);
        long rejections = office.query(c -> {
            Ticket ticket = c.tickets().find(t).orElseThrow();
            return together
                    ? ticket.reviews().stream()
                                    .filter(r -> r.verdict() == ReviewVerdict.REJECTED)
                                    .count()
                            // A failed node test is a rejection of the node's single rework (ADR-0062).
                            + c.verification().nodeTestFailures(t)
                    : reviewRejections(c, ticket);
        });
        int cap = pinned.map(Workflow::reviewRejectionCap).orElse(2);
        if (rejections < cap) return;
        askRejectedTooOften(office, m, t, rejections);
    }

    /** The director is asked once a ticket was rejected as often as the workflow allows. */
    static void askRejectedTooOften(OfficeService office, MissionId m, TicketId t, long rejections) {
        office.execute(c -> c.desk()
                .ask(
                        office.context(Actor.system(), Origin.REAL),
                        m,
                        t + " was rejected " + rejections + " times",
                        "The reviewer still rejects the report. Open the workshop, adjust the objective, or cancel the"
                                + " ticket.",
                        List.of(),
                        null,
                        Criticality.HIGH,
                        false,
                        t,
                        Phrase.of("rejectedTimes", "ticket", t.value(), "times", Long.toString(rejections))));
    }

    /** The last verdict line wins; no recognizable verdict means the review cannot be trusted as is. */
    static ReviewVerdict verdictOf(String text) {
        Matcher m = VERDICT.matcher(text);
        ReviewVerdict found = ReviewVerdict.UNVERIFIABLE;
        while (m.find()) found = ReviewVerdict.valueOf(m.group(1));
        return found;
    }
}
