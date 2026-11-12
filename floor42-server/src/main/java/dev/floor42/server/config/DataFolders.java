package dev.floor42.server.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Floor42's data folder and, once its journal was copied from Office's (1.5.0), the former folder that still holds the
 * worktrees of earlier missions. Git registers a worktree by its absolute path, so those worktrees are not moved: they
 * are found where they are, and new ones are made in the new folder. The former folder is left intact, as a backup.
 */
public final class DataFolders {

    /** The journal's file name in the data folder. */
    public static final String JOURNAL = "floor42.db";
    /** The journal's file name before 1.5.0. */
    public static final String LEGACY_JOURNAL = "office.db";
    /** Written in the data folder when the journal was copied from another folder: where from, when, how many events. */
    public static final String MIGRATION_RECORD = "migrated-from-office.properties";

    private DataFolders() {}

    /**
     * The former folder as read at start: the record lies in the data folder, which a process of the daemon's OS user
     * can write, and it decides which folders are guarded and where worktrees are looked for. Read once, at start
     * ({@link #freeze}); only the daemon's own migration changes it afterwards.
     */
    private static final Map<Path, Optional<Path>> FROZEN = new ConcurrentHashMap<>();

    private static Path key(Path dataDir) {
        return dataDir.toAbsolutePath().normalize();
    }

    /** Reads the migration record now, and never again for this data folder. */
    public static void freeze(Path dataDir) {
        if (dataDir != null) FROZEN.put(key(dataDir), read(dataDir));
    }

    /** The former data folder the journal was copied from, when it still exists. */
    public static Optional<Path> legacy(Path dataDir) {
        if (dataDir == null) return Optional.empty();
        Optional<Path> frozen = FROZEN.get(key(dataDir));
        return java.util.Objects.nonNull(frozen) ? frozen : read(dataDir);
    }

    private static Optional<Path> read(Path dataDir) {
        Path record = dataDir.resolve(MIGRATION_RECORD);
        if (!Files.isRegularFile(record)) return Optional.empty();
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(record, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            // Handled: logged; a record that cannot be read names no former folder, never a guessed one
            org.jboss.logging.Logger.getLogger(DataFolders.class).warnf("Cannot read %s: %s", record, e);
            return Optional.empty();
        }
        String from = p.getProperty("from");
        if (from == null || from.isBlank()) return Optional.empty();
        Path dir = Path.of(from).toAbsolutePath().normalize();
        if (dir.equals(dataDir.toAbsolutePath().normalize()) || !Files.isDirectory(dir)) return Optional.empty();
        return Optional.of(dir);
    }

    /** The data folder, then the former one if any: every folder an agent must never reach (ShellGuard). */
    public static List<Path> guarded(Path dataDir) {
        List<Path> dirs = new ArrayList<>();
        if (dataDir == null) return dirs;
        dirs.add(dataDir);
        legacy(dataDir).ifPresent(dirs::add);
        return List.copyOf(dirs);
    }

    /** Records that the journal of {@code dataDir} was copied from {@code from}'s, with how many events it held. */
    public static void recordMigration(Path dataDir, Path from, String sourceFile, long events) throws IOException {
        Properties p = new Properties();
        p.setProperty("from", from.toAbsolutePath().normalize().toString());
        p.setProperty("file", sourceFile);
        p.setProperty("events", Long.toString(events));
        p.setProperty("at", Instant.now().toString());
        try (Writer w = Files.newBufferedWriter(dataDir.resolve(MIGRATION_RECORD), StandardCharsets.UTF_8)) {
            p.store(
                    w,
                    " Floor42 copied its journal from Office's data folder (1.5.0). That folder was left as it was, as a"
                            + " backup; the worktrees of earlier missions stay in it, where git knows them.");
        }
        // The daemon's own write: what it reads from now on.
        if (FROZEN.containsKey(key(dataDir))) FROZEN.put(key(dataDir), read(dataDir));
    }
}
