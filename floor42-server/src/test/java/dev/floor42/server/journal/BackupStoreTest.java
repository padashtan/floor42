package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.server.config.DataFolders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Journal copies (ADR-0042): rotation, consistency while writers write, and a restore applied at the next start. */
class BackupStoreTest {

    @TempDir
    Path dir;

    /** A clock the test moves. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-31T08:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final TestClock clock = new TestClock();

    BackupStore store(int startup, int daily) {
        return new BackupStore(
                dir.resolve("backups"),
                clock,
                Map.of(BackupStore.Kind.STARTUP, startup, BackupStore.Kind.DAILY, daily, BackupStore.Kind.MANUAL, 2));
    }

    Path journalWith(int events) {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.open(file);
        for (int i = 0; i < events; i++) journal.append(List.of(TestJournals.created("M-" + i)));
        return file;
    }

    @Test
    void theRestoreSafetyCopiesAreRotatedLikeTheOthers() throws Exception {
        Path journal = journalWith(1);
        BackupStore store = new BackupStore(
                dir.resolve("backups"),
                clock,
                Map.of(BackupStore.Kind.BEFORE_RESTORE, 2, BackupStore.Kind.DISPLACED, 1));

        for (int i = 0; i < 3; i++) {
            store.take(journal, BackupStore.Kind.BEFORE_RESTORE);
            store.take(journal, BackupStore.Kind.DISPLACED);
            clock.now = clock.now.plusSeconds(60);
        }

        assertThat(store.list().stream().filter(b -> b.kind() == BackupStore.Kind.BEFORE_RESTORE))
                .hasSize(2);
        assertThat(store.list().stream().filter(b -> b.kind() == BackupStore.Kind.DISPLACED))
                .hasSize(1);
    }

    @Test
    void eachKindKeepsItsLastCopiesAndADailyOneIsTakenOncePerDay() throws Exception {
        Path journal = journalWith(3);
        BackupStore store = store(2, 3);

        List<String> startups = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            startups.add(store.take(journal, BackupStore.Kind.STARTUP).name());
            clock.now = clock.now.plusSeconds(60);
        }
        for (int day = 0; day < 5; day++) {
            assertThat(store.takeDailyIfDue(journal)).isPresent();
            assertThat(store.takeDailyIfDue(journal)).as("once a day").isEmpty();
            clock.now = clock.now.plus(Duration.ofDays(1));
        }
        store.take(journal, BackupStore.Kind.MANUAL);

        List<BackupStore.Backup> kept = store.list();
        assertThat(kept)
                .filteredOn(b -> b.kind() == BackupStore.Kind.STARTUP)
                .extracting(BackupStore.Backup::name)
                .containsExactlyInAnyOrder(startups.get(2), startups.get(3));
        assertThat(kept)
                .filteredOn(b -> b.kind() == BackupStore.Kind.DAILY)
                .extracting(BackupStore.Backup::name)
                .containsExactlyInAnyOrder("daily-2026-11-02.db", "daily-2026-11-03.db", "daily-2026-11-04.db");
        assertThat(kept).allSatisfy(b -> {
            assertThat(b.events()).isEqualTo(3);
            assertThat(b.sizeBytes()).isPositive();
        });
        // Nothing half-written is left, and a file of another name is never listed nor deleted.
        Files.writeString(store.dir().resolve("notes.txt"), "mine");
        assertThat(store.list()).noneMatch(b -> b.name().endsWith(".partial"));
        assertThat(store.find("../floor42.db")).isEmpty();
        assertThat(store.find("notes.txt")).isEmpty();
        assertThat(store.dir().resolve("notes.txt")).exists();
    }

    @Test
    void aCopyTakenWhileCommandsAreWrittenHoldsWholeCommandsOnly() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.open(file);
        journal.append(List.of(TestJournals.created("M-0")));
        BackupStore store = new BackupStore(dir.resolve("backups"), Clock.systemUTC(), Map.of());

        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger written = new AtomicInteger();
        AtomicReference<Throwable> writerFailed = new AtomicReference<>();
        Thread writer = Thread.ofPlatform().start(() -> {
            try {
                for (int i = 1; !stop.get(); i++) {
                    // Commands of two events: a copy must hold both or neither.
                    journal.append(List.of(TestJournals.created("M-" + i), TestJournals.completed("M-" + i)));
                    written.incrementAndGet();
                }
            } catch (Throwable t) {
                writerFailed.set(t);
            }
        });
        List<BackupStore.Backup> copies = new ArrayList<>();
        try {
            while (copies.size() < 12) {
                copies.add(store.take(file, BackupStore.Kind.MANUAL));
            }
        } finally {
            stop.set(true);
            writer.join();
        }

        assertThat(writerFailed.get())
                .as("writers never fail because of a copy")
                .isNull();
        assertThat(written.get()).isPositive();
        long previous = 0;
        for (BackupStore.Backup copy : copies) {
            Path f = store.dir().resolve(copy.name());
            assertThat(BackupStore.check(f)).isEqualTo(copy.events());
            assertThat(copy.events() % 2).as("whole commands: %s", copy.name()).isEqualTo(1);
            assertThat(copy.events()).isGreaterThanOrEqualTo(previous);
            previous = copy.events();
            SqliteJournal read = new SqliteJournal();
            org.sqlite.SQLiteDataSource ds = new org.sqlite.SQLiteDataSource();
            ds.setUrl("jdbc:sqlite:" + f);
            read.dataSource = ds;
            Journal.Read all = read.replay();
            assertThat(all.unreadable()).isEmpty();
            assertThat(all.entries()).hasSize((int) copy.events());
            try (Connection c = ds.getConnection();
                    PreparedStatement ps = c.prepareStatement("SELECT max(sequence) FROM event_journal");
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).as("no gap").isEqualTo(copy.events());
            }
        }
        assertThat(copies.getLast().events()).isGreaterThan(1);
    }

    /** A connection that keeps the journal's write lock, readers included, until it is committed. */
    static java.sql.Connection lockJournal(Path file) throws Exception {
        java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
        try (java.sql.Statement st = c.createStatement()) {
            st.execute("BEGIN EXCLUSIVE");
        }
        return c;
    }

