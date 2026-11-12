package dev.floor42.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether what a reviewer wrote is a reservation or a note (ADR-0053). A reservation cites a criterion of the ticket by
 * its number and says what to change; anything else is recorded as a note, which does not count as a reservation. This
 * only classifies: the verdict recorded is always the one the reviewer declared, Floor42 never rewrites it.
 */
public final class ReviewCalibration {

    private static final Pattern LABEL = Pattern.compile("(?:C|CRITERION\\s*)?#?(\\d{1,3})");

    private ReviewCalibration() {}

    /** What Floor42 kept of a review: its reservations, its notes, both in the order written. */
    public record Calibrated(List<ReviewReservation> reservations, List<ReviewNote> notes) {
        public Calibrated {
            reservations = List.copyOf(reservations);
            notes = List.copyOf(notes);
        }
    }

    public static Calibrated classify(List<String> criteria, List<ReviewClaim> claims) {
        List<ReviewReservation> kept = new ArrayList<>();
        List<ReviewNote> notes = new ArrayList<>();
        for (ReviewClaim c : claims == null ? List.<ReviewClaim>of() : claims) {
            String text = blankToNull(c.text());
            String change = blankToNull(c.change());
            if (text == null && change == null) continue;
            if (!c.reservation()) {
                notes.add(new ReviewNote(joined(text, change), ReviewNote.Why.NOTE));
                continue;
            }
            Integer n = number(c.criterion());
            if (n == null) notes.add(new ReviewNote(joined(text, change), ReviewNote.Why.NO_CRITERION));
            else if (n < 1 || n > criteria.size())
                notes.add(new ReviewNote(joined(text, change), ReviewNote.Why.UNKNOWN_CRITERION));
            else if (text == null || change == null)
                notes.add(new ReviewNote(joined(text, change), ReviewNote.Why.NO_CHANGE));
            else kept.add(new ReviewReservation(n, criteria.get(n - 1), text, change));
        }
        return new Calibrated(kept, notes);
    }

    /**
     * What Floor42 keeps of a review: {@link #classify}, and when the reviewer declared reservations but wrote no item
     * Floor42 could read, one {@link ReviewNote.Why#UNPARSED} note instead of nothing, so the screen says the items
     * could not be read rather than "none".
     */
    public static Calibrated sort(List<String> criteria, List<ReviewClaim> claims, ReviewVerdict declared) {
        boolean none = claims == null || claims.isEmpty();
        if (none && declared == ReviewVerdict.VALIDATED_WITH_RESERVATIONS) {
            return new Calibrated(List.of(), List.of(new ReviewNote("", ReviewNote.Why.UNPARSED)));
        }
        return classify(criteria, claims);
    }

    private static Integer number(String label) {
        if (label == null) return null;
        Matcher m = LABEL.matcher(label.strip().toUpperCase(Locale.ROOT));
        return m.matches() ? Integer.valueOf(m.group(1)) : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String joined(String text, String change) {
        if (text == null) return change;
        return change == null ? text : text + " | CHANGE: " + change;
    }
}
