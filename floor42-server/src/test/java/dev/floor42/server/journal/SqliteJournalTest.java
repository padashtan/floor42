package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.Actor;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SqliteJournalTest {

    @Inject
    Journal journal;

    @Inject
    DataSource dataSource;

    static EventEnvelope event(String id, String mission) {
        return new EventEnvelope(
                id,
                Instant.parse("2026-10-20T10:00:00Z"),
                Origin.REAL,
                Actor.director(),
                new MissionId(mission),
                "policy:" + mission,
                "toolu_1",
                new MissionEvent.MissionCreated(new MissionId(mission), "title", "objective", "C:/repo"));
    }

    @Test
    void storesEventsWithTheirProvenanceInOrder() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        journal.append(List.of(event(a, "J-1"), event(b, "J-2")));

        List<Journal.Entry> latest = journal.latest(2);

        assertThat(latest).extracting(e -> e.envelope().eventId()).containsExactly(a, b);
        assertThat(latest.getFirst().sequence()).isLessThan(latest.getLast().sequence());
        assertThat(latest.getLast().envelope()).isEqualTo(event(b, "J-2"));
    }

    @Test
    void readsFromASequenceOnwardsOldestFirst() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String c = UUID.randomUUID().toString();
        journal.append(List.of(event(a, "J-5"), event(b, "J-5"), event(c, "J-5")));
        // Other tests' background work may append too: only these three are checked.
        long first = journal.latest(50).stream()
                .filter(e -> e.envelope().eventId().equals(a))
                .findFirst()
                .orElseThrow()
                .sequence();

        List<String> after = journal.since(first, 100).stream()
                .map(e -> e.envelope().eventId())
                .filter(id -> id.equals(a) || id.equals(b) || id.equals(c))
                .toList();
        assertThat(after).containsExactly(b, c);
        List<Journal.Entry> one = journal.since(first, 1);
        assertThat(one).hasSize(1);
        assertThat(one.getFirst().sequence()).isEqualTo(first + 1);
    }

    @Test
    void aCommandIsStoredCompletelyOrNotAtAll() {
        int before = journal.readAll().size();
        String dup = UUID.randomUUID().toString();

        assertThatThrownBy(() -> journal.append(
                        List.of(event(UUID.randomUUID().toString(), "J-3"), event(dup, "J-3"), event(dup, "J-3"))))
                .isInstanceOf(JournalException.class);

        assertThat(journal.readAll()).hasSize(before);
    }

    /** The daemon's own pool: transactions take the write lock at once, and SQLite never loads an extension. */
    @Test
    void theJournalsConnectionsBeginImmediateAndRefuseExtensions() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.unwrap(org.sqlite.SQLiteConnection.class)
                            .getConnectionConfig()
                            .getTransactionMode())
                    .isEqualTo(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);
            try (Statement s = c.createStatement()) {
                assertThatThrownBy(() -> s.executeQuery("SELECT load_extension('floor42-no-such-extension')"))
                        .hasMessageContaining("not authorized");
            }
        }
    }

    @Test
    void theDatabaseRefusesToRewriteHistory() throws SQLException {
        journal.append(List.of(event(UUID.randomUUID().toString(), "J-4")));
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("UPDATE event_journal SET origin = 'REAL'"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> s.executeUpdate("DELETE FROM event_journal"))
                    .hasMessageContaining("append-only");
        }
    }
}
