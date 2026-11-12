package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.agentOf;
import static dev.floor42.domain.Rules.requireOpen;
import static dev.floor42.domain.Rules.requireText;
import static dev.floor42.domain.Rules.requireTransition;

import dev.floor42.domain.event.DeskEvent.QuestionAsked;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TicketEvent.ConsultationAnswered;
import dev.floor42.domain.event.TicketEvent.ConsultationRequested;
import dev.floor42.domain.event.TicketEvent.ReviewItemsRecorded;
import dev.floor42.domain.event.TicketEvent.TicketCompleted;
import dev.floor42.domain.event.TicketEvent.TicketReopened;
import dev.floor42.domain.event.TicketEvent.TicketReviewed;
import dev.floor42.domain.event.TicketEvent.TicketSubmittedForReview;
import dev.floor42.domain.workflow.CheckProgress;
import dev.floor42.domain.workflow.StepAt;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.domain.workflow.Workflow.Step;
import java.util.List;
import java.util.Optional;

/**
 * Independent review (spec section 12): the reviewer is an agent other than the producer, may ask for help once per
 * round (ADR-0018), and only a validating verdict closes the ticket; an unverifiable one goes to the director. The
 * tickets are the {@link TicketBoard}'s, which applies these events.
 */
final class TicketReviews {

    /**
     * Rejections by one blocking check of one ticket, over its submissions, before the director is asked instead of
     * the ticket being reopened once more (ADR-0052, the director's answer 5).
     */
    private static final List<String> ESCALATION_OPTIONS =
            List.of(OptionCode.ACCEPT_AS_IS.label(), OptionCode.SEND_BACK.label(), OptionCode.REVIEW_AGAIN.label());

    /** For a step the environment prevents: nothing here grants anything, the director does it first. */
    private static final List<String> BLOCKED_OPTIONS = List.of(
            OptionCode.DO_IT_MYSELF.label(),
            OptionCode.ALLOW_EXCEPTION.label(),
            OptionCode.INSTALL_MISSING.label(),
            OptionCode.ACCEPT_AS_IS.label(),
            OptionCode.SEND_BACK.label());

    /** Docker out of the sandbox's reach: no exception or install changes it (ADR-0028). */
    private static final List<String> DOCKER_OPTIONS =
            List.of(OptionCode.DO_IT_MYSELF.label(), OptionCode.ACCEPT_AS_IS.label(), OptionCode.SEND_BACK.label());

    private final CompanyView view;

    TicketReviews(CompanyView view) {
        this.view = view;
    }

    List<EventEnvelope> submit(CommandContext ctx, TicketId id, String deliverable) {
        Ticket t = view.existingTicket(id);
        requireOpen(view.missionFor(ctx, t.mission()));
        require(ctx.actor().is(t.assignee), Refusal.NOT_ASSIGNEE, "Only the assignee submits " + id);
        requireTransition(t, TicketStatus.IN_REVIEW);
        require(
                view.questions(t.mission()).stream()
                        .noneMatch(q -> q.status() == QuestionStatus.OPEN && q.blocking() && id.equals(q.ticket())),
                Refusal.BLOCKED_BY_QUESTION,
                id + " waits for the director");
        requireText(deliverable, "deliverable");
        Recorder r = view.record(ctx, t.mission()).emit(new TicketSubmittedForReview(id, deliverable));
        // A task the director did not ask to have reviewed is done with its report (ADR-0061).
        if (unreviewedTask(t)) r.emit(new TicketCompleted(id));
        return r.events();
    }

    /** A peer's advice or a web research, once per review round; the review is given afterwards. */
    List<EventEnvelope> requestConsultation(
            CommandContext ctx, TicketId id, ConsultationKind kind, String role, String question) {
        Ticket t = view.existingTicket(id);
        requireOpen(view.missionFor(ctx, t.mission()));
        require(ctx.actor().kind() == Actor.Kind.AGENT, Refusal.REVIEWER_NOT_AGENT, "Reviewers are agents");
        AgentId reviewer = new AgentId(ctx.actor().id());
        require(
                !reviewer.equals(t.assignee),
                Refusal.SELF_REVIEW,
                reviewer + " produced " + id + " and cannot review it");
        require(t.status == TicketStatus.IN_REVIEW, Refusal.INVALID_TRANSITION, id + " is not in review");
        require(kind != null, Refusal.INVALID_ARGUMENT, "CONSULT or RESEARCH");
        require(
                kind != ConsultationKind.CONSULT || (role != null && !role.isBlank()),
                Refusal.INVALID_ARGUMENT,
                "Whom to consult");
        requireText(question, "question");
        require(
                t.consultationThisRound().isEmpty(),
                Refusal.ALREADY_CONSULTED,
                "One consultation per review: " + reviewer + " already asked for help on " + id);
        String whom = kind == ConsultationKind.CONSULT ? role.strip() : null;
        return view.record(ctx, t.mission())
                .emit(new ConsultationRequested(id, reviewer, kind, whom, question.strip()))
                .events();
    }

