package dev.floor42.server.journal;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;
import org.jboss.logging.Logger;
import org.sqlite.SQLiteConnection;

/**
 * A restore of the journal from one of its copies (ADR-0042, amended by ADR-0044). The director prepares it through the
 * API; the daemon keeps it <b>in memory only</b> and applies it during its own orderly shutdown, after everything else
 * has stopped writing. Nothing on disk asks for a restore: a process of the same OS user (an agent's shell) could write
 * any file the daemon reads at its next start, so a start never restores anything. A file asking for one ({@link
 * #MARKER}, which 1.7.0 read) is refused, kept aside and reported.
 *
 * <p>The copy is read once into memory, and those very bytes are hashed, checked and written: no file stands between
 * the check and the restore. The journal is rewritten in place from them through SQLite's online backup API, under
 * SQLite's own lock: the pool's connections see the restored pages, no file is swapped under them. The journal being
 * replaced is copied first ({@code before-restore-*}, never rotated); when that copy fails, nothing is restored.
 */
public final class PendingRestore {

    private static final Logger LOG = Logger.getLogger(PendingRestore.class);

    /** Floor42 1.7.0 restored the copy this file named at its next start. A start now refuses it. */
    public static final String MARKER = "restore-pending.properties";

    /** Where a refused marker is kept, for the director to look at. */
    public static final String REFUSED = "restore-pending.refused.properties";

    /**
     * What the last orderly stop did with the restore the director prepared: written by that stop, read once by the
     * next start, which only reports it (checked against the journal and the copies), never acts on it.
     */
    public static final String OUTCOME = "restore-outcome.properties";

    /**
     * What a stop did with a prepared restore. The next start learns it from {@link #OUTCOME}, a file a process of the
     * same OS user could have written: it is shown as reported by a file, never as proven.
     *
     * @param matchesJournal for a restore reported done: the journal holds the events it names and the kept copy
     *     exists (consistent, not proof: a forger can match both); null for one reported not applied, which has
     *     nothing to check against
     */
    public record Outcome(
            boolean restored,
            String backup,
            long events,
            String keptAs,
            String reason,
            Instant at,
            Boolean matchesJournal) {

        Outcome checked(Boolean ok) {
            return new Outcome(restored, backup, events, keptAs, reason, at, ok);
        }
    }

    /** Text read from a file is shown, so it is bounded and stripped of control characters. */
    static String bounded(String s) {
        if (s == null) return null;
        String clean = s.replaceAll("\\p{Cntrl}", " ").strip();
        return clean.length() > 200 ? clean.substring(0, 200) + "…" : clean;
    }

    /** A file asking for a restore, found at start and refused. */
    public record Refused(String backup, Instant modifiedAt, String keptAs) {}

    private PendingRestore() {}

