package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.config.DataFolders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The journal's storage mode (v1.4, node N4): WAL while running, rollback again once stopped. */
class JournalWalTest {

    @TempDir
    Path dir;

    private static String pragma(Connection c, String name) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("PRAGMA " + name)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /**
     * The cause of the 59% JOURNAL_LOCKED of the M0 measure: a deferred transaction that reads, then writes, upgrades its
     * lock and SQLite answers SQLITE_BUSY at once without calling the busy handler. BEGIN IMMEDIATE waits instead.
     * (The daemon's pool is already IMMEDIATE; the M0 harness was not: SQLiteDataSource's own config overrode the URL.)
     */
    @Test
    void aDeferredTransactionThatReadsThenWritesGetsBusyAtOnceAnImmediateOneWaits() throws Exception {
        // Two deferred transactions both read, then both write: the second gets SQLITE_BUSY at once, whatever the
        // timeout (the busy handler would deadlock the first one's commit). Forced here by a barrier, no timing luck.
        assertThat(busyFailures("?busy_timeout=120000", true)).as("deferred").isEqualTo(1);
        // Immediate: the second one waits at BEGIN for the first one's commit.
        assertThat(busyFailures("?transaction_mode=IMMEDIATE&busy_timeout=120000", false))
                .as("immediate")
                .isZero();
    }