    /** The consulted agent's answer, or Floor42's own when the research was not allowed (by = null). */
    List<EventEnvelope> answerConsultation(CommandContext ctx, TicketId id, String answer) {
        Ticket t = view.existingTicket(id);
        requireOpen(view.missionFor(ctx, t.mission()));
        require(
                !t.consultations().isEmpty() && t.consultations().getLast().pending(),
                Refusal.NO_CONSULTATION,
                "No consultation pending on " + id);
        require(ctx.actor().kind() != Actor.Kind.DIRECTOR, Refusal.FORBIDDEN, "Answered by an agent or by Floor42");
        requireText(answer, "answer");
        AgentId by = agentOf(ctx);
        require(
                by == null || !by.equals(t.assignee),
                Refusal.SELF_REVIEW,
                "The producer cannot advise its own reviewer");
        require(by == null || !view.existingAgent(by).dismissed, Refusal.AGENT_DISMISSED, by + " has left the company");
        return view.record(ctx, t.mission())
                .emit(new ConsultationAnswered(id, by, answer))
                .events();
    }

    List<EventEnvelope> review(CommandContext ctx, TicketId id, ReviewVerdict verdict, String findings) {
        return review(ctx, id, verdict, findings, null);
    }

    /**
     * A review whose items Floor42 sorts (ADR-0053): with {@code claims} non-null, a {@link ReviewItemsRecorded}
     * follows the {@link TicketReviewed}, whose verdict is the declared one, untouched. Null keeps
     * the review as given, unsorted (simulated missions and the scenarios recorded before 1.13.0).
     */
    List<EventEnvelope> review(
            CommandContext ctx, TicketId id, ReviewVerdict declared, String findings, List<ReviewClaim> claims) {
        return review(ctx, id, declared, findings, claims, null);
    }

    /**
     * The verdict of the review or of a check (ADR-0052). On a mission whose pin holds a check, it is recorded only
     * while the ticket still stands at {@code at}: the check-then-act runs inside one command, under the journal's
     * single writer (the office's lock), so a directive or another verdict cannot slip in between.
     */
    List<EventEnvelope> review(
            CommandContext ctx,
            TicketId id,
            ReviewVerdict declared,
            String findings,
            List<ReviewClaim> claims,
            StepAt at) {
        Ticket t = view.existingTicket(id);
        AgentId reviewer = reviewer(ctx, t);
        Optional<Workflow> checked = checked(t);
        Optional<CheckProgress> progress = checked.map(w -> guard(t, w, at));
        require(t.status == TicketStatus.IN_REVIEW, Refusal.INVALID_TRANSITION, id + " is not in review");
        requireText(findings, "findings");
        Step.Check step = progress.flatMap(CheckProgress::check).orElse(null);
        // A check's one criterion is what it hands over (ADR-0052); the review keeps the ticket's own.
        List<String> criteria = step == null ? t.acceptanceCriteria() : List.of(step.handsOver());
        ReviewItemsRecorded items = claims == null ? null : items(t, reviewer, declared, claims, criteria);
        // The reviewer's verdict is recorded as declared: calibration only sorts the items (ADR-0053).
        Recorder r = view.record(ctx, t.mission()).emit(new TicketReviewed(id, reviewer, declared, findings));
        if (items != null) r.emit(items);
        if (CheckProgress.advances(declared, step)) {
            // The completion follows the last advancing verdict only: a check left, the ticket stays in review.
            if (checked.isEmpty() || progress.get().last(checked.get())) {
                r.emit(new TicketCompleted(id));
                WorkflowTasks.workChanged(view, r, t);
            }
        } else if (step != null
                && declared == ReviewVerdict.REJECTED
                && capped(t, progress.get(), step, checked.get())) {
            r.as(Actor.system()).emit(rejectedTooOften(t, step, reviewer, progress.get(), findings));
        } else {
            notValidated(r, t, reviewer, declared, findings);
        }
        return r.events();
    }

    /** The agent giving the verdict, checked: an agent of the company, not the producer. */
    private AgentId reviewer(CommandContext ctx, Ticket t) {
        requireOpen(view.missionFor(ctx, t.mission()));
        require(ctx.actor().kind() == Actor.Kind.AGENT, Refusal.REVIEWER_NOT_AGENT, "Reviews are made by agents");
        AgentId reviewer = new AgentId(ctx.actor().id());
        require(!view.existingAgent(reviewer).dismissed, Refusal.AGENT_DISMISSED, reviewer + " has left the company");
        require(
                !reviewer.equals(t.assignee),
                Refusal.SELF_REVIEW,
                reviewer + " produced " + t.id() + " and cannot review it");
        return reviewer;
    }