    @Test
    void aCopyWaitsForTheWriteLockToBeReleasedThenHoldsEveryEvent() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.open(file);
        journal.append(List.of(TestJournals.created("M-0")));
        BackupStore store = new BackupStore(dir.resolve("backups"), Clock.systemUTC(), Map.of());
        store.busyTimeoutMillis = 30;
        store.retryPauseMillis = 10;

        try (java.sql.Connection lock = lockJournal(file)) {
            Thread release = Thread.ofPlatform().start(() -> {
                try {
                    // The lock outlasts the busy timeout of two attempts, then goes.
                    while (store.attempts < 3) Thread.sleep(5);
                    lock.createStatement().execute("COMMIT");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            BackupStore.Backup copy = store.take(file, BackupStore.Kind.MANUAL);
            release.join();

            assertThat(copy.events()).isEqualTo(1);
            assertThat(BackupStore.check(store.dir().resolve(copy.name()))).isEqualTo(1);
            assertThat(store.attempts).isBetween(3, BackupStore.BUSY_ATTEMPTS);
        }
    }

    @Test
    void aCopyGivesUpWithTheBusyErrorAfterTheBoundedAttempts() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.open(file);
        journal.append(List.of(TestJournals.created("M-0")));
        BackupStore store = new BackupStore(dir.resolve("backups"), Clock.systemUTC(), Map.of());
        store.busyTimeoutMillis = 20;
        store.retryPauseMillis = 5;

        try (java.sql.Connection lock = lockJournal(file)) {
            assertThatThrownBy(() -> store.take(file, BackupStore.Kind.MANUAL))
                    .isInstanceOfSatisfying(
                            org.sqlite.SQLiteException.class,
                            e -> assertThat(e.getResultCode()).isEqualTo(org.sqlite.SQLiteErrorCode.SQLITE_BUSY));
            assertThat(store.attempts).isEqualTo(BackupStore.BUSY_ATTEMPTS);
            assertThat(store.list()).isEmpty();
        }
    }

    /** The daemon's own bean, on this test's folders. */
    JournalBackups backups() {
        JournalBackups b = new JournalBackups();
        b.dataDir = dir;
        b.enabled = true;
        b.configuredDir = java.util.Optional.of(dir.resolve("backups"));
        b.startupKeep = 5;
        b.dailyKeep = 7;
        b.manualKeep = 5;
        return b;
    }

    @Test
    void aRestoreTheDirectorPreparedIsAppliedWhenTheDaemonStopsAndReportedByTheNextStart() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.open(file);
        for (int i = 0; i < 2; i++) journal.append(List.of(TestJournals.created("M-" + i)));
        JournalBackups backups = backups();
        journal.backups = backups;
        String older = backups.take(BackupStore.Kind.MANUAL).orElseThrow().name();
        journal.append(List.of(TestJournals.created("M-late")));

        backups.prepareRestore(older);
        assertThat(backups.state().pendingRestore()).isEqualTo(older);
        assertThat(dir.resolve(PendingRestore.MARKER)).as("in memory only").doesNotExist();
        journal.restoreAtShutdown(null);

        assertThat(BackupStore.events(file)).isEqualTo(2);
        assertThat(journal.readAll())
                .as("the live pool reads the restored pages")
                .hasSize(2);
        PendingRestore.Outcome outcome = backups.state().lastRestore();
        assertThat(outcome.restored()).isTrue();
        assertThat(outcome.keptAs()).startsWith("before-restore-");
        assertThat(BackupStore.events(dir.resolve("backups").resolve(outcome.keptAs())))
                .isEqualTo(3);
        assertThatThrownBy(() -> journal.append(List.of(TestJournals.created("M-after"))))
                .as("nothing written during the stop lands in the restored journal")
                .isInstanceOf(JournalException.class)
                .hasMessageContaining("takes no more events");

        JournalBackups next = backups();
        next.atStart();
        assertThat(next.state().lastRestore().restored()).isTrue();
        assertThat(next.state().lastRestore().matchesJournal()).isTrue();
        assertThat(next.state().refusedRestore()).isNull();
        assertThat(BackupStore.events(file)).as("a start never restores").isEqualTo(2);
    }