    /**
     * At start: a marker in the data folder is never applied. It is moved aside ({@link #REFUSED}) and reported, so the
     * director sees that something asked for a rollback of the journal.
     */
    public static Optional<Refused> refuseMarker(Path dataDir) {
        Path marker = dataDir.resolve(MARKER);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        String backup = "";
        Instant modified = null;
        try {
            modified =
                    Files.getLastModifiedTime(marker, LinkOption.NOFOLLOW_LINKS).toInstant();
            if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) && Files.size(marker) < 4096) {
                backup = load(marker).getProperty("backup", "");
            }
        } catch (IOException | IllegalArgumentException unreadable) {
            // Handled: Reported without a name.
        }
        String keptAs = moveAside(dataDir, marker);
        LOG.errorf(
                "Refused %s in the data folder, asking to restore the journal from \"%s\": a start never restores"
                        + " the journal. Only the director prepares a restore, in Settings; it is applied when this"
                        + " daemon stops. Kept as %s. A process of the daemon's OS user wrote it: an agent, or a"
                        + " Floor42 before 1.8.0 (prepare the restore again)",
                MARKER, backup, keptAs == null ? "nothing (could not move it)" : keptAs);
        return Optional.of(new Refused(backup, modified, keptAs));
    }

    /** The marker moved aside as {@link #REFUSED}; null when it could only be deleted. */
    private static String moveAside(Path dataDir, Path marker) {
        try {
            Files.move(marker, dataDir.resolve(REFUSED), StandardCopyOption.REPLACE_EXISTING);
            return REFUSED;
        } catch (IOException e) {
            // Handled: the marker cannot be moved aside: deleted below, so a planted marker is never applied
            deleteQuietly(marker);
            return null;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Handled: refused or read anyway: nothing reads it again
        }
    }

    /**
     * Restores the journal from this copy, in place, through a connection of the live journal. Must run when nothing
     * else writes (the daemon's own shutdown, after its other work stopped). A copy that is missing or damaged, or a
     * journal that cannot be copied first, is not restored: the outcome says why.
     */
    public static Outcome applyLive(
            Connection c,
            Path journal,
            BackupStore store,
            String name,
            String preparedSha256,
            long memoryBudget,
            Instant now) {
        Progress progress = new Progress();
        try {
            return restore(c, journal, store, name, preparedSha256, memoryBudget, now, progress);
        } catch (IOException | SQLException | RuntimeException e) {
            // Handled: the restore is not applied: the outcome says why and the journal is left as it was
            return notApplied(name, String.valueOf(e.getMessage()), progress, now, e);
        } catch (OutOfMemoryError e) {
            // Handled: Narrow on purpose: the copy was read into memory and the heap was too small for it. The stop is
            // the last
            // writer: the outcome tells the next start, and whether the journal may have been touched.
            String cause = "not enough memory to restore: do it by hand, daemon stopped (docs/backups.md)";
            return notApplied(name, cause, progress, now, e);
        }
    }

    /** How far a restore got: once SQLite's backup started writing, the journal may be partly restored. */
    private static final class Progress {
        boolean writing;
        /** SQLite's backup answered success: the journal was replaced whole, only the check after it is missing. */
        boolean backedUp;

        String keptAs;
    }

    private static Outcome restore(
            Connection c,
            Path journal,
            BackupStore store,
            String name,
            String preparedSha256,
            long memoryBudget,
            Instant now,
            Progress progress)
            throws IOException, SQLException {
        byte[] bytes = readPrepared(store, name, preparedSha256, memoryBudget);
        try (Connection held = BackupStore.inMemory(bytes)) {
            long events = BackupStore.check(held, name);
            progress.keptAs =
                    store.takeThrough(c, BackupStore.Kind.BEFORE_RESTORE).name();
            progress.writing = true;
            int rc = held.unwrap(SQLiteConnection.class).getDatabase().backup("main", journal.toString(), null);
            if (rc != 0) {
                // The backup API writes the destination in one transaction: an error answer wrote nothing.
                progress.writing = false;
                throw new SQLException("not restored, the journal is unchanged: SQLite's backup answered " + rc);
            }
            progress.backedUp = true;
            long holds = count(c);
            if (holds != events) throw new SQLException("the journal holds " + holds + " events, not " + events);
            LOG.warnf(
                    "Journal restored from the backup %s (%d events). The journal it replaced is kept as %s.",
                    name, events, progress.keptAs);
            return new Outcome(true, name, events, progress.keptAs, null, now, Boolean.TRUE);
        }
    }

    /** The outcome of a restore that did not complete; the first words survive the 200 characters shown. */
    private static Outcome notApplied(String name, String cause, Progress progress, Instant now, Throwable e) {
        String reason = progress.backedUp
                ? "restored, but the restored journal could not be checked (the one it replaced: " + progress.keptAs
                        + "): " + cause
                : progress.writing
                        ? "the journal may be partly restored, check it (the one it replaced: " + progress.keptAs
                                + "): " + cause
                        : cause;
        LOG.errorf(e, "The prepared restore of %s was not applied: %s", name, reason);
        return new Outcome(false, name, -1, progress.keptAs, reason, now, null);
    }

    /**
     * What a restore from memory may take of the heap: the bytes of the copy, then SQLite's in-memory database
     * deserialised from them, about twice the copy, and the rest of the daemon needs room too. So a quarter of the
     * heap.
     */
    static long defaultBudget(long maxMemory) {
        return maxMemory / 4;
    }

    /** Why a copy of this size is not restored from memory with this budget; null when it is. */
    static String tooLarge(String name, long size, long memoryBudget) {
        long limit = Math.min(MAX_RESTORE_BYTES, memoryBudget);
        if (size <= limit) return null;
        return "the copy " + name + " is " + size + " bytes, over the " + limit
                + " this daemon can restore from memory:" + " restore it by hand, daemon stopped (docs/backups.md)";
    }

    /** Above this, a copy is not restored from memory: restore it by hand, daemon stopped (docs/backups.md). */
    static final long MAX_RESTORE_BYTES = 1L << 30;

    /**
     * The copy, read once into memory: those bytes are hashed, checked and restored, so no file stands between the
     * check and the restore for a process of the same OS user to change (ADR-0044, amendment 3). A copy changed since
     * the director chose it is refused.
     */
    static byte[] readPrepared(BackupStore store, String name, String preparedSha256, long memoryBudget)
            throws IOException {
        Path backup =
                store.find(name).orElseThrow(() -> new IOException("no backup named " + name + " in " + store.dir()));
        String refusal = tooLarge(name, Files.size(backup), memoryBudget);
        if (refusal != null) throw new IOException(refusal);
        byte[] bytes = Files.readAllBytes(backup);
        if (preparedSha256 != null && !preparedSha256.equals(BackupStore.sha256(bytes))) {
            throw new IOException("the copy " + name + " changed since it was prepared: refused");
        }
        return bytes;
    }

    private static long count(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM event_journal");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    /** Written by the stop that applied (or failed) a restore, for the next start to report. */
    public static void writeOutcome(Path dataDir, Outcome o) throws IOException {
        Properties p = new Properties();
        p.setProperty("restored", Boolean.toString(o.restored()));
        p.setProperty("backup", o.backup());
        p.setProperty("events", Long.toString(o.events()));
        if (o.keptAs() != null) p.setProperty("kept-as", o.keptAs());
        if (o.reason() != null) p.setProperty("reason", o.reason());
        p.setProperty("at", o.at().toString());
        Path partial = dataDir.resolve(OUTCOME + ".partial");
        try (Writer w = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
            p.store(w, "What Floor42's last stop did with the restore the director prepared (ADR-0042)");
        }
        Files.move(partial, dataDir.resolve(OUTCOME), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * At start: what the last stop reported, read once and removed. It is information only, shown as reported by a
     * file: a process of the daemon's OS user can write one. A restore reported done is compared with the journal and
     * the copies ({@code matchesJournal}); one reported not applied has nothing to compare with. Its text is bounded.
     */
    public static Optional<Outcome> takeOutcome(Path dataDir, Path journal, BackupStore store) {
        Path file = dataDir.resolve(OUTCOME);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            Outcome o = outcomeOf(load(file));
            Boolean ok = !o.restored()
                    ? null
                    : BackupStore.events(journal) == o.events()
                            && o.keptAs() != null
                            && store.find(o.keptAs()).isPresent();
            if (Boolean.FALSE.equals(ok)) LOG.errorf("%s does not match the journal or the copies", OUTCOME);
            LOG.infof("%s reports what the last stop did with a restore (a file, not a proof)", OUTCOME);
            return Optional.of(o.checked(ok));
        } catch (IOException | RuntimeException e) {
            // Handled: logged; an unreadable outcome is not shown
            LOG.errorf("%s is unreadable (%s): ignored", OUTCOME, e.toString());
            return Optional.empty();
        } finally {
            deleteQuietly(file);
        }
    }

    private static Outcome outcomeOf(Properties p) {
        return new Outcome(
                Boolean.parseBoolean(p.getProperty("restored")),
                bounded(p.getProperty("backup", "")),
                Long.parseLong(p.getProperty("events", "-1")),
                bounded(p.getProperty("kept-as")),
                bounded(p.getProperty("reason")),
                Instant.parse(p.getProperty("at")),
                null);
    }

    private static Properties load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }
}
