package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.server.config.DataFolders;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Another process holding the journal (a sqlite3 shell, a database browser, a backup tool): SQLite lets it, and Floor42
 * does not try to prevent it. An append then fails after the busy timeout, and says why instead of a bare "cannot
 * append"; the next append after the lock is gone works again.
 */
class JournalLockTest {

    @TempDir
    Path dir;

    @Test
    void aForeignReaderHoldingTheJournalMakesAnAppendFailWithAClearErrorUntilItLetsGo() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.open(file, 200);
        journal.append(List.of(TestJournals.created("M-1")));

        try (Connection foreign = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement s = foreign.createStatement()) {
            foreign.setAutoCommit(false);
            // A read inside an open transaction keeps SQLite's SHARED lock: a writer cannot commit until it ends.
            try (ResultSet rs = s.executeQuery("SELECT count(*) FROM event_journal")) {
                rs.next();
            }

            assertThatThrownBy(() -> journal.append(List.of(TestJournals.created("M-2"))))
                    .isInstanceOf(JournalLockedException.class)
                    .hasMessageContaining("locked by another process")
                    .hasMessageContaining(file.getFileName().toString());
            assertThat(journal.locked()).isPresent();
            foreign.rollback();
        }

        journal.append(List.of(TestJournals.created("M-3")));
        assertThat(journal.locked()).as("cleared by the next append").isEmpty();
        assertThat(BackupStore.events(file)).isEqualTo(2);
    }

    @Test
    void aForeignWriterHoldingTheJournalIsReportedTheSameWay() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = TestJournals.open(file, 200);
        try (Connection foreign = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement s = foreign.createStatement()) {
            s.execute("BEGIN EXCLUSIVE");
            assertThatThrownBy(() -> journal.append(List.of(TestJournals.created("M-1"))))
                    .isInstanceOf(JournalLockedException.class);
            s.execute("ROLLBACK");
        }
        journal.append(List.of(TestJournals.created("M-1")));
    }
}
