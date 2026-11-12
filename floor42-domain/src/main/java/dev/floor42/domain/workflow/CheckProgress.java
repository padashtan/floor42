package dev.floor42.domain.workflow;

import dev.floor42.domain.ReviewRounds;
import dev.floor42.domain.ReviewVerdict;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.workflow.Workflow.Step;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where a ticket's current submission stands among the review and the director's checks (ADR-0052), walked over its
 * verdicts in order, each tagged with the submission it was recorded in. The position within a submission is the number
 * of verdicts that advanced it over {@code [review, check 1, check 2...]} of the pinned workflow: a verdict advances
 * when it validates, or when the step at that position is an advisory check. A verdict that does not validate on a
 * blocking step never advances: a rejection reopens the ticket (the next submission starts at 0), an unverifiable one
 * goes to the director, whose "review again" records nothing and leaves the same step to run again.
 *
 * @param at the ticket's current submission and its position
 * @param check the check at that position; empty at the review, and once every step advanced
 * @param rejections by check key, the rejections each blocking check gave this ticket over all its submissions
 */
public record CheckProgress(StepAt at, Optional<Step.Check> check, Map<String, Integer> rejections) {

    public CheckProgress {
        rejections = Map.copyOf(rejections);
    }

    /** Whether the step at the current position is the last one: its advancing verdict completes the ticket. */
    public boolean last(Workflow w) {
        return at.position() >= w.checks().size();
    }

    /** How many times this check rejected the ticket. */
    public int rejectionsBy(String key) {
        return rejections.getOrDefault(key, 0);
    }

    /** The one walk over the verdicts: where each one was recorded, the rejections, and where the last submission stands. */
    private record Walk(
            List<Integer> positions, Map<String, Integer> rejections, int position, int submission, int resume) {}

    /**
     * For each review, in order, the position of its submission it was recorded at: 0 for the review, 1... for the
     * checks; -1 for a verdict past the last step. The same walk as {@link #of}, so no reader disagrees with it.
     */
    public static List<Integer> positions(Workflow w, List<Ticket.Review> reviews, ReviewRounds rounds) {
        return walk(w, reviews, rounds).positions();
    }

    public static CheckProgress of(Workflow w, List<Ticket.Review> reviews, ReviewRounds rounds) {
        Walk walk = walk(w, reviews, rounds);
        List<Step.Check> checks = w.checks();
        int current = walk.submission() == rounds.submissions() ? walk.position() : walk.resume();
        Optional<Step.Check> check =
                current >= 1 && current <= checks.size() ? Optional.of(checks.get(current - 1)) : Optional.empty();
        return new CheckProgress(new StepAt(rounds.submissions(), current), check, walk.rejections());
    }

    private static Walk walk(Workflow w, List<Ticket.Review> reviews, ReviewRounds rounds) {
        List<Step.Check> checks = w.checks();
        Map<String, Integer> rejections = new LinkedHashMap<>();
        List<Integer> positions = new java.util.ArrayList<>();
        int position = 0;
        int submission = -1;
        // Graph mode (ADR-0062): a resubmission goes back to the step that did not validate, not to the review.
        int resume = 0;
        for (int i = 0; i < reviews.size(); i++) {
            int s = i < rounds.reviewedIn().size() ? rounds.reviewedIn().get(i) : rounds.submissions();
            if (s != submission) {
                submission = s;
                position = resume;
            }
            if (position > checks.size()) {
                positions.add(-1);
                continue;
            }
            positions.add(position);
            Step.Check step = position == 0 ? null : checks.get(position - 1);
            ReviewVerdict v = reviews.get(i).verdict();
            if (v == ReviewVerdict.REJECTED && step != null) rejections.merge(step.key(), 1, Integer::sum);
            if (advances(v, step)) position++;
            resume = w.graph() && position <= checks.size() && !validates(v) ? position : 0;
        }
        return new Walk(positions, rejections, position, submission, resume);
    }

    /** Whether a verdict moves the submission on: it validates, or the step is an advisory check. */
    public static boolean advances(ReviewVerdict v, Step.Check step) {
        return validates(v) || (step != null && !step.blocks());
    }

    static boolean validates(ReviewVerdict v) {
        return v == ReviewVerdict.VALIDATED || v == ReviewVerdict.VALIDATED_WITH_RESERVATIONS;
    }
}
