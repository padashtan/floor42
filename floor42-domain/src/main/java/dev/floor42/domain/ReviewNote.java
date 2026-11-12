package dev.floor42.domain;

/**
 * A remark that does not block: written as a note, or a reservation Floor42 could not tie to a criterion with a change
 * to make (ADR-0053). {@code why} is a code, never a sentence: the screen words it.
 *
 * @param text the reviewer's text, with its change when it gave one
 * @param why why it is a note
 */
public record ReviewNote(String text, Why why) {

    public enum Why {
        /** Written as a note. */
        NOTE,
        /** A reservation that cites no criterion. */
        NO_CRITERION,
        /** A reservation that cites a criterion the ticket does not have. */
        UNKNOWN_CRITERION,
        /** A reservation that says nothing to change. */
        NO_CHANGE,
        /**
         * The reviewer declared reservations but Floor42 could read no RESERVATION or NOTE line: the declared verdict is
         * kept and the screen shows the findings as written. Its text is empty.
         */
        UNPARSED
    }
}
