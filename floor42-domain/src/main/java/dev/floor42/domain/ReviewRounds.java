package dev.floor42.domain;

import java.util.List;

/**
 * A ticket's submissions, counted, and the submission each of its reviews was recorded in (ADR-0052). Derived while the
 * journal is applied, never written: a replay rebuilds it from {@code TicketSubmittedForReview} and
 * {@code TicketReviewed} alone.
 *
 * @param submissions how many times the ticket was submitted for review
 * @param reviewedIn for each review of {@link Ticket#reviews()}, in the same order, the submission it was recorded in
 *     (0 for a review recorded before any submission, as a simulated scenario may)
 */
public record ReviewRounds(int submissions, List<Integer> reviewedIn) {

    public static final ReviewRounds NONE = new ReviewRounds(0, List.of());

    public ReviewRounds {
        reviewedIn = List.copyOf(reviewedIn);
    }

    ReviewRounds submitted() {
        return new ReviewRounds(submissions + 1, reviewedIn);
    }

    ReviewRounds reviewed() {
        List<Integer> tags = new java.util.ArrayList<>(reviewedIn);
        tags.add(submissions);
        return new ReviewRounds(submissions, tags);
    }
}
