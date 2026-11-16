package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Signed checkpoints, the startup check and the refusal to write on a broken chain (ADR-0079). */
class JournalIntegrityTest {

    @TempDir
    Path dir;

    /** A daemon's start: the journal, then the chain's check with a checkpoint every {@code every} rows. */
    SqliteJournal start(int every) {
        SqliteJournal j = TestJournals.open(dir.resolve("floor42.db"));
        JournalIntegrity i = new JournalIntegrity(j.dataSource, dir, every);
        i.start();
        j.integrity = i;
        return j;
    }

    void sql(SqliteJournal j, String... statements) throws SQLException {
        try (Connection c = j.dataSource.getConnection();
                Statement s = c.createStatement()) {
            for (String sql : statements) s.execute(sql);
        }
    }

    void appendRows(SqliteJournal j, int n) {
        for (int i = 0; i < n; i++) j.append(List.of(TestJournals.created("J-" + i)));
    }

    @Test
    void aCheckpointIsSignedEveryNRowsAndVerifies() {
        SqliteJournal j = start(2);
        appendRows(j, 5);

        JournalIntegrity.Status s = j.integrity.verifyAll();

        assertThat(s.status()).isEqualTo(JournalIntegrity.OK);
        assertThat(s.checkpoints()).isEqualTo(2);
        assertThat(s.verified()).isEqualTo(2);
        assertThat(s.last().sequence()).isEqualTo(4);
        assertThat(s.writesRefused()).isFalse();
    }

    @Test
    void theShutdownCheckpointCoversTheEnd() {
        SqliteJournal j = start(1000);
        appendRows(j, 3);

        j.integrity.checkpoint("SHUTDOWN");

        JournalIntegrity.Status s = j.integrity.verifyAll();
        assertThat(s.last().sequence()).isEqualTo(3);
        assertThat(s.last().kind()).isEqualTo("SHUTDOWN");
        assertThat(s.verified()).isEqualTo(1);
    }

    @Test
    void aJournalFromBeforeTheChainIsAdoptedWithASignedAnchor() throws Exception {
        SqliteJournal j = TestJournals.open(dir.resolve("floor42.db"));
        TestJournals.insertRaw(j, "MissionCreated", "{}", "2026-11-01T10:00:00Z", "J-1", 1);
        TestJournals.insertRaw(j, "MissionCreated", "{}", "2026-11-01T10:01:00Z", "J-2", 1);
        JournalIntegrity i = new JournalIntegrity(j.dataSource, dir, 1000);

        i.start();

        JournalIntegrity.Status s = i.status();
        assertThat(s.status()).isEqualTo(JournalIntegrity.OK);
        assertThat(s.adopted()).isEqualTo(2);
        assertThat(s.last().kind()).isEqualTo("ADOPTION");
        assertThat(s.verified()).isEqualTo(1);
    }

    @Test
    void aForgedRowRefusesAppendsUntilTheDirectorAcknowledges() throws Exception {
        SqliteJournal j = start(1000);
        appendRows(j, 3);
        sql(
                j,
                "DROP TRIGGER event_journal_no_update",
                "UPDATE event_journal SET actor_id = 'someone' WHERE sequence = 2");

        JournalIntegrity.Status broken = j.integrity.verifyAll();
        assertThat(broken.status()).isEqualTo(JournalIntegrity.BROKEN);
        assertThat(broken.brokenSequence()).isEqualTo(2);
        assertThat(broken.writesRefused()).isTrue();
        assertThatThrownBy(() -> j.append(List.of(TestJournals.created("J-9"))))
                .isInstanceOf(JournalChainBrokenException.class);

        JournalIntegrity.Status seen = j.integrity.acknowledge();
        assertThat(seen.status()).isEqualTo(JournalIntegrity.ACKNOWLEDGED);
        assertThat(seen.writesRefused()).isFalse();
        j.append(List.of(TestJournals.created("J-9")));
        assertThat(j.integrity.verifyAll().status()).isEqualTo(JournalIntegrity.ACKNOWLEDGED);
    }

