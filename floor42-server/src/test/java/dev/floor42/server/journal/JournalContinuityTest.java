package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.config.DataFolders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** The journal's continuity between two runs (ADR-0044, amendments 2 and 3): detected, never refused. */
class JournalContinuityTest {

    @TempDir
    Path dir;

    JournalBackups backups() {
        JournalBackups b = new JournalBackups();
        b.dataDir = dir;
        b.enabled = true;
        b.configuredDir = Optional.of(dir.resolve("backups"));
        b.startupKeep = 5;
        b.dailyKeep = 7;
        b.manualKeep = 5;
        return b;
    }

    /** A daemon's start on this test's data folder: the journal, its copies, the continuity check. */
    SqliteJournal start(Path file) {
        SqliteJournal j = TestJournals.open(file);
        j.dataDir = dir;
        j.backups = backups();
        j.checkContinuity();
        return j;
    }

    /** Its orderly stop: the anchor closes, so a test may replace the file after it (Windows refuses before). */
    static void stop(SqliteJournal j) {
        j.restoreAtShutdown(null);
    }

    /**
     * Counter-check of 1.8.0: an older copy moved over floor42.db, then a restart, replayed the old journal without a
     * word. Now the next start finds fewer events than the last run saw, and says so until the director acknowledges.
     */
    @Test
    void aJournalShorterThanTheLastRunSawIsReportedUntilAcknowledged() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal first = start(file);
        first.append(List.of(TestJournals.created("M-1")));
        String old = first.backups.take(BackupStore.Kind.MANUAL).orElseThrow().name();
        first.append(List.of(TestJournals.created("M-2"), TestJournals.created("M-3")));
        stop(first);
        assertThat(JournalContinuity.read(dir).orElseThrow().events()).isEqualTo(3);

        Files.copy(dir.resolve("backups").resolve(old), file, StandardCopyOption.REPLACE_EXISTING);
        SqliteJournal next = start(file);

        JournalContinuity.Break b = next.continuityBreak().orElseThrow();
        assertThat(b.reason()).isEqualTo("SHORTER");
        assertThat(b.recorded().events()).isEqualTo(3);
        assertThat(b.foundEvents()).isEqualTo(1);
        next.append(List.of(TestJournals.created("M-4")));
        stop(next);
        SqliteJournal again = start(file);
        assertThat(again.continuityBreak())
                .as("held until the director acknowledges")
                .isPresent();

