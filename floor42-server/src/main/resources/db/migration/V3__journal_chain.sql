-- Hash chain over the journal (ADR-0079). event_journal is not touched: its rows, its trigger and its columns stay what
-- Floor42 1.x reads and writes, so a rollback to 1.2 still reads the file. The chain sits beside it, one row per journal
-- row. The rows that exist when this migration runs are chained at the next start (code, not SQL: SHA-256 is not in
-- SQLite), marked adopted = 1: the chain proves nothing about them before that moment.
CREATE TABLE journal_chain (
    sequence  INTEGER PRIMARY KEY,
    prev_hash TEXT    NOT NULL,
    hash      TEXT    NOT NULL,
    adopted   INTEGER NOT NULL DEFAULT 0
);

-- Signed statements "the chain ended at this hash at this time". kind: ADOPTION (after rows were chained after the
-- fact), PERIODIC (every N rows) or SHUTDOWN.
CREATE TABLE journal_checkpoint (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    sequence  INTEGER NOT NULL,
    hash      TEXT    NOT NULL,
    signed_at TEXT    NOT NULL,
    key_id    TEXT    NOT NULL,
    signature TEXT    NOT NULL,
    kind      TEXT    NOT NULL CHECK (kind IN ('ADOPTION', 'PERIODIC', 'SHUTDOWN'))
);

-- A break the director has seen: writes resume, the verification keeps reporting it as acknowledged.
CREATE TABLE journal_chain_ack (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    broken_sequence INTEGER NOT NULL,
    reason          TEXT    NOT NULL,
    acknowledged_at TEXT    NOT NULL
);

CREATE TRIGGER journal_chain_no_update BEFORE UPDATE ON journal_chain
BEGIN SELECT RAISE(ABORT, 'journal_chain is append-only'); END;
CREATE TRIGGER journal_chain_no_delete BEFORE DELETE ON journal_chain
BEGIN SELECT RAISE(ABORT, 'journal_chain is append-only'); END;
CREATE TRIGGER journal_checkpoint_no_update BEFORE UPDATE ON journal_checkpoint
BEGIN SELECT RAISE(ABORT, 'journal_checkpoint is append-only'); END;
CREATE TRIGGER journal_checkpoint_no_delete BEFORE DELETE ON journal_checkpoint
BEGIN SELECT RAISE(ABORT, 'journal_checkpoint is append-only'); END;
CREATE TRIGGER journal_chain_ack_no_update BEFORE UPDATE ON journal_chain_ack
BEGIN SELECT RAISE(ABORT, 'journal_chain_ack is append-only'); END;
CREATE TRIGGER journal_chain_ack_no_delete BEFORE DELETE ON journal_chain_ack
BEGIN SELECT RAISE(ABORT, 'journal_chain_ack is append-only'); END;
