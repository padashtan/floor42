package dev.floor42.server.journal;

import dev.floor42.server.config.DataFolders;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Properties;
import org.jboss.logging.Logger;

/**
 * Office became Floor42 in 1.5.0: its journal moves from {@code office.db} to {@code floor42.db}, and the default data
 * folder from {@code ~/.office} to {@code ~/.floor42}. On a start where the new journal does not exist yet, this copies
 * the old one (never moves it): {@code office.db} of the data folder itself when there is one (a data folder set by
 * hand, or Docker's), else the one of the former folder. The copy is SQLite's own consistent snapshot ({@code VACUUM
 * INTO}) of a read-only connection, checked event for event count before it becomes the journal. The former folder is
 * not modified: it stays as the backup, with its worktrees, which git knows by their absolute path ({@link
 * DataFolders}).
 */
public final class DataMigration {

    private static final Logger LOG = Logger.getLogger(DataMigration.class);
    /** The driver itself: DriverManager does not always see it from Quarkus' class loader. */
    private static final org.sqlite.JDBC SQLITE = new org.sqlite.JDBC();

    /** What a start did: nothing, or the copy it made. */
    public record Outcome(Path from, Path to, long events) {}

    private DataMigration() {}

    /**
     * @param dataDir Floor42's data folder (exists)
     * @param legacyDir Office's former data folder, or null
     * @return the copy made, or empty when there was nothing to copy
     */
    public static Optional<Outcome> run(Path dataDir, Path legacyDir) {
        Path target = dataDir.resolve(DataFolders.JOURNAL);
        if (present(target)) return Optional.empty();
        Path source = dataDir.resolve(DataFolders.LEGACY_JOURNAL);
        Path from = dataDir;
        if (!present(source)) {
            if (legacyDir == null) return Optional.empty();
            Path legacy = legacyDir.toAbsolutePath().normalize();
            if (legacy.equals(dataDir.toAbsolutePath().normalize())) return Optional.empty();
            source = legacy.resolve(DataFolders.LEGACY_JOURNAL);
            from = legacy;
            if (!present(source)) return Optional.empty();
        }
        try {
            Outcome done = copy(source, target, from, dataDir);
            LOG.infof(
                    "Office is now Floor42: its journal %s (%d events) was copied to %s. %s is left as it was, as a"
                            + " backup%s.",
                    source,
                    done.events(),
                    target,
                    from,
                    from.equals(dataDir)
                            ? ""
                            : "; the worktrees of earlier missions stay in it, new ones are made in " + dataDir);
            return Optional.of(done);
        } catch (SQLException | IOException e) {
            throw new JournalException("Cannot copy the journal " + source + " to " + target, e);
        }
    }

    private static Outcome copy(Path source, Path target, Path from, Path dataDir) throws SQLException, IOException {
        Path partial = target.resolveSibling(target.getFileName() + ".migrating");
        Files.deleteIfExists(partial);
        long events;
        Properties readOnly = new Properties();
        readOnly.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY
        try (Connection c = SQLITE.connect("jdbc:sqlite:" + source, readOnly)) {
            events = count(c);
            try (PreparedStatement ps = c.prepareStatement("VACUUM INTO ?")) {
                ps.setString(1, partial.toString());
                ps.execute();
            }
        }
        try (Connection c = SQLITE.connect("jdbc:sqlite:" + partial, new Properties())) {
            long copied = count(c);
            if (copied != events) {
                throw new IOException("the copy holds " + copied + " events, the journal " + events);
            }
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        DataFolders.recordMigration(dataDir, from, source.getFileName().toString(), events);
        return new Outcome(source, target, events);
    }

    private static long count(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM event_journal");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** A file SQLite opened without writing anything is empty: that is no journal. */
    private static boolean present(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) > 0;
        } catch (IOException e) {
            // Handled: a file that cannot be read is not a journal to migrate
            return false;
        }
    }
}
