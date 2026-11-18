package dev.floor42.server.journal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/** How the journal's SQLite connections are set up and let go: WAL while the daemon runs, no native extensions. */
final class JournalConnections {

    private static final Logger LOG = Logger.getLogger(JournalConnections.class);

    private JournalConnections() {}

    /**
     * WAL while the daemon runs (v1.4): a reader (a backup's VACUUM INTO, the continuity anchor) no longer holds the
     * writer up, so an append's tail latency stays low under a backup (M0: p95 28 ms in WAL, 154 ms in rollback mode).
     * The mode is stored in the file, so one connection sets it for all. {@code synchronous} stays at the driver's FULL
     * (pinned by a test): a commit is on disk when {@code SqliteJournal#append} returns. NORMAL would risk the last commits in a power
     * cut, and the signed checkpoints and the continuity record would then read as a journal that went back in time.
     */
    static void enableWal(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            useWal(c);
        } catch (SQLException e) {
            throw new JournalException("Cannot put the journal in WAL mode", e);
        }
    }

    static void useWal(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("PRAGMA journal_mode=WAL")) {
            String mode = rs.next() ? rs.getString(1) : null;
            if (!"wal".equalsIgnoreCase(mode)) throw new SQLException("journal_mode is " + mode + ", not wal");
        }
    }

    /**
     * At a clean stop: folds the WAL into the file and goes back to rollback mode, so the folder holds one file and a
     * Floor42 1.3.0 opens it as before (downgrade). Best effort: another connection still open keeps the WAL, which
     * is then complete and harmless (SQLite reads it, 1.3.0 included, only if its -wal file stays beside the journal).
     */
    static void leaveWal(DataSource dataSource, long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        String mode = null;
        String why = null;
        do {
            // Agroal's flush is asynchronous: idle connections may still be open, so flush and retry to the deadline.
            if (dataSource instanceof io.agroal.api.AgroalDataSource pool) {
                pool.flush(io.agroal.api.AgroalDataSource.FlushMode.IDLE);
            }
            try {
                mode = leaveOnce(dataSource);
                if ("delete".equalsIgnoreCase(mode)) return;
            } catch (SQLException e) {
                // Handled: the message is kept in why, the loop retries to the deadline, the warning below names it.
                why = e.getMessage();
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                // Handled: the interrupt flag is restored and the retry loop ends; the warning below reports the mode.
                Thread.currentThread().interrupt();
                break;
            }
        } while (System.nanoTime() < deadline);
        LOG.warnf(
                "The journal stays in WAL mode at stop (%s): to go back to Floor42 1.3.0, keep floor42.db-wal and"
                        + " floor42.db-shm beside floor42.db, or start 1.4.0 again and stop it cleanly first",
                why != null ? why : mode);
    }

    /** One attempt: folds the WAL in, asks for rollback mode, returns the mode SQLite reports. */
    /** Undoes an open transaction after a failed write; a failure of the rollback rides along with the cause. */
    static void rollBack(Connection c, Exception cause) {
        try {
            if (!c.getAutoCommit()) c.rollback();
        } catch (SQLException rollback) {
            // Handled: the cause is the first failure, this one rides along
            cause.addSuppressed(rollback);
        }
    }

    private static String leaveOnce(DataSource dataSource) throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement()) {
            s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            try (ResultSet rs = s.executeQuery("PRAGMA journal_mode=DELETE")) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * SQLite's {@code load_extension()} runs native code from a file: an open journal connection must refuse it. Asked
     * of the connection itself, so it holds whatever configured the pool.
     */
    static void refuseExtensionLoading(DataSource dataSource) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT load_extension('floor42-no-such-extension')")) {
            ps.executeQuery().close();
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("not authorized")) return;
            throw new JournalException(
                    "The journal's connection loads SQLite extensions (" + e.getMessage() + "): refused", e);
        }
        throw new JournalException("The journal's connection loads SQLite extensions: refused", null);
    }
}
