package dev.floor42.server.journal;

/**
 * A journal row that replay left out (ADR-0042): which one, why, and what was wrong with it, never its payload (an event
 * may quote a command, a file or a token). The row itself stays in the journal, untouched.
 *
 * @param sequence the row's place in the journal
 * @param eventId the event's id, null when unreadable
 * @param type the stored event type, as written
 * @param mission the mission the row names, null when none
 * @param detail what failed, in words that never quote a stored value
 * @param ids the ticket (T-n), question (Q-n), incident (I-n) and meeting (MT-n) numbers the row names, kept from new
 *     ones; read leniently, so an id held under another field name is not found
 */
public record Unreadable(
        long sequence,
        String eventId,
        String type,
        String mission,
        Reason reason,
        String detail,
        java.util.List<String> ids) {

    public enum Reason {
        /** The row cannot be read: broken JSON, a missing or malformed field. */
        CORRUPT,
        /** The row names an event type, a field or a value this version does not know: a newer Floor42 wrote it. */
        NEWER_VERSION,
        /** The row was read but could not be applied, after an earlier row was already left out. */
        NOT_APPLICABLE
    }
}