    /** Counter-check 4: a copy under 1 GiB but over the heap threw OutOfMemoryError at the stop, with no outcome. */
    @Test
    void aCopyHeavierThanTheMemoryBudgetIsRefusedWhenPreparedAndAgainAtTheStop() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.open(file);
        journal.append(List.of(TestJournals.created("M-1")));
        JournalBackups backups = backups();
        journal.backups = backups;
        String copy = backups.take(BackupStore.Kind.MANUAL).orElseThrow().name();
        backups.restoreMemoryBytes = java.util.Optional.of(100L);

        assertThatThrownBy(() -> backups.prepareRestore(copy))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("can restore from memory");
        assertThat(backups.state().pendingRestore()).isNull();

        backups.restoreMemoryBytes = java.util.Optional.empty();
        backups.prepareRestore(copy);
        backups.restoreMemoryBytes = java.util.Optional.of(100L);
        journal.restoreAtShutdown(null);

        assertThat(backups.state().lastRestore().restored()).isFalse();
        assertThat(backups.state().lastRestore().reason()).contains("can restore from memory");
        assertThat(dir.resolve(PendingRestore.OUTCOME))
                .as("the next start reports it")
                .exists();
    }

    @Test
    void anOutOfMemoryErrorWhileRestoringIsAnOutcomeNotAnEscapedError() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.open(file);
        journal.append(List.of(TestJournals.created("M-1")));
        BackupStore store = store(5, 7);
        String copy = store.take(file, BackupStore.Kind.MANUAL).name();

        PendingRestore.Outcome outcome;
        try (Connection live = journal.dataSource.getConnection()) {
            Connection oom = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, m, args) -> {
                        if (m.getName().equals("prepareStatement") && "VACUUM INTO ?".equals(args[0])) {
                            throw new OutOfMemoryError("Java heap space");
                        }
                        try {
                            return m.invoke(live, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            outcome = PendingRestore.applyLive(oom, file, store, copy, null, Long.MAX_VALUE, clock.instant());
        }

        assertThat(outcome.restored()).isFalse();
        assertThat(outcome.reason()).contains("not enough memory");
        assertThat(BackupStore.events(file)).as("the journal is left as it was").isEqualTo(1);
    }

    /** A DataSource whose connections close badly, or that gives none: the stop must still record what happened. */
    private static javax.sql.DataSource failing(javax.sql.DataSource real, boolean noConnection) {
        return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                javax.sql.DataSource.class.getClassLoader(), new Class<?>[] {javax.sql.DataSource.class}, (p, m, a) -> {
                    if (!m.getName().equals("getConnection")) return unwrap(m, real, a);
                    if (noConnection) throw new java.sql.SQLException("no connection");
                    Connection live = real.getConnection();
                    return java.lang.reflect.Proxy.newProxyInstance(
                            Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (cp, cm, ca) -> {
                                if (cm.getName().equals("close")) {
                                    live.close();
                                    throw new java.sql.SQLException("close failed");
                                }
                                return unwrap(cm, live, ca);
                            });
                });
    }

    private static Object unwrap(java.lang.reflect.Method m, Object target, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private SqliteJournal journalWithPreparedRestore() throws Exception {
        SqliteJournal journal = TestJournals.open(dir.resolve(DataFolders.JOURNAL));
        journal.append(List.of(TestJournals.created("M-1")));
        JournalBackups backups = backups();
        journal.backups = backups;
        String copy = backups.take(BackupStore.Kind.MANUAL).orElseThrow().name();
        journal.append(List.of(TestJournals.created("M-2")));
        backups.prepareRestore(copy);
        return journal;
    }

    @Test
    void aStopThatCannotOpenTheJournalStillWritesTheNotAppliedOutcome() throws Exception {
        SqliteJournal journal = journalWithPreparedRestore();
        journal.dataSource = failing(journal.dataSource, true);
        journal.restoreAtShutdown(null);

        assertThat(journal.backups.state().lastRestore().restored()).isFalse();
        assertThat(journal.backups.state().lastRestore().reason()).contains("cannot be opened");
        assertThat(dir.resolve(PendingRestore.OUTCOME)).exists();
    }

    @Test
    void aConnectionThatFailsToCloseAfterARestoreDoesNotTurnItIntoNotApplied() throws Exception {
        SqliteJournal journal = journalWithPreparedRestore();
        journal.dataSource = failing(journal.dataSource, false);
        journal.restoreAtShutdown(null);

        assertThat(journal.backups.state().lastRestore().restored()).isTrue();
        assertThat(BackupStore.events(dir.resolve(DataFolders.JOURNAL))).isEqualTo(1);
    }

    @Test
    void aFailureToCheckAfterTheJournalWasWrittenSaysItWasRestoredButNotChecked() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.open(file);
        journal.append(List.of(TestJournals.created("M-1")));
        BackupStore store = store(5, 7);
        String copy = store.take(file, BackupStore.Kind.MANUAL).name();

        PendingRestore.Outcome outcome;
        try (Connection live = journal.dataSource.getConnection()) {
            Connection late = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, m, args) -> {
                        if (m.getName().equals("prepareStatement")
                                && "SELECT count(*) FROM event_journal".equals(args[0])) {
                            throw new OutOfMemoryError("Java heap space");
                        }
                        return unwrap(m, live, args);
                    });
            outcome = PendingRestore.applyLive(late, file, store, copy, null, Long.MAX_VALUE, clock.instant());
        }

        assertThat(outcome.restored()).isFalse();
        assertThat(outcome.reason()).startsWith("restored, but the restored journal could not be checked");
        assertThat(outcome.reason()).doesNotContain("partly");
        assertThat(outcome.keptAs()).startsWith("before-restore-");
    }

    /**
     * SQLite's backup writes the destination in one transaction: a call that answers an error (here a read-only
     * journal file) wrote nothing, and the outcome says so instead of "may be partly restored".
     */
    @Test
    void aBackupCallThatAnswersAnErrorSaysTheJournalIsUnchanged() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.open(file);
        journal.append(List.of(TestJournals.created("M-1")));
        BackupStore store = store(5, 7);
        String copy = store.take(file, BackupStore.Kind.MANUAL).name();
        Path locked = dir.resolve("read-only.db");
        Files.copy(file, locked);
        assertThat(locked.toFile().setReadOnly()).isTrue();

        PendingRestore.Outcome outcome;
        try (Connection live = journal.dataSource.getConnection()) {
            outcome = PendingRestore.applyLive(live, locked, store, copy, null, Long.MAX_VALUE, clock.instant());
        } finally {
            locked.toFile().setWritable(true);
        }

        assertThat(outcome.restored()).isFalse();
        assertThat(outcome.reason()).startsWith("not restored, the journal is unchanged");
        assertThat(outcome.reason()).doesNotContain("partly");
        assertThat(outcome.keptAs()).startsWith("before-restore-");
    }

    @Test
    void aRestoreOfAMissingCopyLeavesTheJournalAsItWas() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.open(file);
        journal.append(List.of(TestJournals.created("M-1")));
        PendingRestore.Outcome outcome;
        try (java.sql.Connection c = journal.dataSource.getConnection()) {
            outcome = PendingRestore.applyLive(
                    c, file, store(5, 7), "manual-20261031T080000000Z.db", null, Long.MAX_VALUE, clock.instant());
        }

        assertThat(outcome.restored()).isFalse();
        assertThat(outcome.reason()).contains("no backup named");
        assertThat(BackupStore.events(file)).isEqualTo(1);
    }

    /**
     * The hole of 1.7.0: an agent of the daemon's OS user wrote restore-pending.properties naming an old copy, then
     * made the daemon restart ({@code kill -TERM 1} in Docker): the start rolled the journal back. Now a start refuses
     * the file, keeps it aside and reports it; the journal stays as it was.
     */
    @Test
    void aRestoreMarkerPlantedInTheDataFolderIsRefusedAtStartAndReported() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.open(file);
        journal.append(List.of(TestJournals.created("M-1")));
        JournalBackups backups = backups();
        String older = backups.take(BackupStore.Kind.MANUAL).orElseThrow().name();
        journal.append(List.of(TestJournals.created("M-2")));
        Files.writeString(dir.resolve(PendingRestore.MARKER), "backup=" + older + "\n");

        backups.atStart();

        assertThat(BackupStore.events(file)).as("not rolled back").isEqualTo(2);
        PendingRestore.Refused refused = backups.state().refusedRestore();
        assertThat(refused.backup()).isEqualTo(older);
        assertThat(refused.keptAs()).isEqualTo(PendingRestore.REFUSED);
        assertThat(dir.resolve(PendingRestore.MARKER)).doesNotExist();
        assertThat(dir.resolve(PendingRestore.REFUSED)).exists();
        assertThat(backups.state().pendingRestore()).isNull();
    }

    @Test
    void aForgedOutcomeIsReportedAsNotVerified() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        TestJournals.open(file).append(List.of(TestJournals.created("M-1")));
        PendingRestore.writeOutcome(
                dir,
                new PendingRestore.Outcome(
                        true, "manual-x.db", 40, "before-restore-20261031T080000000Z.db", null, clock.instant(), true));

        JournalBackups backups = backups();
        backups.atStart();

        assertThat(backups.state().lastRestore().matchesJournal()).isFalse();
        assertThat(dir.resolve(PendingRestore.OUTCOME)).as("read once").doesNotExist();
    }

    /**
     * Counter-check of 1.8.0: a forged "not applied" outcome with a text of the forger's was shown as verified. Now it
     * is reported as a file says it, with nothing claimed checked, and its text bounded.
     */
    @Test
    void aForgedFailedOutcomeClaimsNothingCheckedAndItsTextIsBounded() throws Exception {
        TestJournals.open(dir.resolve(DataFolders.JOURNAL)).append(List.of(TestJournals.created("M-1")));
        PendingRestore.writeOutcome(
                dir,
                new PendingRestore.Outcome(
                        false, "daily-x.db", -1, null, "Disk failure\ncall " + "x".repeat(500), clock.instant(), null));

        JournalBackups backups = backups();
        backups.atStart();

        PendingRestore.Outcome o = backups.state().lastRestore();
        assertThat(o.restored()).isFalse();
        assertThat(o.matchesJournal())
                .as("nothing to check: never shown as verified")
                .isNull();
        assertThat(o.reason()).doesNotContain("\n").hasSizeLessThanOrEqualTo(201);
    }

    /**
     * Counter-check of 1.8.0: the copy the director prepared could be replaced before the stop applied it. Now its
     * SHA-256 is taken at preparation, the stop restores from a private copy only when it still matches.
     */
    @Test
    void aBackupChangedAfterTheDirectorPreparedItIsRefusedAtTheStop() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.open(file);
        journal.append(List.of(TestJournals.created("M-1")));
        JournalBackups backups = backups();
        journal.backups = backups;
        String one = backups.take(BackupStore.Kind.MANUAL).orElseThrow().name();
        journal.append(List.of(TestJournals.created("M-2")));
        clock.now = clock.now.plusSeconds(1);
        String two = backups.take(BackupStore.Kind.MANUAL).orElseThrow().name();
        journal.append(List.of(TestJournals.created("M-3")));

        backups.prepareRestore(two);
        Path b = dir.resolve("backups");
        Files.copy(b.resolve(one), b.resolve(two), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        journal.restoreAtShutdown(null);

        assertThat(backups.state().lastRestore().restored()).isFalse();
        assertThat(backups.state().lastRestore().reason()).contains("changed since it was prepared");
        assertThat(BackupStore.events(file)).as("left as it was").isEqualTo(3);
    }

    /**
     * Counter-check of amendment 2: the stop copied the prepared backup to a file in the shared temporary folder,
     * hashed it, then restored that file by its name: a same-user process could change it in between. Now the bytes
     * hashed are the bytes restored, held in memory. Here everything named like a restore's copy, and the backup
     * itself, is overwritten with another copy just after the hash, while the journal being replaced is copied.
     */
    @Test
    void aCopyChangedBetweenItsHashAndTheRestoreChangesNothingRestored() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.open(file);
        journal.append(List.of(TestJournals.created("M-1")));
        BackupStore store = store(5, 7);
        Path forged = dir.resolve("backups")
                .resolve(store.take(file, BackupStore.Kind.MANUAL).name());
        journal.append(List.of(TestJournals.created("M-2"), TestJournals.created("M-3")));
        clock.now = clock.now.plusSeconds(1);
        String chosen = store.take(file, BackupStore.Kind.MANUAL).name();
        String sha = BackupStore.sha256(store.dir().resolve(chosen));
        journal.append(List.of(TestJournals.created("M-4")));

        PendingRestore.Outcome outcome;
        try (Connection live = journal.dataSource.getConnection()) {
            Connection tampering = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, m, args) -> {
                        if (m.getName().equals("prepareStatement") && "VACUUM INTO ?".equals(args[0])) {
                            overwriteRestoreCopies(forged, store.dir().resolve(chosen));
                        }
                        try {
                            return m.invoke(live, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            outcome = PendingRestore.applyLive(tampering, file, store, chosen, sha, Long.MAX_VALUE, clock.instant());
        }

        assertThat(outcome.reason()).isNull();
        assertThat(outcome.restored()).isTrue();
        assertThat(BackupStore.events(file)).as("the chosen copy, as hashed").isEqualTo(3);
    }

    /**
     * What a same-user process can do: overwrite any file it finds named like the restore's copy (only those written
     * in the last minute, to leave other runs' alone).
     */
    static void overwriteRestoreCopies(Path with, Path backup) throws java.io.IOException {
        var replace = java.nio.file.StandardCopyOption.REPLACE_EXISTING;
        Instant recent = Instant.now().minusSeconds(60);
        try (var temp = Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")), "floor42-restore-*")) {
            for (Path p : temp) {
                if (Files.getLastModifiedTime(p).toInstant().isAfter(recent)) Files.copy(with, p, replace);
            }
        }
        Files.copy(with, backup, replace);
    }

    @Test
    void copiesAreNeverMadeInsideAWorktreeOrAGitWorkTree() throws Exception {
        Path data = Files.createDirectories(dir.resolve("data"));
        assertThat(JournalBackups.insideAWorktree(data.resolve("backups"), data))
                .isNull();
        assertThat(JournalBackups.insideAWorktree(data.resolve("workspaces/M-1-chris/backups"), data))
                .contains("agents' worktrees");
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        assertThat(JournalBackups.insideAWorktree(repo.resolve("target/backups"), data))
                .contains("git work tree");
    }

    @Test
    void whyCopiesAreNotMadeIsACodeAndAFolder() throws Exception {
        Path data = Files.createDirectories(dir.resolve("data2"));
        assertThat(JournalBackups.where(data.resolve("backups"), data)).isNull();
        var agents = JournalBackups.where(data.resolve("workspaces/M-1-chris/backups"), data);
        assertThat(agents.code()).isEqualTo("IN_AGENT_WORKTREES");
        assertThat(agents.path())
                .isEqualTo(data.resolve("workspaces").toAbsolutePath().normalize());
        Path repo = Files.createDirectories(dir.resolve("repo2"));
        Files.createDirectories(repo.resolve(".git"));
        var git = JournalBackups.where(repo.resolve("target/backups"), data);
        assertThat(git.code()).isEqualTo("IN_GIT_WORKTREE");
        assertThat(git.path()).isEqualTo(repo.toAbsolutePath().normalize());
    }
}
