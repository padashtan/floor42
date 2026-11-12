package dev.floor42.server.journal;

import dev.floor42.domain.Actor;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.server.config.DataFolders;
import dev.floor42.server.security.ConfigGuard;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SqliteJournal implements Journal {

    private static final Logger LOG = Logger.getLogger(SqliteJournal.class);

    private static final String COLUMNS = "sequence, event_id, occurred_at, origin, actor_kind, actor_id, mission_id,"
            + " authorization_ref, causation_id, event_type, payload, format_version";
    private static final String INSERT =
            "INSERT INTO event_journal (event_id, occurred_at, origin, actor_kind, actor_id,"
                    + " mission_id, authorization_ref, causation_id, event_type, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** The row format this version writes and reads ({@code format_version}, V1). */
    static final int FORMAT_VERSION = 1;

    private final EventCodec codec = new EventCodec();

    @Inject
    DataSource dataSource;

    @Inject
    Flyway flyway;

    @Inject
    JournalBackups backups;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    /** Office's data folder before 1.5.0, whose journal is copied on the first start of Floor42 (DataMigration). */
    @ConfigProperty(name = "floor42.legacy-data-dir")
    Optional<Path> legacyDataDir;

    @Inject
    ConfigGuard configGuard;

    /** Appends share it; the restore at shutdown takes it alone, then the journal takes no more appends. */
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();

    private volatile String closed;

    /** Since when another process holds the journal, and what SQLite said; empty since an append succeeded. */
    public record Locked(Instant since, String detail) {}

    private volatile Locked locked;

    @PostConstruct
    void migrate() {
        // Before anything opens the journal: a connection an agent configured is never opened.
        configGuard.verify();
        try {
            // SQLite creates the database file, not its directory.
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new JournalException("Cannot create data directory " + dataDir, e);
        }
        // A start never restores: a file asking for it is refused and reported (ADR-0042, amended).
        backups.atStart();
        // Before anything opens the journal: SQLite would create an empty one.
        DataMigration.run(dataDir, legacyDataDir.orElse(null));
        // Read once: a record an agent writes later changes no guarded folder (ADR-0044, amended).
        DataFolders.freeze(dataDir);
        // A copy before migration and replay: what a failed start leaves to restore.
        backups.atStartup();
        flyway.migrate();
        refuseExtensionLoading();
        checkContinuity();
    }

    /** Bound to the journal file this daemon opened; null until {@link #checkContinuity()}, and when it failed. */
    private volatile ContinuityKeeper continuity;

    /** A file's identity: tests stand in for the inode Windows does not give. */
    Function<Path, Object> fileIdentity = ContinuityKeeper::fileKey;

    long markEveryMillis = 30_000;

    /**
     * Compares the journal with what the last run saw ({@link JournalContinuity}, {@link ContinuityKeeper}). A journal
     * that went back in time is reported, never refused: the check reads a file of the data folder, which the same OS
     * user can rewrite too, so refusing writes would prove nothing against a forger. The director sees a red banner
     * and decides; the record is then kept as it was until they acknowledge it.
     */
    void checkContinuity() {
        if (dataDir == null) return;
        ContinuityKeeper previous = continuity;
        if (previous != null) previous.close();
        continuity = null;
        anchorFailure = null;
        try {
            ContinuityKeeper k = new ContinuityKeeper(dataDir, fileIdentity, markEveryMillis, epochUnchecked());
            continuity = k;
            k.atStart();
            if (k.broken().isEmpty()) markEpochChecked();
        } catch (SQLException | IOException e) {
            // Handled: logged; the banner says the check could not be made (ANCHOR_FAILED)
            LOG.errorf("Cannot check the journal's continuity: %s", e);
            anchorFailure = new JournalContinuity.Break(JournalContinuity.ANCHOR_FAILED, null, -1, -1);
        }
    }

    /** Set when no check could be made this run: the banner says so, a silent detection would look like all is well. */
    private volatile JournalContinuity.Break anchorFailure;

    /**
     * True while no check ended without a break since an older Floor42 opened this journal (table {@code
     * continuity_epoch}, V2). A table that is missing or unreadable answers false: the red alarm, the safe side.
     */
    private boolean epochUnchecked() {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT checked FROM continuity_epoch WHERE id = 1");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getInt(1) == 0;
        } catch (SQLException e) {
            // Handled: logged; answers false, so the mark reads MISSING: the red alarm, the safe side
            LOG.warnf("continuity_epoch cannot be read (%s): a missing record would be shown as an alarm", e);
            return false;
        }
    }

    private void markEpochChecked() {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("UPDATE continuity_epoch SET checked = 1 WHERE id = 1")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            // Handled: logged; the next start may show the first-check notice again
            LOG.warnf("continuity_epoch not updated (%s): the next start may show the first-check notice again", e);
        }
    }

    /** The journal went back in time since the last run, or nothing says what it saw, until the director has seen it. */
    public Optional<JournalContinuity.Break> continuityBreak() {
        ContinuityKeeper k = continuity;
        Optional<JournalContinuity.Break> b = k == null ? Optional.empty() : k.broken();
        return b.isPresent() ? b : Optional.ofNullable(anchorFailure);
    }

    /** The director has seen the break: the record follows the journal again. */
    public void acknowledgeContinuity() {
        ContinuityKeeper k = continuity;
        boolean failed = anchorFailure != null;
        anchorFailure = null;
        // A check that could not be made leaves nothing to acknowledge: the banner goes, no record is written.
        if (failed) return;
        if (k == null) throw new JournalException("This run does not check the journal's continuity", null);
        try {
            k.acknowledge();
            markEpochChecked();
        } catch (SQLException | IOException e) {
            throw new JournalException("Cannot record the journal's continuity", e);
        }
    }

    /**
     * SQLite's {@code load_extension()} runs native code from a file: an open journal connection must refuse it. Asked
     * of the connection itself, so it holds whatever configured the pool.
     */
    void refuseExtensionLoading() {
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

    /**
     * The restore the director prepared (in memory, never from a file) is applied here, during the daemon's own orderly
     * stop, after every other stop observer: the journal then takes no more appends, so nothing written during the
     * stop lands in the restored journal. A crash or {@code kill -9} forgets the restore.
     */
    void restoreAtShutdown(@Observes @Priority(Integer.MAX_VALUE) ShutdownEvent ignored) {
        Optional<JournalBackups.Pending> pending = backups.takePendingRestore();
        gate.writeLock().lock();
        try {
            closed = pending.map(p -> "Floor42 is stopping to restore the journal from " + p.name())
                    .orElse("Floor42 is stopping");
            stopJournal(pending);
        } finally {
            gate.writeLock().unlock();
        }
    }

    /**
     * When another file was moved over the journal's name while this daemon ran, the journal it opened is kept as
     * {@code displaced-*} and a prepared restore is not applied (it would rewrite the other file). Linux only: Windows
     * does not let a file SQLite holds be replaced.
     */
    private void stopJournal(Optional<JournalBackups.Pending> pending) {
        ContinuityKeeper k = continuity;
        try {
            String displaced = k == null ? null : k.keepDisplaced(backups.store());
            boolean restored = false;
            if (pending.isPresent()) restored = restore(k, pending.get());
            if (k != null) k.atStop(displaced, restored);
        } catch (SQLException | IOException e) {
            // Handled: logged; the journal is left as it was
            LOG.errorf("The journal's stop did not complete: %s", e);
        } finally {
            if (k != null) k.close();
        }
    }

    /** Applies the prepared restore; whatever stops it, the outcome is written so the next start reports it. */
    private boolean restore(ContinuityKeeper k, JournalBackups.Pending pending) {
        if (k != null && k.replaced()) {
            backups.notApplied(pending, "the journal file was replaced while Floor42 ran");
            return false;
        }
        Connection c;
        try {
            c = dataSource.getConnection();
        } catch (SQLException e) {
            // Handled: the restore is recorded as not applied, with the reason
            backups.notApplied(pending, "the journal cannot be opened: " + e.getMessage());
            return false;
        }
        boolean restored = backups.applyLive(c, pending).restored();
        try {
            c.close();
        } catch (SQLException e) {
            // Handled: The restore itself is done and recorded; only the pooled connection did not close cleanly.
            LOG.warnf("The connection used by the restore did not close: %s", e);
        }
        return restored;
    }

    /** Another process holds the journal: since when, and what SQLite said. */
    public Optional<Locked> locked() {
        return Optional.ofNullable(locked);
    }

    private static boolean busy(SQLException e) {
        int code = e.getErrorCode();
        return code == 5 || code == 6 || String.valueOf(e.getMessage()).contains("SQLITE_BUSY");
    }

    /** Floor42's own actor was called "office" before 1.5.0: read as "floor42", forever. */
    static Actor actor(String kind, String id) {
        Actor.Kind k = Actor.Kind.valueOf(kind);
        if (k == Actor.Kind.SYSTEM && LEGACY_SYSTEM_ACTOR.equals(id)) return Actor.system();
        return new Actor(k, id);
    }

    private static final String LEGACY_SYSTEM_ACTOR = "office";

    @Override
    public void append(List<EventEnvelope> events) {
        if (events.isEmpty()) return;
        gate.readLock().lock();
        try {
            if (closed != null) throw new JournalException("The journal takes no more events: " + closed, null);
            write(events);
            locked = null;
            ContinuityKeeper k = continuity;
            if (k != null) k.markSoon();
        } finally {
            gate.readLock().unlock();
        }
    }

    private void write(List<EventEnvelope> events) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(INSERT)) {
                for (EventEnvelope e : events) {
                    JournalRows.bind(ps, e, codec);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException | RuntimeException ex) {
                c.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            if (busy(ex)) throw locked(ex);
            throw new JournalException("Cannot append " + events.size() + " events", ex);
        }
    }

    /** Another program holds the journal: remembered for the status, said plainly, nothing recorded. */
    private JournalLockedException locked(SQLException ex) {
        locked = new Locked(Instant.now(), ex.getMessage());
        String message = "The journal " + (dataDir == null ? DataFolders.JOURNAL : dataDir.resolve(DataFolders.JOURNAL))
                + " is locked by another process (a sqlite3 shell, a database browser, a backup tool):"
                + " nothing was recorded. Close it; Floor42 does not stop other programs from opening its"
                + " journal (docs/backups.md)";
        LOG.error(message);
        return new JournalLockedException(message, ex);
    }

    @Override
    public List<EventEnvelope> readAll() {
        return replay().entries().stream().map(Entry::envelope).toList();
    }

    @Override
    public Read replay() {
        List<Unreadable> unreadable = new ArrayList<>();
        List<Entry> entries = query("SELECT " + COLUMNS + " FROM event_journal ORDER BY sequence", -1, unreadable);
        return new Read(entries, List.copyOf(unreadable));
    }

    @Override
    public List<Entry> latest(int limit) {
        List<Entry> newestFirst =
                query("SELECT " + COLUMNS + " FROM event_journal ORDER BY sequence DESC LIMIT ?", limit, null);
        List<Entry> result = new ArrayList<>(newestFirst);
        Collections.reverse(result);
        return result;
    }

    @Override
    public List<Entry> since(long sequence, int limit) {
        // The sequence is the table's primary key: SQLite seeks to it rather than scanning the journal.
        return query(
                "SELECT " + COLUMNS + " FROM event_journal WHERE sequence > ? ORDER BY sequence LIMIT ?",
                limit,
                null,
                sequence);
    }

    /** @param unreadable receives the rows that cannot be read; null leaves them out silently (replay lists them) */
    private List<Entry> query(String sql, int limit, List<Unreadable> unreadable, long... before) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (long b : before) ps.setLong(i++, b);
            if (limit >= 0) ps.setInt(i, limit);
            List<Entry> entries = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long sequence = rs.getLong("sequence");
                    try {
                        entries.add(new Entry(sequence, JournalRows.envelope(rs, codec)));
                    } catch (UnreadableEventException e) {
                        // Handled: a row this version cannot read is left out, listed when the caller asks for the list
                        if (unreadable == null) continue;
                        int format = rs.getInt("format_version");
                        // A row in a later format is a newer version's, whatever failed in reading it.
                        unreadable.add(
                                format > FORMAT_VERSION
                                        ? JournalRows.unreadable(
                                                rs,
                                                sequence,
                                                Unreadable.Reason.NEWER_VERSION,
                                                "format version " + format + ": " + e.detail())
                                        : JournalRows.unreadable(rs, sequence, e.reason(), e.detail()));
                    }
                }
            }
            return entries;
        } catch (SQLException ex) {
            throw new JournalException("Cannot read the journal", ex);
        }
    }
}
