package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The hash chain over the journal (ADR-0079): its encoding, what it chains, and the edits it catches. */
class JournalChainTest {

    @TempDir
    Path dir;

    SqliteJournal journal() {
        return TestJournals.open(dir.resolve("floor42.db"));
    }

    JournalChain.Verdict verify(SqliteJournal j) throws SQLException {
        try (Connection c = j.dataSource.getConnection()) {
            return JournalChain.verify(c, Set.of());
        }
    }

    void sql(SqliteJournal j, String... statements) throws SQLException {
        try (Connection c = j.dataSource.getConnection();
                Statement s = c.createStatement()) {
            for (String sql : statements) s.execute(sql);
        }
    }

    String scalar(SqliteJournal j, String sql) throws SQLException {
        try (Connection c = j.dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void everyAppendedRowIsChainedToItsPredecessor() throws Exception {
        SqliteJournal j = journal();
        j.append(List.of(TestJournals.created("J-1"), TestJournals.completed("J-1")));
        j.append(List.of(TestJournals.created("J-2")));

        JournalChain.Verdict v = verify(j);
        assertThat(v.firstBroken()).isNull();
        assertThat(v.rows()).isEqualTo(3);
        assertThat(v.chained()).isEqualTo(3);
        assertThat(v.adopted()).isZero();
        assertThat(scalar(j, "SELECT prev_hash FROM journal_chain WHERE sequence = 1"))
                .isEqualTo(JournalChain.GENESIS);
        assertThat(scalar(j, "SELECT prev_hash FROM journal_chain WHERE sequence = 2"))
                .isEqualTo(scalar(j, "SELECT hash FROM journal_chain WHERE sequence = 1"));
    }

    /** The encoding of ADR-0079, built here byte by byte, independently of the production code. */
    @Test
    void theHashFollowsTheCanonicalEncodingOfTheAdr() throws Exception {
        SqliteJournal j = journal();
        sql(
                j,
                "INSERT INTO event_journal (sequence, event_id, occurred_at, origin, actor_kind, actor_id, mission_id,"
                        + " authorization_ref, causation_id, event_type, payload, format_version) VALUES"
                        + " (7, 'e-1', '2026-11-16T09:00:00Z', 'REAL', 'DIRECTOR', 'director', NULL, NULL, 'c-1',"
                        + " 'Probe', '{\"a\":\"é\"}', 1)");
        try (Connection c = j.dataSource.getConnection()) {
            JournalChain.chainPending(c, true);
        }

        String[] fields = {
            "7",
            "e-1",
            "2026-11-16T09:00:00Z",
            "REAL",
            "DIRECTOR",
            "director",
            null,
            null,
            "c-1",
            "Probe",
            "{\"a\":\"é\"}",
            "1"
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("floor42.journal.chain.v1".getBytes(StandardCharsets.US_ASCII));
        out.write(0);
        out.write(new byte[32]);
        for (String f : fields) {
            if (f == null) {
                out.write(0);
                continue;
            }
            byte[] b = f.getBytes(StandardCharsets.UTF_8);
            out.write(1);
            out.write(ByteBuffer.allocate(4).putInt(b.length).array());
            out.write(b);
        }
        String expected =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()));

        assertThat(scalar(j, "SELECT hash FROM journal_chain WHERE sequence = 7"))
                .isEqualTo(expected);
    }

    @Test
    void theChainLeavesEventPayloadsAndReplayAsTheyWere() throws Exception {
        SqliteJournal j = journal();
        j.append(List.of(TestJournals.created("J-1"), TestJournals.completed("J-1")));
        String before = scalar(j, "SELECT group_concat(payload, '|') FROM event_journal");

        try (Connection c = j.dataSource.getConnection()) {
            JournalChain.chainPending(c, false);
        }

        assertThat(scalar(j, "SELECT group_concat(payload, '|') FROM event_journal"))
                .isEqualTo(before);
        assertThat(j.readAll()).hasSize(2);
        assertThat(j.replay().unreadable()).isEmpty();
    }

    @Test
    void rowsFromABeforeChainJournalAreAdoptedAndSaidSo() throws Exception {
        SqliteJournal j = journal();
        TestJournals.insertRaw(j, "MissionCreated", "{}", "2026-11-01T10:00:00Z", "J-1", 1);
        TestJournals.insertRaw(j, "MissionCreated", "{}", "2026-11-01T10:01:00Z", "J-2", 1);

        try (Connection c = j.dataSource.getConnection()) {
            assertThat(JournalChain.chainPending(c, true)).isEqualTo(2);
            assertThat(JournalChain.chainPending(c, true)).isZero();
        }

        JournalChain.Verdict v = verify(j);
        assertThat(v.firstBroken()).isNull();
        assertThat(v.adopted()).isEqualTo(2);
    }

    @Test
    void rowsLeftAfterTheChainByAnOlderVersionAreAdoptedByTheNextAppend() throws Exception {
        SqliteJournal j = journal();
        j.append(List.of(TestJournals.created("J-1")));
        TestJournals.insertRaw(j, "MissionCreated", "{}", "2026-11-01T10:00:00Z", "J-2", 1);
        assertThat(verify(j).pending()).isEqualTo(1);

        j.append(List.of(TestJournals.created("J-3")));

        JournalChain.Verdict v = verify(j);
        assertThat(v.firstBroken()).isNull();
        assertThat(v.adopted()).isEqualTo(1);
        assertThat(v.chained()).isEqualTo(3);
    }

    @Test
    void aForgedPayloadIsFoundAtItsRow() throws Exception {
        SqliteJournal j = journal();
        j.append(List.of(TestJournals.created("J-1"), TestJournals.completed("J-1"), TestJournals.created("J-2")));
        sql(j, "DROP TRIGGER event_journal_no_update", "UPDATE event_journal SET payload = '{}' WHERE sequence = 2");

        JournalChain.Verdict v = verify(j);

        assertThat(v.firstBroken()).isEqualTo(2);
        assertThat(v.reason()).isEqualTo(JournalChain.HASH_MISMATCH);
    }

    @Test
    void aDeletedRowIsFound() throws Exception {
        SqliteJournal j = journal();
        j.append(List.of(TestJournals.created("J-1"), TestJournals.completed("J-1"), TestJournals.created("J-2")));
        sql(j, "DROP TRIGGER event_journal_no_delete", "DELETE FROM event_journal WHERE sequence = 2");

        JournalChain.Verdict v = verify(j);

        assertThat(v.firstBroken()).isEqualTo(2);
        assertThat(v.reason()).isEqualTo(JournalChain.MISSING_ROW);
    }

    @Test
    void aRowInsertedBelowTheEndOfTheChainIsFound() throws Exception {
        SqliteJournal j = journal();
        TestJournals.insertRaw(j, "MissionCreated", "{}", "2026-11-01T10:00:00Z", "J-1", 1);
        sql(
                j,
                "INSERT INTO event_journal (sequence, event_id, occurred_at, origin, actor_kind, actor_id, event_type,"
                        + " payload) VALUES (10, 'x-10', '2026-11-01T10:00:00Z', 'REAL', 'SYSTEM', 'floor42', 'T', '{}')");
        j.append(List.of(TestJournals.created("J-2")));
        sql(
                j,
                "INSERT INTO event_journal (sequence, event_id, occurred_at, origin, actor_kind, actor_id, event_type,"
                        + " payload) VALUES (5, 'x-5', '2026-11-01T10:00:00Z', 'REAL', 'SYSTEM', 'floor42', 'T', '{}')");

        JournalChain.Verdict v = verify(j);

        assertThat(v.firstBroken()).isEqualTo(5);
        assertThat(v.reason()).isEqualTo(JournalChain.UNCHAINED_ROW);
    }

    @Test
    void anAcknowledgedBreakIsPassedOverAndALaterOneIsStillFound() throws Exception {
        SqliteJournal j = journal();
        j.append(List.of(TestJournals.created("J-1"), TestJournals.completed("J-1"), TestJournals.created("J-2")));
        sql(
                j,
                "DROP TRIGGER event_journal_no_update",
                "UPDATE event_journal SET payload = '{}' WHERE sequence IN (1, 3)");

        try (Connection c = j.dataSource.getConnection()) {
            JournalChain.Verdict v = JournalChain.verify(c, Set.of(1L));
            assertThat(v.acknowledged()).isEqualTo(1);
            assertThat(v.firstBroken()).isEqualTo(3);
        }
    }
}
