package dev.floor42.server.journal;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;

/**
 * Detects a journal that went back in time between two runs (ADR-0044, amended). The journal is append-only: a start
 * must find at least the events the last run saw, and the last of them unchanged. The daemon writes what it saw
 * ({@link #FILE}: event count, last sequence, a hash of the last row) at start, after appends (at most every 30 s) and
 * at its stop; the next start compares.
 *
 * <p>This is detection, not prevention: the record sits in the data folder, which a process of the daemon's OS user
 * can rewrite together with the journal (D16). It catches the plain rollback (an older copy moved over {@code
 * floor42.db}, then a restart), which left no trace before 1.8.0.
 */
public final class JournalContinuity {

    /** In the data folder. */
    public static final String FILE = "journal-continuity.properties";

    /**
     * A {@link Break} reason: no record beside a journal an older Floor42 last opened and no check has ended since
     * (ADR-0044, amendment 3). Shown as a notice, not an alarm; a presentation hint, never a proof.
     */
    public static final String FIRST_CHECK = "FIRST_CHECK";

    /**
     * A {@link Break} reason: the daemon could not open the read-only connection that measures the journal, so no
     * check was made this run.
     */
    public static final String ANCHOR_FAILED = "ANCHOR_FAILED";

    /**
     * What one run saw of the journal.
     *
     * @param lastRowHash SHA-256 of the last row's id, time, type and payload; empty for an empty journal
     * @param displaced a copy of the journal the stop kept because the file was replaced under it, null when none
     */
    public record Mark(long events, long lastSequence, String lastRowHash, Instant at, String displaced) {}

    /**
     * The journal does not continue the one the last run saw, or nothing says what that run saw.
     *
     * @param reason SHORTER (fewer events), DIVERGED (the last row seen is missing or different), MISSING (no record,
     *     beside a journal that holds events), FIRST_CHECK (the same, on the first check since an older Floor42),
     *     UNREADABLE (a record that cannot be read) or ANCHOR_FAILED (no check could be made this run)
     * @param recorded what the last run saw; null for the last four
     */
    public record Break(String reason, Mark recorded, long foundEvents, long foundLastSequence) {}

    private JournalContinuity() {}

    public static Mark measure(Connection c, Instant now) throws SQLException {
        long events;
        long last;
        try (PreparedStatement ps =
                        c.prepareStatement("SELECT count(*), coalesce(max(sequence), 0) FROM event_journal");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            events = rs.getLong(1);
            last = rs.getLong(2);
        }
        return new Mark(events, last, rowHash(c, last).orElse(""), now, null);
    }

    /** The hash of the row at this sequence; empty when there is none. */
    static Optional<String> rowHash(Connection c, long sequence) throws SQLException {
        if (sequence <= 0) return Optional.empty();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT event_id, occurred_at, event_type, payload FROM event_journal WHERE sequence = ?")) {
            ps.setLong(1, sequence);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                for (int i = 1; i <= 4; i++) {
                    String v = rs.getString(i);
                    md.update((v == null ? "\u0000" : v).getBytes(StandardCharsets.UTF_8));
                    md.update((byte) 0x1f);
                }
                return Optional.of(HexFormat.of().formatHex(md.digest()));
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compares the journal this connection reads with what the last run recorded. */
    public static Optional<Break> check(Mark recorded, Connection c) throws SQLException {
        Mark now = measure(c, Instant.now());
        if (now.events() < recorded.events()) {
            return Optional.of(new Break("SHORTER", recorded, now.events(), now.lastSequence()));
        }
        if (recorded.lastSequence() > 0) {
            Optional<String> seen = rowHash(c, recorded.lastSequence());
            if (seen.isEmpty() || !seen.get().equals(recorded.lastRowHash())) {
                return Optional.of(new Break("DIVERGED", recorded, now.events(), now.lastSequence()));
            }
        }
        return Optional.empty();
    }

    /**
     * What the last run recorded; empty when there is no record at all.
     *
     * @throws IOException when a record is there but cannot be read (not a plain file, malformed, a value missing):
     *     never taken for a missing one, which a fresh data folder has too
     */
    public static Optional<Mark> read(Path dataDir) throws IOException {
        Path f = dataDir.resolve(FILE);
        if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) throw new IOException(FILE + " is not a plain file");
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            p.load(r);
            return Optional.of(new Mark(
                    Long.parseLong(p.getProperty("events")),
                    Long.parseLong(p.getProperty("last-sequence")),
                    p.getProperty("last-row-sha256", ""),
                    Instant.parse(p.getProperty("at")),
                    p.getProperty("displaced")));
        } catch (RuntimeException e) {
            throw new IOException(FILE + " is malformed: " + e.getClass().getSimpleName(), e);
        }
    }

    public static void write(Path dataDir, Mark m) throws IOException {
        Properties p = new Properties();
        p.setProperty("events", Long.toString(m.events()));
        p.setProperty("last-sequence", Long.toString(m.lastSequence()));
        p.setProperty("last-row-sha256", m.lastRowHash());
        p.setProperty("at", m.at().toString());
        if (m.displaced() != null) p.setProperty("displaced", m.displaced());
        Path partial = dataDir.resolve(FILE + ".partial");
        try (Writer w = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
            p.store(w, "What Floor42 last saw of its journal: the next start checks the journal continues it");
        }
        Files.move(partial, dataDir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING);
    }
}
