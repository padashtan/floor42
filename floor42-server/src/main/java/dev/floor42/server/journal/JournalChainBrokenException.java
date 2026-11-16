package dev.floor42.server.journal;

/**
 * The journal's hash chain is broken and the director has not acknowledged it (ADR-0079): nothing is written. Reported
 * as {@code JOURNAL_CHAIN_BROKEN}; the message never carries a stored value.
 */
public final class JournalChainBrokenException extends JournalException {

    public JournalChainBrokenException(String message) {
        super(message, null);
    }
}