    /** The pinned workflow, when it holds a check: only then is a verdict guarded by its position. */
    private Optional<Workflow> checked(Ticket t) {
        return view.workflowOf(t.mission())
                .runnable()
                .map(w -> w.forNode(view.checksRun(t)))
                .filter(w -> !w.checks().isEmpty() && view.workflowStepOf(t).isEmpty());
    }

    private boolean unreviewedTask(Ticket t) {
        return view.workflowStepOf(t)
                .flatMap(k -> view.workflowOf(t.mission()).runnable().flatMap(w -> w.task(k)))
                .map(k -> !k.reviewed())
                .orElse(false);
    }

    /**
     * Where the ticket stands, which must be where the session was sent: a stale verdict is never recorded. A verdict
     * that does not advance leaves the position where it was, so a question it opened (an escalation, the rejection
     * cap) also means the step moved on: a second session sent to that position is refused until the director answers.
     */
    private CheckProgress guard(Ticket t, Workflow w, StepAt at) {
        CheckProgress p = CheckProgress.of(w, t.reviews(), view.reviewRounds(t.id()));
        boolean asked = view.questions(t.mission()).stream()
                .anyMatch(q -> q.status() == QuestionStatus.OPEN && q.blocking() && t.id().equals(q.ticket()));
        require(
                t.status == TicketStatus.IN_REVIEW && p.at().equals(at) && !asked,
                Refusal.STEP_MOVED_ON,
                t.id() + " moved on from " + at + ": this verdict is not recorded");
        return p;
    }

    /** The rejection that reaches the workflow's cap ({@link Workflow#checkRejectionCap}): asked instead of reopened. */
    private boolean capped(Ticket t, CheckProgress p, Step.Check step, Workflow w) {
        long before = w.reworksTogether() ? earlierRejections(t) : p.rejectionsBy(step.key());
        return before + 1 >= w.checkRejectionCap();
    }

    /** Graph mode: the rejections before the one being recorded (it is among the reviews), failed node tests included. */
    private long earlierRejections(Ticket t) {
        return t.rejections() - 1 + view.nodeTestFailures(t.id());
    }

    /** A review or a blocking check that does not validate: back to work, or to the director. */
    private void notValidated(Recorder r, Ticket t, AgentId reviewer, ReviewVerdict verdict, String findings) {
        // The same environment would stop the next session too: the director decides, the ticket waits in review.
        blocker(t, findings)
                .ifPresentOrElse(b -> r.as(Actor.system()).emit(blocked(t, reviewer, verdict, findings, b)), () -> {
                    if (verdict == ReviewVerdict.REJECTED) r.emit(new TicketReopened(t.id(), findings));
                    else r.as(Actor.system()).emit(escalation(t, reviewer, findings));
                });
    }

    /**
     * A blocking check that rejected the ticket as often as the cap says: one blocking question with the review's
     * escalation options instead of a fourth reopening (ADR-0052). "Send back" reopens it, and the next rejection asks
     * again; "accept as is" completes it, its later checks included; "review again" runs the same check again.
     */
    private QuestionAsked rejectedTooOften(
            Ticket t, Step.Check step, AgentId reviewer, CheckProgress p, String findings) {
        TicketId id = t.id();
        int times = p.rejectionsBy(step.key()) + 1;
        return new QuestionAsked(
                view.nextQuestionId(),
                t.mission(),
                QuestionKind.REVIEW_ESCALATION,
                null,
                "The check \"" + step.title() + "\" rejected " + id + " " + times + " times",
                reviewer + ": " + findings,
                ESCALATION_OPTIONS,
                null,
                Criticality.HIGH,
                true,
                id,
                null,
                Phrase.of(
                        "checkRejectedTooOften",
                        "ticket",
                        id.value(),
                        "check",
                        step.title(),
                        "times",
                        Integer.toString(times),
                        "reviewer",
                        reviewer.value(),
                        "findings",
                        findings),
                OptionCode.codesOf(ESCALATION_OPTIONS));
    }

    private static ReviewItemsRecorded items(
            Ticket t, AgentId reviewer, ReviewVerdict declared, List<ReviewClaim> claims, List<String> criteria) {
        ReviewCalibration.Calibrated sorted = ReviewCalibration.sort(criteria, claims, declared);
        return new ReviewItemsRecorded(t.id(), reviewer, declared, sorted.reservations(), sorted.notes());
    }

