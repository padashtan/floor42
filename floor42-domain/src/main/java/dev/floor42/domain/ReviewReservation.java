package dev.floor42.domain;

/**
 * A reservation Floor42 kept: it cites one of the ticket's acceptance criteria and says what to change (ADR-0053).
 *
 * @param criterion the criterion's number, 1 for the first, as the reviewer's brief numbered them
 * @param criterionText the criterion as it stood at the review (a later edit of the ticket does not change it)
 * @param gap what falls short of the criterion
 * @param change what to change
 */
public record ReviewReservation(int criterion, String criterionText, String gap, String change) {}
