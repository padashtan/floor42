package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.config.DataFolders;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/**
 * A process killed in the middle of an append (v1.4, node N4). The closest deterministic equivalent of {@code kill -9}:
 * a child JVM appends in a loop and is stopped by {@code Process.destroyForcibly()} (TerminateProcess on Windows,
 * SIGKILL elsewhere) after a random pause past its first acknowledged appends, so the kill lands in an arbitrary
 * place of an append. Three kills in a row on the same journal.
 *
 * <p>This is a process kill, not a power cut: the operating system still holds and writes out what the dead process
 * had handed to it, so {@code synchronous=FULL} is not exercised here. Only crash recovery of the WAL is.
 */
@Tag("slow")
class JournalKillTest {

    @TempDir
    Path dir;

    /** The child: appends one event at a time and says so once each is stored. */
    public static final class Child {
        public static void main(String[] args) {
            SqliteJournal journal = TestJournals.openWal(Path.of(args[0]));
            for (int i = 0; ; i++) {
                journal.append(List.of(TestJournals.created("K-" + System.nanoTime() + "-" + i)));
                System.out.println("ACK " + (i + 1));
                System.out.flush();
            }
        }
    }

    @Test
    void aKilledAppendLeavesAReadableJournalWithAnUnbrokenChain() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        TestJournals.openWal(file); // migrated before the first child starts
        Random random = new Random(42);
        long acknowledged = 0;
        for (int round = 0; round < 3; round++) {
            AtomicInteger acks = new AtomicInteger();
            ProcessBuilder pb = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    Child.class.getName(),
                    file.toString());
            pb.redirectErrorStream(true);
            Process child = pb.start();
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                    for (String line; (line = in.readLine()) != null; ) {
                        if (line.startsWith("ACK ")) acks.set(Integer.parseInt(line.substring(4)));
                    }
                } catch (java.io.IOException ignored) {
                    // the child was killed
                }
            });
            reader.start();
            long deadline = System.currentTimeMillis() + 60_000;
            while (acks.get() < 100 && child.isAlive() && System.currentTimeMillis() < deadline) Thread.sleep(5);
            assertThat(acks.get()).as("the child appended").isGreaterThanOrEqualTo(100);
            Thread.sleep(random.nextInt(25));
            child.destroyForcibly().waitFor(); // only this child, by its own handle
            reader.join();
            acknowledged += acks.get();

            // The daemon's own first step after a crash: the startup copy, read-only, on the hot WAL (before the
            // journal opens).
            BackupStore.Backup startup = new BackupStore(
                            dir.resolve("backups"),
                            java.time.Clock.systemUTC(),
                            java.util.Map.of(BackupStore.Kind.STARTUP, 5))
                    .take(file, BackupStore.Kind.STARTUP);
            assertThat(startup.events())
                    .as("the startup copy reads the hot WAL")
                    .isBetween(acknowledged, acknowledged + 1);

            SqliteJournal reopened = TestJournals.openWal(file);
            assertThat(reopened.readAll().size())
                    .as("every acknowledged append is there; the one in flight may be")
                    .isBetween((int) acknowledged, (int) acknowledged + 1);
            acknowledged = reopened.readAll().size();
            try (Connection c = ((SQLiteDataSource) reopened.dataSource).getConnection();
                    Statement s = c.createStatement()) {
                try (ResultSet rs = s.executeQuery("PRAGMA integrity_check")) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("ok");
                }
                JournalChain.Verdict v = JournalChain.verify(c, Set.of());
                assertThat(v.firstBroken()).as("hash chain after kill " + round).isNull();
                assertThat(v.pending()).as("no row without its link").isZero();
                assertThat(v.rows()).isEqualTo(acknowledged);
            }
        }
    }
}
