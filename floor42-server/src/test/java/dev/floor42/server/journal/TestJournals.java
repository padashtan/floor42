package dev.floor42.server.journal;

import dev.floor42.domain.Actor;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteDataSource;

/** Journals of their own, in a test's temporary folder: never the daemon's, never ~/.floor42. */
public final class TestJournals {

    private TestJournals() {}

    /** A migrated, empty journal file, and the journal that reads and writes it. */
    public static SqliteJournal open(Path file) {
        return open(file, 3000);
    }

    /** As the daemon opens it (transactions IMMEDIATE), waiting this long for another process's lock. */
    public static SqliteJournal open(Path file, int busyTimeoutMillis) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + file + "?transaction_mode=IMMEDIATE&busy_timeout=" + busyTimeoutMillis);
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        SqliteJournal journal = new SqliteJournal();
        journal.dataSource = ds;
        return journal;
    }

    public static EventEnvelope created(String mission) {
        return envelope(
                mission, new MissionEvent.MissionCreated(new MissionId(mission), "title", "objective", "C:/repo"));
    }

    public static EventEnvelope completed(String mission) {
        return envelope(mission, new MissionEvent.MissionCompleted(new MissionId(mission), "done"));
    }

    public static EventEnvelope envelope(String mission, DomainEvent event) {
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                Instant.parse("2026-10-31T10:00:00Z"),
                Origin.REAL,
                Actor.director(),
                new MissionId(mission),
                null,
                null,
                event);
    }

    /** A row as another version, a crash or a hand edit could leave it: written straight into the table. */
    public static void insertRaw(
            SqliteJournal journal, String type, String payload, String occurredAt, String mission, int format)
            throws SQLException {
        try (Connection c = journal.dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("INSERT INTO event_journal (event_id, occurred_at, origin,"
                        + " actor_kind, actor_id, mission_id, event_type, payload, format_version)"
                        + " VALUES (?, ?, 'REAL', 'DIRECTOR', 'director', ?, ?, ?, ?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, occurredAt);
            ps.setString(3, mission);
            ps.setString(4, type);
            ps.setString(5, payload);
            ps.setInt(6, format);
            ps.executeUpdate();
        }
    }
}
