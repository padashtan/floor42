package dev.floor42.server.journal;

import dev.floor42.domain.Actor;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.EventEnvelope;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One row of the journal as an event, or why it cannot be one: the envelope's columns are checked as the payload is. */
final class JournalRows {

    private JournalRows() {}

    static EventEnvelope envelope(ResultSet rs, EventCodec codec) throws SQLException {
        String type = rs.getString("event_type");
        Instant occurredAt = occurredAt(rs, type);
        Origin origin = known(type, "origin", () -> Origin.valueOf(rs.getString("origin")));
        Actor actor = known(
                type, "actor_kind", () -> SqliteJournal.actor(rs.getString("actor_kind"), rs.getString("actor_id")));
        MissionId missionId = missionId(rs, type);
        try {
            return new EventEnvelope(
                    rs.getString("event_id"),
                    occurredAt,
                    origin,
                    actor,
                    missionId,
                    rs.getString("authorization_ref"),
                    rs.getString("causation_id"),
                    codec.decode(type, rs.getString("payload")));
        } catch (UnreadableEventException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UnreadableEventException(
                    Unreadable.Reason.CORRUPT,
                    type,
                    "envelope rejected: " + e.getClass().getSimpleName(),
                    e);
        }
    }

    private static Instant occurredAt(ResultSet rs, String type) throws SQLException {
        try {
            return Instant.parse(rs.getString("occurred_at"));
        } catch (RuntimeException e) {
            throw new UnreadableEventException(Unreadable.Reason.CORRUPT, type, "malformed occurred_at", e);
        }
    }

    private static MissionId missionId(ResultSet rs, String type) throws SQLException {
        String mission = rs.getString("mission_id");
        try {
            return mission == null ? null : new MissionId(mission);
        } catch (RuntimeException e) {
            throw new UnreadableEventException(Unreadable.Reason.CORRUPT, type, "malformed mission_id", e);
        }
    }

    /** The ten columns of an insert, in order. */
    static void bind(PreparedStatement ps, EventEnvelope e, EventCodec codec) throws SQLException {
        ps.setString(1, e.eventId());
        ps.setString(2, e.occurredAt().toString());
        ps.setString(3, e.origin().name());
        ps.setString(4, e.actor().kind().name());
        ps.setString(5, e.actor().id());
        ps.setString(6, e.mission() == null ? null : e.mission().value());
        ps.setString(7, e.authorizationRef());
        ps.setString(8, e.causationId());
        ps.setString(9, e.type());
        ps.setString(10, codec.encode(e.event()));
    }

    private interface Column<T> {
        T read() throws SQLException;
    }

    /** An enumerated column: an unknown name is a later version's (a new origin, a new kind of actor). */
    private static <T> T known(String type, String column, Column<T> read) throws SQLException {
        try {
            return read.read();
        } catch (IllegalArgumentException e) {
            throw new UnreadableEventException(Unreadable.Reason.NEWER_VERSION, type, "unknown value of " + column, e);
        } catch (NullPointerException e) {
            throw new UnreadableEventException(Unreadable.Reason.CORRUPT, type, "no " + column, e);
        }
    }

    private static final Pattern IDS =
            Pattern.compile("\"(?:ticket|question|incident|meeting)\"\\s*:\\s*\"((?:T|Q|I|MT)-\\d{1,9})\"");

    static Unreadable unreadable(ResultSet rs, long sequence, Unreadable.Reason reason, String detail)
            throws SQLException {
        String payload = rs.getString("payload");
        return new Unreadable(
                sequence,
                rs.getString("event_id"),
                rs.getString("event_type"),
                rs.getString("mission_id"),
                reason,
                detail,
                ids(payload));
    }

    /** The ids a row's payload names, read without decoding the row (it may not decode): null when none. */
    private static java.util.List<String> ids(String payload) {
        java.util.List<String> found = new java.util.ArrayList<>();
        if (payload == null) return found;
        Matcher m = IDS.matcher(payload);
        while (m.find()) found.add(m.group(1));
        return found;
    }
}