    /** The environment's wall that sends a review that does not validate to the director instead of back to work. */
    private java.util.Optional<EnvironmentBlocker> blocker(Ticket t, String findings) {
        // Only the reviewer can say the blocker is all that is left: a developer's marker alone never closes a review.
        if (EnvironmentBlocker.onlyIssue(findings) && t.environmentBlocker().isPresent()) return t.environmentBlocker();
        // The director said they do the step the sandbox cannot (missing-tools question): a review that does not
        // validate goes to them, never back to work for a step no session there can do.
        return EnvironmentBlocker.directorStep(view.questions(t.mission()), t.id())
                .map(b -> t.environmentBlocker().orElse(b));
    }

    /** ONE question naming what the environment lacks and what the director may do about it. */
    private QuestionAsked blocked(
            Ticket t, AgentId reviewer, ReviewVerdict verdict, String findings, EnvironmentBlocker b) {
        TicketId id = t.id();
        String step = b.step() == null ? "" : b.step();
        String said = findings;
        List<String> options = b.docker() ? DOCKER_OPTIONS : BLOCKED_OPTIONS;
        return new QuestionAsked(
                view.nextQuestionId(),
                t.mission(),
                QuestionKind.REVIEW_ESCALATION,
                null,
                id + " is blocked by the environment: it lacks " + b.missing(),
                blockedContext(b, step, reviewer + " (" + verdict + "): " + said),
                options,
                null,
                Criticality.HIGH,
                true,
                id,
                null,
                blockedPhrase(id, b, step, reviewer, verdict, said),
                OptionCode.codesOf(options));
    }

    /** What the screen writes in its language: codes and values, never the English context. */
    private static Phrase blockedPhrase(
            TicketId id, EnvironmentBlocker b, String step, AgentId reviewer, ReviewVerdict verdict, String said) {
        return Phrase.of(
                blockedCode(b, step),
                "ticket",
                id.value(),
                "missing",
                b.missing(),
                "step",
                step,
                "reviewer",
                reviewer.value(),
                "verdict",
                verdict.name(),
                "findings",
                said);
    }

    /** Docker has its own codes: the sandbox cannot reach its daemon, so installing is no way out. */
    private static String blockedCode(EnvironmentBlocker b, String step) {
        if (b.docker()) return step.isEmpty() ? "environmentBlockedDockerNoStep" : "environmentBlockedDocker";
        return step.isEmpty() ? "environmentBlockedNoStep" : "environmentBlocked";
    }

    private static String blockedContext(EnvironmentBlocker b, String step, String review) {
        String needed = (step.isEmpty() ? "" : ", needed to " + step) + ". ";
        if (b.docker()) {
            return "The developer reports that the environment lacks " + b.missing() + needed
                    + "The shell sandbox cannot reach the Docker daemon: its socket is hidden there and the docker"
                    + " group is dropped, so installing Docker will not help and no setting grants it. Do the Docker"
                    + " step yourself, accept the ticket as is, or send it back to work without that step."
                    + " Reviewer " + review;
        }
        return "The developer reports that the environment lacks " + b.missing() + needed
                + "Another session there would hit the same wall. Do the step yourself, allow an exception (grant"
                + " it first in Permissions or Settings), install what is missing, or accept the ticket as is."
                + " Reviewer " + review;
    }

    private QuestionAsked escalation(Ticket t, AgentId reviewer, String findings) {
        TicketId id = t.id();
        return new QuestionAsked(
                view.nextQuestionId(),
                t.mission(),
                QuestionKind.REVIEW_ESCALATION,
                null,
                "Review of " + id + " could not be verified",
                reviewer + ": " + findings,
                ESCALATION_OPTIONS,
                null,
                Criticality.HIGH,
                true,
                id,
                null,
                Phrase.of(
                        "reviewUnverifiable", "ticket", id.value(), "reviewer", reviewer.value(), "findings", findings),
                OptionCode.codesOf(ESCALATION_OPTIONS));
    }

    /** The director settles an escalated review still in review: accepted, sent back, or reviewed again. */
    void escalationAnswered(Recorder r, Question q, String chosen) {
        Ticket t = view.ticket(q.ticket()).orElseThrow();
        if (t.status != TicketStatus.IN_REVIEW) return;
        OptionCode code = q.codeOf(chosen);
        if (code == null) return;
        switch (code) {
            // The director does the step, or takes the ticket without it: nothing is left for an agent.
            case ACCEPT_AS_IS, DO_IT_MYSELF -> {
                r.emit(new TicketCompleted(t.id()));
                WorkflowTasks.workChanged(view, r, t);
            }
            // The director changed the environment (or wants other work done): the developer tries again.
            case SEND_BACK, ALLOW_EXCEPTION, INSTALL_MISSING ->
                r.emit(new TicketReopened(t.id(), "director: " + code.label()));
            default -> {}
        }
    }
}