    @Test
    void aCutTailIsFoundByTheCheckpointThatSawIt() throws Exception {
        SqliteJournal j = start(2);
        appendRows(j, 4);
        sql(
                j,
                "DROP TRIGGER event_journal_no_delete",
                "DROP TRIGGER journal_chain_no_delete",
                "DELETE FROM event_journal WHERE sequence > 2",
                "DELETE FROM journal_chain WHERE sequence > 2");

        JournalIntegrity.Status s = j.integrity.verifyAll();

        assertThat(s.status()).isEqualTo(JournalIntegrity.BROKEN);
        assertThat(s.reason()).isEqualTo(JournalIntegrity.CHECKPOINT_MISMATCH);
        assertThat(s.brokenSequence()).isEqualTo(4);
    }

    @Test
    void aTamperedCheckpointFailsItsSignature() throws Exception {
        SqliteJournal j = start(2);
        appendRows(j, 2);
        sql(
                j,
                "DROP TRIGGER journal_checkpoint_no_update",
                "UPDATE journal_checkpoint SET signed_at = '2020-01-01T00:00:00Z'");

        JournalIntegrity.Status s = j.integrity.verifyAll();

        assertThat(s.reason()).isEqualTo(JournalIntegrity.CHECKPOINT_SIGNATURE);
        assertThat(s.invalid()).isEqualTo(1);
    }

    /** A missing key beside checkpoints is a loss, never "another key": no new key is made. */
    @Test
    void aDeletedKeyBesideCheckpointsIsBrokenAndNoNewKeyIsMade() throws Exception {
        SqliteJournal j = start(2);
        appendRows(j, 2);
        Files.delete(dir.resolve(JournalSigningKey.FILE));

        JournalIntegrity other = new JournalIntegrity(j.dataSource, dir, 1000);
        other.start();

        assertThat(other.status().status()).isEqualTo(JournalIntegrity.BROKEN);
        assertThat(other.status().reason()).isEqualTo(JournalIntegrity.CHECKPOINT_KEY);
        assertThat(other.status().keyProblem()).isEqualTo("KEY_MISSING");
        assertThat(Files.exists(dir.resolve(JournalSigningKey.FILE))).isFalse();
    }

