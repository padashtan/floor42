package dev.floor42.server.journal;

/**
 * Another process holds a lock on the journal file (a sqlite3 shell, a database browser, a backup tool) and SQLite's
 * busy timeout ran out: nothing was written. Floor42 does not prevent another process of the same user from opening its
 * journal; it says so instead of failing without a reason.
 */
public final class JournalLockedException extends JournalException {

    public JournalLockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
