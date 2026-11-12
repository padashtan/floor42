package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import java.util.List;

/**
 * What the director's answer settles, by kind of question: an approved action becomes authorized, a committee gets its
 * ruling or its answers, a budget is raised, an escalated review gets its outcome. Each area settles its own part.
 */
final class Answers {

    private final Desk desk;
    private final ApprovalPolicy approvals;
    private final CommitteeHall committees;
    private final Verification verification;
    private final TicketReviews reviews;

    Answers(
            Desk desk,
            ApprovalPolicy approvals,
            CommitteeHall committees,
            Verification verification,
            TicketReviews reviews) {
        this.desk = desk;
        this.approvals = approvals;
        this.committees = committees;
        this.verification = verification;
        this.reviews = reviews;
    }

    List<EventEnvelope> answer(CommandContext ctx, QuestionId id, String answer) {
        return answer(ctx, id, answer, null);
    }

    List<Question> liftable(Question q) {
        return approvals.liftable(q);
    }

    /** @param askAgain nullable: the remembered refusal the director lifts with this answer (see ApprovalPolicy) */
    List<EventEnvelope> answer(CommandContext ctx, QuestionId id, String answer, QuestionId askAgain) {
        String chosen = desk.checkAnswer(ctx, id, answer);
        Question q = desk.existing(id);
        if (askAgain != null) approvals.checkLift(q, askAgain);
        Recorder r = desk.recordAnswer(ctx, q, chosen);
        if (askAgain != null) approvals.lift(r, q, askAgain);
        switch (q.kind()) {
            // The answer is the record; Floor42's runtime runs, schedules or drops a step held for the plan limit.
            case OPEN, PLAN_LIMIT, STAFFING -> {}
            case ACTION_APPROVAL -> approvals.answered(r, q, chosen);
            case COMMITTEE_ARBITRATION -> committees.arbitrated(r, q, chosen);
            case CLARIFICATION -> committees.clarificationAnswered(r, q);
            case BUDGET_REACHED -> verification.budgetAnswered(r, q, chosen);
            case REVIEW_ESCALATION -> reviews.escalationAnswered(r, q, chosen);
        }
        return r.events();
    }
}
