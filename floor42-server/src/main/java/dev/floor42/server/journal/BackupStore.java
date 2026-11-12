package dev.floor42.server.journal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Copies of the journal in one folder (ADR-0042). Each copy is SQLite's own consistent snapshot ({@code VACUUM INTO})
 * taken through a read-only connection, so writers carry on and the copy holds whole commands only; it is written under
 * a temporary name, checked ({@code PRAGMA quick_check}, events counted) and only then given its name. Each kind keeps
 * its last copies, the older ones are deleted; a copy made before a restore is never deleted.
 */
public final class BackupStore {

    /** The driver itself: DriverManager does not always see it from Quarkus' class loader. */
    private static final org.sqlite.JDBC SQLITE = new org.sqlite.JDBC();

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);
    /** Every name this store writes: a kind, a time stamp or a day, an optional counter. Nothing else is listed. */
    private static final Pattern NAME =
            Pattern.compile("(startup|daily|manual|before-restore|displaced)-([0-9TZ-]{10,19})(-[0-9]{1,3})?\\.db");

    public enum Kind {
        STARTUP("startup"),
        DAILY("daily"),
        MANUAL("manual"),
        BEFORE_RESTORE("before-restore"),
        /** The journal file was replaced under the running daemon: what its open connections still held, kept at its stop. */
        DISPLACED("displaced");

        final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        static Kind of(String prefix) {
            for (Kind k : values()) if (k.prefix.equals(prefix)) return k;
            throw new IllegalArgumentException(prefix);
        }
    }

    /**
     * @param events the events the copy holds, -1 when it cannot be read
     */
    public record Backup(String name, Kind kind, long sizeBytes, Instant createdAt, long events) {}

    private final Path dir;
    private final Clock clock;
    private final Map<Kind, Integer> keep;

    /** @param keep how many copies of each kind are kept; a kind absent is never rotated */
    public BackupStore(Path dir, Clock clock, Map<Kind, Integer> keep) {
        this.dir = dir;
        this.clock = clock;
        this.keep = Map.copyOf(keep);
    }

    public Path dir() {
        return dir;
    }

    /** Copies this journal now, then deletes the copies of that kind beyond what is kept. */
    public Backup take(Path journal, Kind kind) throws IOException, SQLException {
        return take(journal, null, kind);
    }

    /**
     * Copies what this connection reads: an open connection of the live journal still reads the file it opened, even
     * when another file was moved over the journal's name since. The connection is left open.
     */
    public Backup takeThrough(Connection live, Kind kind) throws IOException, SQLException {
        return take(null, live, kind);
    }

    /** SHA-256 of a file, hex. */
    public static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Backup take(Path journal, Connection live, Kind kind) throws IOException, SQLException {
        Files.createDirectories(dir);
        Path target = freeTarget(kind);
        Path partial = dir.resolve(target.getFileName() + ".partial");
        Files.deleteIfExists(partial);
        try {
            vacuumIntoWhenFree(journal, live, partial);
            long events = check(partial);
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            rotate(kind);
            return new Backup(
                    target.getFileName().toString(),
                    kind,
                    Files.size(target),
                    Files.getLastModifiedTime(target).toInstant(),
                    events);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /** The first name of this kind that no copy bears yet. */
    private Path freeTarget(Kind kind) {
        Instant at = clock.instant();
        Path target;
        if (kind == Kind.DAILY) {
            String base = kind.prefix + "-" + DAY.format(at);
            target = dir.resolve(base + ".db");
            for (int n = 2; Files.exists(target); n++) target = dir.resolve(base + "-" + n + ".db");
        } else {
            // Names sort as time does: two copies within one millisecond take the next free one.
            target = dir.resolve(kind.prefix + "-" + STAMP.format(at) + ".db");
            while (Files.exists(target)) {
                at = at.plusMillis(1);
                target = dir.resolve(kind.prefix + "-" + STAMP.format(at) + ".db");
            }
        }
        return target;
    }

    /**
     * A journal written without a pause can keep SQLite's write lock past a reader's busy timeout (SQLITE_BUSY from
     * VACUUM INTO): the copy is tried again, a few times, before the failure is reported.
     */
    private void vacuumIntoWhenFree(Path journal, Connection live, Path partial) throws SQLException, IOException {
        for (int attempt = 1; ; attempt++) {
            attempts = attempt;
            try {
                vacuumInto(journal, live, partial);
                return;
            } catch (SQLException e) {
                boolean busy = e instanceof org.sqlite.SQLiteException s
                        && s.getResultCode() == org.sqlite.SQLiteErrorCode.SQLITE_BUSY;
                if (!busy || attempt == BUSY_ATTEMPTS) throw e;
                Files.deleteIfExists(partial);
                try {
                    Thread.sleep(retryPauseMillis * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("the copy of the journal was interrupted");
                }
            }
        }
    }

    static final int BUSY_ATTEMPTS = 5;

    /** For tests: how long a copy waits for the write lock, and the pause (times the attempt) between two attempts. */
    int busyTimeoutMillis = 3000;

    long retryPauseMillis = 100;

    /** For tests: the attempts the last copy needed. */
    volatile int attempts;

    /** From the connection given, else from a read-only one on the journal file. */
    private void vacuumInto(Path journal, Connection live, Path partial) throws SQLException {
        if (live != null) {
            vacuumInto(live, partial);
            return;
        }
        try (Connection c = readOnly(journal, busyTimeoutMillis)) {
            vacuumInto(c, partial);
        }
    }

    private static void vacuumInto(Connection c, Path partial) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("VACUUM INTO ?")) {
            ps.setString(1, partial.toString());
            ps.execute();
        }
    }

    /** Today's daily copy (UTC), unless one exists already. */
    public Optional<Backup> takeDailyIfDue(Path journal) throws IOException, SQLException {
        String today = Kind.DAILY.prefix + "-" + DAY.format(clock.instant());
        if (list().stream().anyMatch(b -> b.name().startsWith(today))) return Optional.empty();
        return Optional.of(take(journal, Kind.DAILY));
    }

    /** The copies, newest first. */
    public List<Backup> list() {
        if (!Files.isDirectory(dir)) return List.of();
        List<Backup> backups = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                Matcher m = NAME.matcher(f.getFileName().toString());
                if (!m.matches() || !Files.isRegularFile(f)) continue;
                try {
                    backups.add(new Backup(
                            f.getFileName().toString(),
                            Kind.of(m.group(1)),
                            Files.size(f),
                            Files.getLastModifiedTime(f).toInstant(),
                            events(f)));
                } catch (IOException e) {
                    // Handled: Deleted while listed: not a copy any more.
                }
            }
        } catch (IOException e) {
            // Handled: logged; no copy is listed rather than a partial list
            org.jboss.logging.Logger.getLogger(BackupStore.class).warnf("Cannot list the copies in %s: %s", dir, e);
            return List.of();
        }
        backups.sort(Comparator.comparing(Backup::createdAt)
                .thenComparing(Backup::name)
                .reversed());
        return backups;
    }

    /** A copy by its listed name: never a path, so nothing outside the folder can be named. */
    public Optional<Path> find(String name) {
        if (name == null || !NAME.matcher(name).matches()) return Optional.empty();
        Path f = dir.resolve(name);
        return Files.isRegularFile(f) ? Optional.of(f) : Optional.empty();
    }

    void rotate(Kind kind) throws IOException {
        Integer kept = keep.get(kind);
        if (kept == null) return;
        List<Backup> ofKind = list().stream()
                .filter(b -> b.kind() == kind)
                .sorted(Comparator.comparing(Backup::name).reversed())
                .toList();
        for (Backup old : ofKind.subList(Math.min(Math.max(kept, 1), ofKind.size()), ofKind.size())) {
            Files.deleteIfExists(dir.resolve(old.name()));
        }
    }

    /** Events in a copy, -1 when it is not a readable journal. */
    public static long events(Path file) {
        try (Connection c = readOnly(file);
                PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM event_journal");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (SQLException e) {
            // Handled: a copy that cannot be read counts no events (-1), shown as unreadable
            return -1;
        }
    }

    /** Checks a copy SQLite wrote: whole, and a journal. Returns its events. */
    public static long check(Path file) throws SQLException, IOException {
        try (Connection c = readOnly(file)) {
            return check(c, file.getFileName().toString());
        }
    }

    /** Checks the journal this connection reads: whole, and a journal. Returns its events. */
    static long check(Connection c, String name) throws SQLException, IOException {
        try (PreparedStatement ps = c.prepareStatement("PRAGMA quick_check");
                ResultSet rs = ps.executeQuery()) {
            String result = rs.next() ? rs.getString(1) : "no answer";
            if (!"ok".equals(result)) throw new IOException("the copy " + name + " is damaged");
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM event_journal");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    static Connection readOnly(Path file) throws SQLException {
        return readOnly(file, 3000);
    }

    static Connection readOnly(Path file, int busyTimeoutMillis) throws SQLException {
        Properties p = new Properties();
        p.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY: a missing file is an error, never created
        // A live journal under write load can answer SQLITE_BUSY to a reader that does not wait (VACUUM INTO of a
        // copy).
        p.setProperty("busy_timeout", Integer.toString(busyTimeoutMillis));
        return SQLITE.connect("jdbc:sqlite:" + file, p);
    }

    /**
     * The read-only connection a running daemon holds on the journal it opened ({@link ContinuityKeeper}): it waits
     * for the pool's writers as they wait for each other.
     */
    static Connection anchor(Path journal) throws SQLException {
        Properties p = new Properties();
        p.setProperty("open_mode", "1");
        p.setProperty("busy_timeout", "3000");
        return SQLITE.connect("jdbc:sqlite:" + journal, p);
    }

    /** A journal held in memory only, from these bytes: what reads it reads exactly them, no file in between. */
    static Connection inMemory(byte[] bytes) throws SQLException {
        Connection c = SQLITE.connect("jdbc:sqlite::memory:", new Properties());
        try {
            c.unwrap(org.sqlite.SQLiteConnection.class).getDatabase().deserialize("main", bytes);
            return c;
        } catch (SQLException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    /** SHA-256 of these bytes, hex. */
    public static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
