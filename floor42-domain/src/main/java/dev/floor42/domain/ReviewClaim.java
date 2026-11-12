package dev.floor42.domain;

/**
 * One item a reviewer wrote, as written, before Floor42 checks it against the ticket's criteria (ADR-0053).
 *
 * @param reservation true for a {@code RESERVATION} line, false for a {@code NOTE} line
 * @param criterion the criterion label the reservation cites ({@code C2}), as written; null when it cites none
 * @param text what falls short (a reservation) or the remark (a note)
 * @param change what to change, for a reservation; null when the line says none
 */
public record ReviewClaim(boolean reservation, String criterion, String text, String change) {

    public static ReviewClaim note(String text) {
        return new ReviewClaim(false, null, text, null);
    }

    public static ReviewClaim reservation(String criterion, String text, String change) {
        return new ReviewClaim(true, criterion, text, change);
    }
}
