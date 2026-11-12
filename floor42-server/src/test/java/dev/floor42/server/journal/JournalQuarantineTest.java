package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** D14: a row this version cannot read is left out and listed, never a reason to read nothing (ADR-0042). */
class JournalQuarantineTest {

    static final String SECRET = "sk-ant-secret-0123456789";

    @TempDir
    Path dir;

    @Test
    void aCorruptRowIsLeftOutListedAndNeverQuoted() throws Exception {
        SqliteJournal journal = TestJournals.open(dir.resolve("floor42.db"));
        journal.append(List.of(TestJournals.created("M-1")));
        TestJournals.insertRaw(
                journal,
                "MissionCreated",
                "{\"mission\":\"M-2\",\"title\":\"" + SECRET,
                "2026-10-31T10:00:00Z",
                "M-2",
                1);
        journal.append(List.of(TestJournals.created("M-3")));

        Journal.Read read = journal.replay();

        assertThat(read.entries()).extracting(Journal.Entry::sequence).containsExactly(1L, 3L);
        assertThat(read.unreadable()).hasSize(1);
        Unreadable u = read.unreadable().getFirst();
        assertThat(u.sequence()).isEqualTo(2);
        assertThat(u.type()).isEqualTo("MissionCreated");
        assertThat(u.mission()).isEqualTo("M-2");
        assertThat(u.reason()).isEqualTo(Unreadable.Reason.CORRUPT);
        // Field names only, never what they held.
        assertThat(u.toString()).doesNotContain(SECRET);
        assertThat(u.detail()).contains("title");
        // Every other read leaves it out too, rather than failing.
        assertThat(journal.readAll()).hasSize(2);
        assertThat(journal.latest(10)).extracting(Journal.Entry::sequence).containsExactly(1L, 3L);
        assertThat(journal.since(1, 10)).extracting(Journal.Entry::sequence).containsExactly(3L);
    }

    @Test
    void aTypeThisVersionDoesNotKnowIsANewerVersionsNotCorruption() throws Exception {
        SqliteJournal journal = TestJournals.open(dir.resolve("floor42.db"));
        TestJournals.insertRaw(
                journal, "WorkflowStepStarted", "{\"step\":\"" + SECRET + "\"}", "2026-10-31T10:00:00Z", null, 1);
        TestJournals.insertRaw(
                journal,
                "MissionCreated",
                "{\"mission\":\"M-1\",\"title\":\"t\",\"objective\":\"o\",\"workspace\":null,\"workflow\":\"x\"}",
                "2026-10-31T10:00:00Z",
                "M-1",
                1);
        TestJournals.insertRaw(
                journal, "MissionCreated", "{not json at all " + SECRET, "2026-10-31T10:00:00Z", "M-2", 2);

        List<Unreadable> unreadable = journal.replay().unreadable();

        assertThat(unreadable).extracting(Unreadable::reason).containsOnly(Unreadable.Reason.NEWER_VERSION);
        assertThat(unreadable.get(0).detail()).isEqualTo("unknown event type");
        assertThat(unreadable.get(1).detail()).isEqualTo("unknown field workflow");
        assertThat(unreadable.get(2).detail()).startsWith("format version 2");
        assertThat(unreadable.toString()).doesNotContain(SECRET);
    }

    @Test
    void aBrokenEnvelopeIsCorruptToo() throws Exception {
        SqliteJournal journal = TestJournals.open(dir.resolve("floor42.db"));
        TestJournals.insertRaw(
                journal,
                "MissionCreated",
                "{\"mission\":\"M-1\",\"title\":\"t\",\"objective\":\"o\",\"workspace\":null}",
                "yesterday",
                "M-1",
                1);

        Unreadable u = journal.replay().unreadable().getFirst();

        assertThat(u.reason()).isEqualTo(Unreadable.Reason.CORRUPT);
        assertThat(u.detail()).isEqualTo("malformed occurred_at");
    }
}
