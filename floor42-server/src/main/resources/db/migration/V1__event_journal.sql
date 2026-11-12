-- The journal is the source of truth: every state of the company is a replay of these rows (ADR-0005).
CREATE TABLE event_journal (
    sequence          INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id          TEXT    NOT NULL UNIQUE,
    occurred_at       TEXT    NOT NULL,
    origin            TEXT    NOT NULL CHECK (origin IN ('SIMULATED', 'REAL')),
    actor_kind        TEXT    NOT NULL CHECK (actor_kind IN ('DIRECTOR', 'AGENT', 'SYSTEM')),
    actor_id          TEXT    NOT NULL,
    mission_id        TEXT,
    authorization_ref TEXT,
    causation_id      TEXT,
    event_type        TEXT    NOT NULL,
    payload           TEXT    NOT NULL,
    format_version    INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX event_journal_mission ON event_journal (mission_id, sequence);

-- Append-only, enforced by the database itself and not only by the code that writes to it.
CREATE TRIGGER event_journal_no_update BEFORE UPDATE ON event_journal
BEGIN
    SELECT RAISE(ABORT, 'event_journal is append-only');
END;

CREATE TRIGGER event_journal_no_delete BEFORE DELETE ON event_journal
BEGIN
    SELECT RAISE(ABORT, 'event_journal is append-only');
END;