    /** What the SQL a forger can run cannot do: the keyless links are recomputed here, as he would. */
    void forgeFrom(SqliteJournal j, long from, String tamper) throws Exception {
        sql(
                j,
                "DROP TRIGGER event_journal_no_update",
                "DROP TRIGGER journal_chain_no_update",
                "DROP TRIGGER journal_checkpoint_no_delete",
                tamper);
        try (Connection c = j.dataSource.getConnection()) {
            String prev = JournalChain.GENESIS;
            if (from > 1) {
                try (var ps = c.prepareStatement("SELECT hash FROM journal_chain WHERE sequence = ?")) {
                    ps.setLong(1, from - 1);
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        prev = rs.getString(1);
                    }
                }
            }
            java.util.Map<Long, String[]> links = new java.util.LinkedHashMap<>();
            try (var ps = c.prepareStatement("SELECT sequence, event_id, occurred_at, origin, actor_kind, actor_id,"
                    + " mission_id, authorization_ref, causation_id, event_type, payload, format_version"
                    + " FROM event_journal WHERE sequence >= ? ORDER BY sequence"); ) {
                ps.setLong(1, from);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String hash = JournalChain.hash(prev, rs);
                        links.put(rs.getLong(1), new String[] {prev, hash});
                        prev = hash;
                    }
                }
            }
            for (var e : links.entrySet()) {
                try (var ps =
                        c.prepareStatement("UPDATE journal_chain SET prev_hash = ?, hash = ? WHERE sequence = ?")) {
                    ps.setString(1, e.getValue()[0]);
                    ps.setString(2, e.getValue()[1]);
                    ps.setLong(3, e.getKey());
                    ps.executeUpdate();
                }
            }
        }
        sql(j, "DELETE FROM journal_checkpoint WHERE sequence >= " + from);
    }

    /** Drop the triggers, rewrite a row, recompute the keyless links, delete the checkpoints: the database alone is fooled. */
    @Test
    void aForgeryWithoutTheKeyIsFoundByTheCheckpointKeptOutsideTheDatabase() throws Exception {
        SqliteJournal j = start(2);
        appendRows(j, 4);
        forgeFrom(j, 2, "UPDATE event_journal SET actor_id = 'someone' WHERE sequence = 2");

        JournalIntegrity restarted = new JournalIntegrity(j.dataSource, dir, 1000);
        restarted.start();

        assertThat(restarted.status().status()).isEqualTo(JournalIntegrity.BROKEN);
        assertThat(restarted.status().reason()).isEqualTo(JournalIntegrity.CHECKPOINT_ANCHOR);
        assertThat(restarted.status().writesRefused()).isTrue();
    }

    @Test
    void aRowAnotherProgramAppendedWhileTheDaemonRunsIsABreakNotAnAdoption() throws Exception {
        SqliteJournal j = start(1000);
        appendRows(j, 1);
        TestJournals.insertRaw(j, "MissionCreated", "{}", "2026-11-01T10:00:00Z", "J-x", 1);

        assertThatThrownBy(() -> j.append(List.of(TestJournals.created("J-9"))))
                .isInstanceOf(JournalChainBrokenException.class);

        JournalIntegrity.Status s = j.integrity.status();
        assertThat(s.status()).isEqualTo(JournalIntegrity.BROKEN);
        assertThat(s.reason()).isEqualTo(JournalIntegrity.UNCHAINED_TAIL);
        assertThat(s.adopted()).isZero();
        // Only the director's acknowledgement lets the row in, marked adopted.
        j.integrity.acknowledge();
        j.append(List.of(TestJournals.created("J-9")));
        assertThat(j.integrity.verifyAll().adopted()).isEqualTo(1);
    }

    @Test
    void aStoredHashThatIsNotAHashIsABreakAtItsRowNotACrash() throws Exception {
        SqliteJournal j = start(1000);
        appendRows(j, 2);
        sql(j, "DROP TRIGGER journal_chain_no_update", "UPDATE journal_chain SET hash = 'zz' WHERE sequence = 2");

        JournalIntegrity restarted = new JournalIntegrity(j.dataSource, dir, 1000);
        restarted.start();
        j.integrity = restarted;

        assertThat(restarted.status().status()).isEqualTo(JournalIntegrity.BROKEN);
        assertThat(restarted.status().brokenSequence()).isEqualTo(2);
        assertThatThrownBy(() -> j.append(List.of(TestJournals.created("J-9"))))
                .isInstanceOf(JournalChainBrokenException.class);
    }

    @Test
    void anAppendOnAMalformedHeadAnswersBrokenAndSaysSo() throws Exception {
        SqliteJournal j = start(1000);
        appendRows(j, 1);
        sql(j, "DROP TRIGGER journal_chain_no_update", "UPDATE journal_chain SET hash = 'zz' WHERE sequence = 1");

        assertThatThrownBy(() -> j.append(List.of(TestJournals.created("J-9"))))
                .isInstanceOf(JournalChainBrokenException.class);
        assertThat(j.integrity.status().status()).isEqualTo(JournalIntegrity.BROKEN);
    }

    /** The acknowledgement covers what the director saw: another edit of the same row is a new break. */
    @Test
    void anAcknowledgementCoversThatStateOnly() throws Exception {
        SqliteJournal j = start(1000);
        appendRows(j, 3);
        sql(
                j,
                "DROP TRIGGER event_journal_no_update",
                "UPDATE event_journal SET actor_id = 'first' WHERE sequence = 2");
        assertThat(j.integrity.verifyAll().status()).isEqualTo(JournalIntegrity.BROKEN);
        j.integrity.acknowledge();
        assertThat(j.integrity.verifyAll().status()).isEqualTo(JournalIntegrity.ACKNOWLEDGED);

        sql(j, "UPDATE event_journal SET actor_id = 'second' WHERE sequence = 2");

        JournalIntegrity.Status s = j.integrity.verifyAll();
        assertThat(s.status()).isEqualTo(JournalIntegrity.BROKEN);
        assertThat(s.brokenSequence()).isEqualTo(2);
        assertThat(s.writesRefused()).isTrue();
    }

    @Test
    void theKeyIsKeptAcrossStartsAndAMalformedFileIsNeverReplaced() throws Exception {
        JournalSigningKey first = JournalSigningKey.loadOrCreate(dir);
        assertThat(JournalSigningKey.loadOrCreate(dir).keyId()).isEqualTo(first.keyId());
        byte[] msg = "m".getBytes();
        assertThat(first.verify(msg, first.sign(msg))).isTrue();
        assertThat(first.verify("n".getBytes(), first.sign(msg))).isFalse();

        Files.writeString(dir.resolve(JournalSigningKey.FILE), "garbage");
        assertThatThrownBy(() -> JournalSigningKey.loadOrCreate(dir)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(dir.resolve(JournalSigningKey.FILE))).isEqualTo("garbage");
    }
}
