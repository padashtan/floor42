package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.journal.TestJournals;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The data-folder checks of {@code floor42 doctor}: each one OK on a sound folder and WARN on its own fault. */
class DataChecksTest {

    @TempDir
    Path tmp;

    static final DataChecks.Port FREE = p -> DataChecks.PortState.FREE;

    private DataChecks.Item item(List<DataChecks.Item> items, String prefix) {
        return items.stream()
                .filter(i -> i.name().startsWith(prefix))
                .findFirst()
                .orElseThrow();
    }

    private List<DataChecks.Item> check(Path backups, long minFree, DataChecks.Port port) {
        return DataChecks.check(tmp, backups, Instant.now(), minFree, port);
    }

    @Test
    void aSoundFolderIsAllOk() throws IOException {
        TestJournals.open(tmp.resolve("floor42.db"));
        Path backups = Files.createDirectories(tmp.resolve("backups"));
        Files.writeString(backups.resolve("daily-2026-11-09.db"), "x");

        List<DataChecks.Item> items = check(backups, 1, FREE);

        assertThat(items).hasSize(4).allMatch(i -> i.state() == DataChecks.State.OK);
        assertThat(item(items, "Journal").detail()).isEqualTo("quick_check ok");
        assertThat(DataChecks.text(items)).contains("[ok]").doesNotContain("[WARN]");
    }

    @Test
    void lowFreeSpaceWarns() {
        DataChecks.Item folder = item(check(tmp, Long.MAX_VALUE, FREE), "Data folder");

        assertThat(folder.state()).isEqualTo(DataChecks.State.WARN);
        assertThat(folder.detail()).contains("only");
    }

    @Test
    void aFolderNotCreatedYetIsNotAWarning() {
        DataChecks.Item folder = DataChecks.check(tmp.resolve("new"), tmp.resolve("b"), Instant.now(), 1, FREE)
                .getFirst();

        assertThat(folder.state()).isEqualTo(DataChecks.State.OK);
    }

    @Test
    void aDamagedJournalWarns() throws IOException {
        Path db = tmp.resolve("floor42.db");
        TestJournals.open(db);
        try (RandomAccessFile f = new RandomAccessFile(db.toFile(), "rw")) {
            f.seek(4096);
            f.write(new byte[8192]); // zeroes over the pages after the first
        }

        DataChecks.Item journal = item(check(tmp, 1, FREE), "Journal");

        assertThat(journal.state()).isEqualTo(DataChecks.State.WARN);
    }

    @Test
    void whileSomethingAnswersOnThePortTheLiveJournalIsNeverOpened() throws IOException {
        Path db = tmp.resolve("floor42.db");
        TestJournals.open(db);
        Path backups = Files.createDirectories(tmp.resolve("backups"));
        Files.copy(db, backups.resolve("daily-2026-11-09.db"));
        // Damage the live file: a check of it would warn; the copy is sound.
        try (RandomAccessFile f = new RandomAccessFile(db.toFile(), "rw")) {
            f.seek(4096);
            f.write(new byte[8192]);
        }

        DataChecks.Item journal = item(check(backups, 1, p -> DataChecks.PortState.FLOOR42), "Journal");

        assertThat(journal.state()).isEqualTo(DataChecks.State.OK);
        assertThat(journal.name()).contains("newest backup copy daily-2026-11-09.db");
        assertThat(item(check(tmp.resolve("none"), 1, p -> DataChecks.PortState.BUSY), "Journal")
                        .detail())
                .contains("not checked");
    }

    @Test
    void noJournalYetIsNotAWarning() {
        assertThat(item(check(tmp, 1, FREE), "Journal").state()).isEqualTo(DataChecks.State.OK);
    }

    @Test
    void aBackupOlderThanTwoDaysWarns() throws IOException {
        Path backups = Files.createDirectories(tmp.resolve("backups"));
        Path old = Files.writeString(backups.resolve("daily-2026-11-01.db"), "x");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusSeconds(3 * 86400)));

        assertThat(item(check(backups, 1, FREE), "Last backup").state()).isEqualTo(DataChecks.State.WARN);
        assertThat(item(check(tmp.resolve("none"), 1, FREE), "Last backup").detail())
                .startsWith("no backup");
    }

    @Test
    void portStatesFreeFloor42AndAnotherProgram() throws IOException {
        assertThat(item(check(tmp, 1, FREE), "Port").state()).isEqualTo(DataChecks.State.OK);
        assertThat(item(check(tmp, 1, p -> DataChecks.PortState.FLOOR42), "Port")
                        .detail())
                .contains("Floor42 itself");
        assertThat(item(check(tmp, 1, p -> DataChecks.PortState.BUSY), "Port").state())
                .isEqualTo(DataChecks.State.WARN);
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            // Someone that is not Floor42 holds the port: no /api/status answers 200
            assertThat(DataChecks.realPort(s.getLocalPort())).isEqualTo(DataChecks.PortState.BUSY);
        }
    }
}
