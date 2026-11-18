package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.config.DataFolders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** One writer, then four, appending while a backup loops (v1.4, node N4). The daemon appends from one thread. */
@Tag("slow")
class JournalWritersTest {

    private static final int APPENDS = 1000;
    private static final int SEED = 2000;

    @TempDir
    Path dir;

    /**
     * The daemon's shape: one writer (OfficeService.execute is synchronized) and the backup reader. WAL is judged
     * against rollback mode measured in the same run on the same machine, not against an absolute time.
     */
    @Test
    void oneWriterAndALoopingBackupGetNoJournalLockedAndWalHalvesTheTailLatency() throws Exception {
        double rollback = scenarioWith(1, false);
        double wal = scenarioWith(1, true);
        System.out.printf(
                "N4 ratio p95(wal)/p95(rollback) = %.3f (wal %.1f ms, rollback %.1f ms)%n",
                wal / rollback, wal, rollback);
        assertThat(wal)
                .as("p95 WAL (ms) <= 0.5 x p95 rollback (%.1f ms)", rollback)
                .isLessThanOrEqualTo(0.5 * rollback);
    }

    /** Correctness only, no latency bound: IMMEDIATE transactions queue on SQLite's lock, the chain stays whole. */
    @Test
    void fourWritersNeverGetJournalLockedAndTheChainFollowsTheCommits() throws Exception {
        scenarioWith(4, true);
    }

    private double scenarioWith(int writerCount, boolean wal) throws Exception {
        Path file = Files.createDirectories(dir.resolve("w" + writerCount + (wal ? "wal" : "rollback")))
                .resolve(DataFolders.JOURNAL);
        // Four threads contend for SQLite's lock, whose wait is unfair: a long timeout, so none starves into BUSY.
        SqliteJournal journal = wal ? TestJournals.openWal(file, 120_000) : TestJournals.open(file, 120_000);
        for (int i = 0; i < SEED / 500; i++) {
            List<dev.floor42.domain.event.EventEnvelope> batch = new ArrayList<>();
            for (int j = 0; j < 500; j++) batch.add(TestJournals.created("S-" + i + "-" + j));
            journal.append(batch);
        }
        // The daemon always holds a connection open (the continuity anchor, the pool): the WAL files stay.
        try (Connection anchor = ((SQLiteDataSource) journal.dataSource).getConnection()) {
            anchor.createStatement()
                    .executeQuery("SELECT count(*) FROM event_journal")
                    .close();
            return run(journal, file, writerCount, wal);
        }
    }

    private double run(SqliteJournal journal, Path file, int writerCount, boolean wal) throws Exception {
        BackupStore store = new BackupStore(
                file.getParent().resolve("backups"), Clock.systemUTC(), Map.of(BackupStore.Kind.MANUAL, 2));
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger backups = new AtomicInteger();
        AtomicInteger backupFailures = new AtomicInteger();
        Thread backup = new Thread(() -> {
            while (!stop.get()) {
                try {
                    store.take(file, BackupStore.Kind.MANUAL);
                    backups.incrementAndGet();
                } catch (Exception e) {
                    backupFailures.incrementAndGet();
                }
            }
        });
        backup.start();

        long[][] nanos = new long[writerCount][APPENDS];
        AtomicInteger locked = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> firstOther =
                new java.util.concurrent.atomic.AtomicReference<>();
        List<Thread> writers = new ArrayList<>();
        for (int w = 0; w < writerCount; w++) {
            int id = w;
            Thread t = new Thread(() -> {
                for (int i = 0; i < APPENDS; i++) {
                    long start = System.nanoTime();
                    try {
                        journal.append(List.of(TestJournals.created("W-" + id + "-" + i)));
                    } catch (JournalLockedException e) {
                        locked.incrementAndGet();
                    } catch (RuntimeException e) {
                        other.incrementAndGet();
                        firstOther.compareAndSet(null, e);
                    }
                    nanos[id][i] = System.nanoTime() - start;
                }
            });
            writers.add(t);
            t.start();
        }
        for (Thread t : writers) t.join();
        stop.set(true);
        backup.join();

        long[] all = Arrays.stream(nanos).flatMapToLong(Arrays::stream).sorted().toArray();
        double p95 = all[(int) (all.length * 0.95)] / 1e6;
        System.out.printf(
                "N4 %s writers=%d appends=%d p50=%.1fms p95=%.1fms max=%.1fms backups=%d (other agents share this machine)%n",
                wal ? "wal" : "rollback",
                writerCount,
                all.length,
                all[all.length / 2] / 1e6,
                p95,
                all[all.length - 1] / 1e6,
                backups.get());

        assertThat(locked.get()).as("JOURNAL_LOCKED").isZero();
        assertThat(other.get())
                .as("other failures, first: " + describe(firstOther.get()))
                .isZero();
        assertThat(backupFailures.get()).as("backups failed").isZero();
        assertThat(backups.get()).as("the backup really looped").isGreaterThan(2);
        assertThat(journal.readAll()).hasSize(SEED + writerCount * APPENDS);
        try (Connection c = ((SQLiteDataSource) journal.dataSource).getConnection()) {
            JournalChain.Verdict v = JournalChain.verify(c, Set.of());
            assertThat(v.firstBroken()).as("hash chain").isNull();
            assertThat(v.pending()).isZero();
        }
        return p95;
    }

    /** Class and error code of the exception and of its cause, to tell a busy variant from a real failure. */
    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable x = t; x != null; x = x.getCause()) {
            sb.append(x.getClass().getName());
            if (x instanceof java.sql.SQLException q) sb.append(" errorCode=").append(q.getErrorCode());
            sb.append(": ").append(x.getMessage()).append(" <- ");
        }
        return sb.toString();
    }
}
