package dev.floor42.server.journal;

import dev.floor42.server.config.DataFolders;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Automatic copies of the journal (ADR-0042): one at every start, before migration and replay; one a day (UTC) while the
 * daemon runs; one when the director asks. They live in {@code floor42.backup.dir} (default {@code
 * <data-dir>/backups}), never in a worktree or a git repository, where an agent or a commit could reach them.
 */
@ApplicationScoped
public class JournalBackups {

    private static final Logger LOG = Logger.getLogger(JournalBackups.class);

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    @ConfigProperty(name = "floor42.backup.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "floor42.backup.dir")
    Optional<Path> configuredDir;

    @ConfigProperty(name = "floor42.backup.startup-keep", defaultValue = "5")
    int startupKeep;

    @ConfigProperty(name = "floor42.backup.daily-keep", defaultValue = "7")
    int dailyKeep;

    @ConfigProperty(name = "floor42.backup.manual-keep", defaultValue = "5")
    int manualKeep;

    /** Copies kept of each of the two safety kinds, before-restore and displaced (a journal each: they add up). */
    @ConfigProperty(name = "floor42.backup.restore-keep", defaultValue = "5")
    int restoreKeep;

    /** How often the daemon checks whether today's copy is due. */
    @ConfigProperty(name = "floor42.backup.check-minutes", defaultValue = "60")
    long checkMinutes;

    /** Heap a restore from memory may take; unset: a quarter of the JVM's maximum heap. Tests set a small one. */
    @ConfigProperty(name = "floor42.backup.restore-memory-bytes")
    Optional<Long> restoreMemoryBytes;

    private final Clock clock = Clock.systemUTC();
    private volatile String lastError;
    private volatile PendingRestore.Outcome lastRestore;
    /** The copy the director chose: in memory only, applied when this daemon stops (ADR-0042, amended). */
    private volatile Pending pendingRestore;
    /** A file in the data folder that asked this start for a restore, refused. */
    private volatile PendingRestore.Refused refusedRestore;

    private ScheduledExecutorService daily;

    /** What the settings show: where, how many kept, what failed last, what waits for the next start. */
    public record State(
            String folder,
            boolean enabled,
            String disabledReason,
            String disabledCode,
            String disabledPath,
            int startupKeep,
            int dailyKeep,
            int manualKeep,
            String lastError,
            String pendingRestore,
            PendingRestore.Outcome lastRestore,
            PendingRestore.Refused refusedRestore,
            List<BackupStore.Backup> backups) {
        public State {
            backups = backups == null ? null : Collections.unmodifiableList(new ArrayList<>(backups));
        }
    }

    public Path folder() {
        return configuredDir.orElse(dataDir.resolve("backups")).toAbsolutePath().normalize();
    }

    /** Why copies are not made, or null when they are. */
    public String disabledReason() {
        return reason(found());
    }

    private Where found() {
        return enabled ? where(folder(), dataDir) : null;
    }

    private String reason(Where w) {
        return enabled ? describe(w) : "turned off (floor42.backup.enabled=false)";
    }

    /** The code of {@link #disabledReason()} for the UI to translate: OFF, IN_AGENT_WORKTREES or IN_GIT_WORKTREE. */
    public String disabledCode() {
        return code(found());
    }

    private String code(Where w) {
        return !enabled ? "OFF" : w == null ? null : w.code();
    }

    /** The folder the code speaks of (the agents' worktrees, the git work tree), null for OFF or when enabled. */
    public String disabledPath() {
        return path(found());
    }

    private static String path(Where w) {
        return w == null ? null : w.path().toString();
    }

    record Where(String code, Path folder, Path path) {}

    /**
     * A copy holds every event, and events may quote what agents saw: never in the folders where agents work, nor in a
     * git work tree, where it could be committed. The home folder itself is not counted (some keep it in git).
     */
    static String insideAWorktree(Path folder, Path dataDir) {
        return describe(where(folder, dataDir));
    }

    private static String describe(Where w) {
        if (w == null) return null;
        return w.folder()
                + (w.code().equals("IN_GIT_WORKTREE")
                        ? " is inside the git work tree " + w.path()
                        : " is inside the agents' worktrees (" + w.path() + ")");
    }

    static Where where(Path folder, Path dataDir) {
        Path f = folder.toAbsolutePath().normalize();
        for (Path root : DataFolders.guarded(dataDir)) {
            Path workspaces = root.toAbsolutePath().normalize().resolve("workspaces");
            if (f.startsWith(workspaces)) return new Where("IN_AGENT_WORKTREES", f, workspaces);
        }
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        for (Path p = f; p != null; p = p.getParent()) {
            if (p.equals(home)) continue;
            if (Files.exists(p.resolve(".git"))) return new Where("IN_GIT_WORKTREE", f, p);
        }
        return null;
    }

    BackupStore store() {
        return new BackupStore(
                folder(),
                clock,
                Map.of(
                        BackupStore.Kind.STARTUP, startupKeep,
                        BackupStore.Kind.DAILY, dailyKeep,
                        BackupStore.Kind.MANUAL, manualKeep,
                        BackupStore.Kind.BEFORE_RESTORE, restoreKeep,
                        BackupStore.Kind.DISPLACED, restoreKeep));
    }

    private Path journal() {
        return dataDir.resolve(DataFolders.JOURNAL);
    }

    /**
     * Before anything opens the journal: never restores. A file asking for a restore is refused and reported; what the
     * last stop did with the director's restore is read, checked and reported.
     */
    void atStart() {
        PendingRestore.refuseMarker(dataDir).ifPresent(r -> refusedRestore = r);
        PendingRestore.takeOutcome(dataDir, journal(), store()).ifPresent(o -> lastRestore = o);
    }

    /**
     * A restore the director prepared: the copy's name and its SHA-256 when they prepared it, so a copy changed since
     * (by a process of the same OS user) is refused at the stop.
     */
    public record Pending(String name, String sha256) {}

    /** The restore the director prepared, taken once by the shutdown that applies it. */
    synchronized Optional<Pending> takePendingRestore() {
        Pending p = pendingRestore;
        pendingRestore = null;
        return Optional.ofNullable(p);
    }

    /** The most a copy may weigh to be restored from memory (the heap it may take, at most 1 GiB). */
    public long restoreLimitBytes() {
        long budget = java.util.Objects.nonNull(restoreMemoryBytes) && restoreMemoryBytes.isPresent()
                ? restoreMemoryBytes.get()
                : PendingRestore.defaultBudget(Runtime.getRuntime().maxMemory());
        return Math.min(PendingRestore.MAX_RESTORE_BYTES, budget);
    }

    /** Applies a restore now, through this connection of the live journal (the daemon's own shutdown only). */
    PendingRestore.Outcome applyLive(Connection c, Pending pending) {
        return recorded(PendingRestore.applyLive(
                c, journal(), store(), pending.name(), pending.sha256(), restoreLimitBytes(), clock.instant()));
    }

    /** A prepared restore that could not even be tried (no connection, journal file replaced): said at the next start. */
    PendingRestore.Outcome notApplied(Pending pending, String reason) {
        LOG.errorf("The prepared restore of %s was not applied: %s", pending.name(), reason);
        return recorded(new PendingRestore.Outcome(false, pending.name(), -1, null, reason, clock.instant(), null));
    }

    private PendingRestore.Outcome recorded(PendingRestore.Outcome outcome) {
        lastRestore = outcome;
        try {
            PendingRestore.writeOutcome(dataDir, outcome);
        } catch (IOException e) {
            // Handled: logged; only the report at the next start is missing
            LOG.errorf("Cannot write %s: the next start will not report the restore", PendingRestore.OUTCOME);
        }
        return outcome;
    }

    /** The copy of every start, before migration and replay. A failure is reported, never blocks the start. */
    void atStartup() {
        take(BackupStore.Kind.STARTUP);
    }

    void startDaily(@Observes StartupEvent ignored) {
        if (disabledReason() != null) {
            LOG.errorf("No automatic backup of the journal: %s", disabledReason());
            return;
        }
        daily = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "floor42-journal-backup");
            t.setDaemon(true);
            return t;
        });
        daily.scheduleWithFixedDelay(this::dailyIfDue, 0, Math.max(1, checkMinutes), TimeUnit.MINUTES);
    }

    @PreDestroy
    void stop() {
        if (daily != null) daily.shutdownNow();
    }

    synchronized void dailyIfDue() {
        if (disabledReason() != null || !present()) return;
        try {
            store().takeDailyIfDue(journal())
                    .ifPresent(b -> LOG.infof("Daily backup of the journal: %s (%d events)", b.name(), b.events()));
            lastError = null;
        } catch (IOException | SQLException | RuntimeException e) {
            // Handled: logged and shown in Settings (failed)
            failed(BackupStore.Kind.DAILY, e);
        }
    }

    /** A copy now, of this kind; empty when none was made (turned off, no journal yet, or it failed). */
    public synchronized Optional<BackupStore.Backup> take(BackupStore.Kind kind) {
        String off = disabledReason();
        if (off != null) {
            if (kind != BackupStore.Kind.STARTUP) throw new IllegalStateException("No backup: " + off);
            return Optional.empty();
        }
        if (!present()) return Optional.empty();
        try {
            BackupStore.Backup b = store().take(journal(), kind);
            LOG.infof("Backup of the journal: %s (%d events)", b.name(), b.events());
            lastError = null;
            return Optional.of(b);
        } catch (IOException | SQLException | RuntimeException e) {
            // Handled: logged and shown in Settings (failed)
            failed(kind, e);
            return Optional.empty();
        }
    }

    private void failed(BackupStore.Kind kind, Exception e) {
        lastError = kind.name() + " backup failed at " + clock.instant() + ": " + e.getMessage();
        LOG.errorf(e, "Backup of the journal failed (%s)", kind);
    }

    private boolean present() {
        try {
            return Files.isRegularFile(journal()) && Files.size(journal()) > 0;
        } catch (IOException e) {
            // Handled: a journal that cannot be read is not copied
            return false;
        }
    }

    public State state() {
        Where w = found();
        return new State(
                folder().toString(),
                enabled,
                reason(w),
                code(w),
                path(w),
                startupKeep,
                dailyKeep,
                manualKeep,
                lastError,
                pendingRestore == null ? null : pendingRestore.name(),
                lastRestore,
                refusedRestore,
                store().list());
    }

    /**
     * This daemon restores this copy when it stops in order. Kept in memory only, never in a file a start would read:
     * a crash or a kill forgets it. A new preparation replaces the previous one.
     */
    public synchronized void prepareRestore(String name) throws IOException, SQLException {
        Path backup = store().find(name).orElseThrow(() -> new IllegalArgumentException("No backup named " + name));
        BackupStore.check(backup);
        String tooLarge = PendingRestore.tooLarge(name, Files.size(backup), restoreLimitBytes());
        if (tooLarge != null) throw new IOException(tooLarge);
        pendingRestore = new Pending(name, BackupStore.sha256(backup));
        LOG.warnf("Restore of %s prepared by the director: it is applied when Floor42 stops", name);
    }

    public synchronized void cancelRestore() {
        pendingRestore = null;
    }
}
