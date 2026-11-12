package dev.floor42.domain.workflow;

/**
 * Where a ticket in review stands (ADR-0052): its submission, counted from 1 over its {@code TicketSubmittedForReview},
 * and the position within it, 0 for the review and 1, 2... for the checks in their order. Derived from the journal,
 * never written: a check's session is handed the pair, and its verdict is recorded only while the ticket still stands
 * there.
 */
public record StepAt(int submission, int position) {}