    private int busyFailures(String options, boolean readBothFirst) throws Exception {
        Path file = dir.resolve("cause" + options.length() + ".db");
        String url = "jdbc:sqlite:" + file + options;
        try (Connection c = DriverManager.getConnection(url);
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)");
        }
        AtomicInteger busy = new AtomicInteger();
        java.util.concurrent.CyclicBarrier bothHaveRead = new java.util.concurrent.CyclicBarrier(2);
        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < 2; w++) {
            Thread t = new Thread(() -> {
                try (Connection c = DriverManager.getConnection(url)) {
                    c.setAutoCommit(false);
                    try (Statement s = c.createStatement()) {
                        s.executeQuery("SELECT max(id) FROM t").close();
                        if (readBothFirst) bothHaveRead.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        s.executeUpdate("INSERT INTO t (v) VALUES ('x')");
                    }
                    c.commit();
                } catch (SQLException e) {
                    busy.incrementAndGet();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        return busy.get();
    }

    @Test
    void theDaemonSOwnUrlIsImmediateAndFullySynchronousInWal() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        // Synchronous FULL: a commit is on disk when append returns. NORMAL could lose the last commits to a power cut,
        // and the signed checkpoints and the continuity record would then show a journal that went back in time.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file + "?transaction_mode=IMMEDIATE")) {
            JournalConnections.useWal(c);
            assertThat(pragma(c, "journal_mode")).isEqualTo("wal");
            assertThat(pragma(c, "synchronous")).isEqualTo("2");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file + "?transaction_mode=IMMEDIATE")) {
            assertThat(pragma(c, "journal_mode"))
                    .as("persistent: a new connection finds it")
                    .isEqualTo("wal");
            assertThat(pragma(c, "synchronous")).isEqualTo("2");
        }
    }

    @Test
    void aCleanStopLeavesARollbackJournalWithNoWalFile() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.openWal(file);
        for (int i = 0; i < 50; i++) journal.append(List.of(TestJournals.created("M-" + i)));
        try (Connection running = journal.dataSource.getConnection()) {
            assertThat(pragma(running, "journal_mode")).as("running").isEqualTo("wal");
            assertThat(dir.resolve("floor42.db-wal")).as("a connection is open").exists();
        }

        journal.leaveWal();

        assertThat(dir.resolve("floor42.db-wal")).doesNotExist();
        assertThat(dir.resolve("floor42.db-shm")).doesNotExist();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertThat(pragma(c, "journal_mode")).isEqualTo("delete");
        }
        // A 1.3.0 binary opens the folder: it reads the file alone, header version 1 (rollback), nothing else needed.
        byte[] header = Files.readAllBytes(file);
        assertThat(header[18]).as("file format write version").isEqualTo((byte) 1);
        assertThat(header[19]).as("file format read version").isEqualTo((byte) 1);
        assertThat(TestJournals.open(file).readAll()).hasSize(50);
    }

    @Test
    void aBackupOfAWalJournalHoldsTheSameEvents() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.openWal(file);
        for (int i = 0; i < 30; i++) journal.append(List.of(TestJournals.created("M-" + i)));
        BackupStore store =
                new BackupStore(dir.resolve("backups"), Clock.systemUTC(), Map.of(BackupStore.Kind.MANUAL, 2));

        BackupStore.Backup backup = store.take(file, BackupStore.Kind.MANUAL);

        assertThat(backup.events()).isEqualTo(30);
        Path copy = store.find(backup.name()).orElseThrow();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + copy)) {
            assertThat(pragma(c, "integrity_check")).isEqualTo("ok");
        }
        byte[] header = Files.readAllBytes(copy);
        assertThat(header[18])
                .as("the copy is a rollback-mode file (write version)")
                .isEqualTo((byte) 1);
        assertThat(header[19])
                .as("the copy is a rollback-mode file (read version)")
                .isEqualTo((byte) 1);
        assertThat(Files.exists(dir.resolve("backups").resolve(backup.name() + "-wal")))
                .as("the copy needs no WAL file")
                .isFalse();
        // Same content in effect: the copy, read as a journal, holds the same events in the same order.
        SqliteJournal reader = TestJournals.open(copy);
        assertThat(reader.readAll()).isEqualTo(journal.readAll());
    }

    @Test
    void aStopThatCannotLeaveWalGivesUpAtItsDeadlineAndLeavesAConsistentJournal() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.openWal(file);
        for (int i = 0; i < 10; i++) journal.append(List.of(TestJournals.created("M-" + i)));
        journal.leaveWalMillis = 150;
        long start = System.nanoTime();
        try (Connection other = journal.dataSource.getConnection()) {
            other.createStatement()
                    .executeQuery("SELECT count(*) FROM event_journal")
                    .close();
            journal.leaveWal();
            assertThat(pragma(other, "journal_mode"))
                    .as("still WAL: another connection is open")
                    .isEqualTo("wal");
        }
        assertThat((System.nanoTime() - start) / 1_000_000)
                .as("bounded by the deadline")
                .isLessThan(3000);
        assertThat(TestJournals.open(file).readAll()).hasSize(10);
        journal.leaveWal();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertThat(pragma(c, "journal_mode")).isEqualTo("delete");
        }
    }

    /** WAL journal, then its backup, then the restore the daemon applies at stop (in memory, through a live connection). */
    @Test
    void aBackupOfAWalJournalRestoresIntoTheHotWalJournal() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.openWal(file);
        for (int i = 0; i < 30; i++) journal.append(List.of(TestJournals.created("M-" + i)));
        BackupStore store = new BackupStore(
                dir.resolve("backups"),
                Clock.systemUTC(),
                Map.of(BackupStore.Kind.MANUAL, 2, BackupStore.Kind.BEFORE_RESTORE, 2));
        String copy = store.take(file, BackupStore.Kind.MANUAL).name();
        for (int i = 30; i < 45; i++) journal.append(List.of(TestJournals.created("M-" + i)));

        PendingRestore.Outcome outcome;
        try (Connection live = journal.dataSource.getConnection()) {
            live.createStatement()
                    .executeQuery("SELECT count(*) FROM event_journal")
                    .close(); // the WAL is open
            outcome = PendingRestore.applyLive(live, file, store, copy, null, Long.MAX_VALUE, java.time.Instant.now());
        }

        assertThat(outcome.reason()).isNull();
        assertThat(outcome.restored()).isTrue();
        assertThat(journal.readAll()).hasSize(30);
        try (Connection c = journal.dataSource.getConnection()) {
            assertThat(pragma(c, "integrity_check")).isEqualTo("ok");
            assertThat(JournalChain.verify(c, java.util.Set.of()).firstBroken()).isNull();
        }
        journal.leaveWal();
        assertThat(TestJournals.open(file).readAll()).hasSize(30);
    }
}