        again.acknowledgeContinuity();
        assertThat(again.continuityBreak()).isEmpty();
        stop(again);
        SqliteJournal after = start(file);
        assertThat(after.continuityBreak()).isEmpty();
        stop(after);
    }

    @Test
    void aJournalWhoseLastSeenRowChangedIsReportedAsDiverged() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal journal = start(file);
        journal.append(List.of(TestJournals.created("M-1"), TestJournals.created("M-2")));
        stop(journal);

        Path other = dir.resolve("other.db");
        TestJournals.open(other)
                .append(List.of(TestJournals.created("X-1"), TestJournals.created("X-2"), TestJournals.created("X-3")));
        Files.copy(other, file, StandardCopyOption.REPLACE_EXISTING);
        SqliteJournal next = start(file);

        assertThat(next.continuityBreak().orElseThrow().reason()).isEqualTo("DIVERGED");
        stop(next);
    }

    /**
     * Counter-check of amendment 2: after a swap, Agroal closes the idle connections (about 5 minutes) and the next one
     * opens the forged file. Every measure went through the pool: the stop kept the forged file as displaced-*,
     * rewrote the record from it (0 events), and the next start said nothing. Simulated here by pointing every new
     * connection of the journal at the forged file and giving the journal's name another identity, as Linux does.
     */
    @Test
    void aSwapFollowedByConnectionsReopenedOnTheForgedFileIsStillCaught() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        AtomicReference<Object> inode = new AtomicReference<>("inode-1");
        SqliteJournal daemon = TestJournals.open(file);
        daemon.dataDir = dir;
        daemon.backups = backups();
        daemon.fileIdentity = p -> inode.get();
        daemon.markEveryMillis = 0;
        daemon.checkContinuity();
        daemon.append(List.of(TestJournals.created("M-1"), TestJournals.created("M-2"), TestJournals.created("M-3")));

        Path forged = dir.resolve("forged.db");
        SqliteJournal forger = TestJournals.open(forged);
        inode.set("inode-2");
        SQLiteDataSource reopened = new SQLiteDataSource();
        reopened.setUrl("jdbc:sqlite:" + forged + "?transaction_mode=IMMEDIATE");
        daemon.dataSource = reopened;
        daemon.append(List.of(TestJournals.created("M-late")));
        assertThat(forger.readAll())
                .as("the reopened pool writes the forged file")
                .hasSize(1);
        assertThat(JournalContinuity.read(dir).orElseThrow().events())
                .as("the record follows the file the daemon opened")
                .isEqualTo(3);
        stop(daemon);

        JournalContinuity.Mark mark = JournalContinuity.read(dir).orElseThrow();
        assertThat(mark.events()).as("never rewritten from the forged file").isEqualTo(3);
        assertThat(mark.displaced()).startsWith("displaced-");
        assertThat(BackupStore.events(dir.resolve("backups").resolve(mark.displaced())))
                .as("the displaced copy is the journal the daemon opened")
                .isEqualTo(3);

        Files.copy(forged, file, StandardCopyOption.REPLACE_EXISTING);
        SqliteJournal next = start(file);
        JournalContinuity.Break b = next.continuityBreak().orElseThrow();
        assertThat(b.reason()).isEqualTo("SHORTER");
        assertThat(b.foundEvents()).isEqualTo(1);
        assertThat(b.recorded().displaced()).isEqualTo(mark.displaced());
        stop(next);
    }

    /**
     * The same with Linux's own inodes: {@code mv -f} of another journal over floor42.db while the daemon runs, then a
     * connection opened afresh (the test's data source opens one per use, as the pool does after an idle removal).
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void onLinuxAJournalMovedOverTheFileIsCaughtThroughItsInode() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal daemon = TestJournals.open(file);
        daemon.dataDir = dir;
        daemon.backups = backups();
        daemon.markEveryMillis = 0;
        daemon.checkContinuity();
        daemon.append(List.of(TestJournals.created("M-1"), TestJournals.created("M-2"), TestJournals.created("M-3")));

        Path forged = dir.resolve("forged.db");
        TestJournals.open(forged);
        Files.move(forged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        daemon.append(List.of(TestJournals.created("M-late")));
        stop(daemon);

        JournalContinuity.Mark mark = JournalContinuity.read(dir).orElseThrow();
        assertThat(mark.events()).isEqualTo(3);
        assertThat(BackupStore.events(dir.resolve("backups").resolve(mark.displaced())))
                .isEqualTo(3);
        SqliteJournal next = start(file);
        assertThat(next.continuityBreak().orElseThrow().reason()).isEqualTo("SHORTER");
        stop(next);
    }

    /** A restore the director prepared is written while the anchor holds the file, and starts a new line. */
    @Test
    void aPreparedRestoreStartsANewLineAndRaisesNothing() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal daemon = start(file);
        daemon.append(List.of(TestJournals.created("M-1"), TestJournals.created("M-2")));
        String older =
                daemon.backups.take(BackupStore.Kind.MANUAL).orElseThrow().name();
        daemon.append(List.of(TestJournals.created("M-3")));
        daemon.backups.prepareRestore(older);
        stop(daemon);

        assertThat(daemon.backups.state().lastRestore().restored()).isTrue();
        assertThat(BackupStore.events(file)).isEqualTo(2);
        assertThat(JournalContinuity.read(dir).orElseThrow().events()).isEqualTo(2);
        SqliteJournal next = start(file);
        assertThat(next.continuityBreak()).isEmpty();
        stop(next);
    }

    /** Counter-check of amendment 2: a record deleted or garbled raised nothing; it was taken for a fresh folder. */
    @Test
    void aMissingRecordBesideAJournalWithEventsIsReportedAndAFreshFolderIsNot() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal fresh = start(file);
        assertThat(fresh.continuityBreak()).as("a fresh data folder").isEmpty();
        fresh.append(List.of(TestJournals.created("M-1"), TestJournals.created("M-2")));
        stop(fresh);

        Files.delete(dir.resolve(JournalContinuity.FILE));
        SqliteJournal next = start(file);
        JournalContinuity.Break b = next.continuityBreak().orElseThrow();
        assertThat(b.reason()).isEqualTo("MISSING");
        assertThat(b.recorded()).isNull();
        assertThat(b.foundEvents()).isEqualTo(2);
        stop(next);
        assertThat(dir.resolve(JournalContinuity.FILE))
                .as("held: nothing written")
                .doesNotExist();
        SqliteJournal again = start(file);
        assertThat(again.continuityBreak().orElseThrow().reason()).isEqualTo("MISSING");
        again.acknowledgeContinuity();
        stop(again);
        SqliteJournal after = start(file);
        assertThat(after.continuityBreak()).isEmpty();
        stop(after);
    }

    /**
     * Counter-check 2: the first start after an upgrade has no record, which is not a rollback. It is shown as a
     * notice (FIRST_CHECK), still held until acknowledged; a record deleted later is the alarm (MISSING).
     */
    @Test
    void theFirstCheckAfterAnUpgradeIsANoticeAndALaterMissingRecordIsTheAlarm() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal old = start(file);
        old.append(List.of(TestJournals.created("M-1")));
        stop(old);
        Files.delete(dir.resolve(JournalContinuity.FILE));
        try (var c = old.dataSource.getConnection();
                var s = c.createStatement()) {
            s.executeUpdate("UPDATE continuity_epoch SET checked = 0");
        }

        SqliteJournal upgraded = start(file);
        assertThat(upgraded.continuityBreak().orElseThrow().reason()).isEqualTo(JournalContinuity.FIRST_CHECK);
        stop(upgraded);
        SqliteJournal again = start(file);
        assertThat(again.continuityBreak().orElseThrow().reason())
                .as("still held until acknowledged")
                .isEqualTo(JournalContinuity.FIRST_CHECK);
        again.acknowledgeContinuity();
        stop(again);

        Files.delete(dir.resolve(JournalContinuity.FILE));
        SqliteJournal later = start(file);
        assertThat(later.continuityBreak().orElseThrow().reason()).isEqualTo("MISSING");
        stop(later);
    }

    /** Counter-check 4: a check that could not be made is a banner, not a silent absence of detection. */
    @Test
    void aCheckThatCannotBeMadeRaisesAnAnchorFailedBanner() {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal j = TestJournals.open(file);
        j.dataDir = dir.resolve("no-such-folder");
        j.backups = backups();
        j.checkContinuity();

        assertThat(j.continuityBreak().orElseThrow().reason()).isEqualTo(JournalContinuity.ANCHOR_FAILED);
        j.acknowledgeContinuity();
        assertThat(j.continuityBreak()).isEmpty();
    }

    /** The anchor opens but measuring the journal fails (no table to count): still a banner, never silence. */
    @Test
    void aMeasureThatFailsRaisesTheAnchorFailedBanner() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal j = TestJournals.open(file);
        j.dataDir = dir;
        j.backups = backups();
        try (var c = j.dataSource.getConnection();
                var s = c.createStatement()) {
            s.executeUpdate("DROP TABLE event_journal");
        }
        j.checkContinuity();

        assertThat(j.continuityBreak().orElseThrow().reason()).isEqualTo(JournalContinuity.ANCHOR_FAILED);
        assertThat(dir.resolve(JournalContinuity.FILE)).as("nothing recorded").doesNotExist();
        stop(j);
    }

    /** An unreadable epoch flag must give the red alarm, never the amber notice (the safe side). */
    @Test
    void aMissingEpochTableGivesTheRedAlarmNotTheFirstCheckNotice() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal old = start(file);
        old.append(List.of(TestJournals.created("M-1")));
        stop(old);
        Files.delete(dir.resolve(JournalContinuity.FILE));
        try (var c = old.dataSource.getConnection();
                var s = c.createStatement()) {
            s.executeUpdate("DROP TABLE continuity_epoch");
        }

        SqliteJournal next = start(file);
        assertThat(next.continuityBreak().orElseThrow().reason()).isEqualTo("MISSING");
        stop(next);
    }

    /** The check starts but fails once the anchor is held: still a banner, and acknowledging writes no record. */
    @Test
    void aCheckThatFailsAfterTheAnchorOpenedRaisesTheBannerAndAcknowledgingWritesNothing() throws Exception {
        Files.createDirectories(dir.resolve(JournalContinuity.FILE + ".partial").resolve("blocker"));
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal j = start(file);

        assertThat(j.continuityBreak().orElseThrow().reason()).isEqualTo(JournalContinuity.ANCHOR_FAILED);
        j.acknowledgeContinuity();
        assertThat(j.continuityBreak()).isEmpty();
        assertThat(dir.resolve(JournalContinuity.FILE)).as("nothing recorded").doesNotExist();
        stop(j);
    }

    /** The record is never rewritten from a journal that does not continue the last one written; the run says so. */
    @Test
    void aJournalRewrittenInPlaceWhileTheDaemonRunsIsReportedAndNotRecorded() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        SqliteJournal daemon = TestJournals.open(file);
        daemon.dataDir = dir;
        daemon.backups = backups();
        daemon.markEveryMillis = 0;
        daemon.checkContinuity();
        daemon.append(List.of(TestJournals.created("M-1"), TestJournals.created("M-2"), TestJournals.created("M-3")));

        SQLiteDataSource forger = new SQLiteDataSource();
        forger.setUrl("jdbc:sqlite:" + file);
        try (var c = forger.getConnection();
                var s = c.createStatement()) {
            List<String> triggers = new java.util.ArrayList<>();
            try (var rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'trigger'")) {
                while (rs.next()) triggers.add(rs.getString(1));
            }
            for (String t : triggers) s.executeUpdate("DROP TRIGGER \"" + t + "\"");
            s.executeUpdate("DELETE FROM event_journal WHERE sequence > 1");
        }
        daemon.append(List.of(TestJournals.created("M-4")));

        assertThat(daemon.continuityBreak().orElseThrow().recorded().events())
                .as("reported while the daemon runs")
                .isEqualTo(3);
        assertThat(JournalContinuity.read(dir).orElseThrow().events()).isEqualTo(3);
        stop(daemon);
        assertThat(JournalContinuity.read(dir).orElseThrow().events())
                .as("held at the stop too")
                .isEqualTo(3);
    }

    @Test
    void anUnreadableRecordIsReported() throws Exception {
        Path file = dir.resolve(DataFolders.JOURNAL);
        stop(start(file));
        Files.writeString(dir.resolve(JournalContinuity.FILE), "events=many\n");

        SqliteJournal next = start(file);

        assertThat(next.continuityBreak().orElseThrow().reason()).isEqualTo("UNREADABLE");
        assertThat(Files.readString(dir.resolve(JournalContinuity.FILE)))
                .as("kept as found until the director has seen it")
                .isEqualTo("events=many\n");
        stop(next);
    }
}
