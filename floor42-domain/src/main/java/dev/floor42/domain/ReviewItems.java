package dev.floor42.domain;

import java.util.List;

/**
 * What Floor42 kept of a review (ADR-0053).
 *
 * @param declared the verdict the reviewer wrote; the review holds the one recorded
 * @param reservations the items that cite a criterion of the ticket and say what to change
 * @param notes everything else, which blocks nothing
 */
public record ReviewItems(ReviewVerdict declared, List<ReviewReservation> reservations, List<ReviewNote> notes) {

    public ReviewItems {
        reservations = List.copyOf(reservations);
        notes = List.copyOf(notes);
    }
}
