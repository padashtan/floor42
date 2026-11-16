# ADR-0079: A hash chain over the journal, with signed checkpoints

Date: 2026-11-16 · Status: Proposed · Amends ADR-0044 (amendment 3, "writes are not refused")

## Context

The journal is append-only by SQLite triggers (ADR-0005). That stops a statement, not a person: anyone who can open the
file can drop a trigger, change a payload and put it back. Journal continuity (ADR-0044, amendment 3) catches a journal
that went back in time between two runs, not an edit in the middle. v1.3 is about proof: a mission's dossier and the
oversight report are only as good as the rows under them. The chain makes an edit in the middle visible.

## Decision

**Where.** A table `journal_chain` beside `event_journal` (migration `V3__journal_chain.sql`), one row per journal row:
`sequence` (primary key), `prev_hash`, `hash`, `adopted`. `event_journal` is not altered: no column, no trigger, no
payload, no `format_version` change. Replay, the event codec and the proof dossier read what they read before.

**Canonical encoding.** The hash of a row is

    hash = SHA-256( DOMAIN || prev || F(c1) || F(c2) || ... || F(c12) )

- `DOMAIN` is the ASCII bytes `floor42.journal.chain.v1` followed by one byte `0x00`.
- `prev` is the 32 raw bytes of the previous row's hash. The first row's `prev` is 32 bytes of `0x00`.
- `c1..c12` are the stored columns, in this order: `sequence`, `event_id`, `occurred_at`, `origin`, `actor_kind`,
  `actor_id`, `mission_id`, `authorization_ref`, `causation_id`, `event_type`, `payload`, `format_version`.
- `F(v)` is the single byte `0x00` when the column is SQL NULL; otherwise the byte `0x01`, the length `n` of the value's
  UTF-8 encoding as 4 bytes big-endian, then those `n` bytes. An integer column (`sequence`, `format_version`) is its
  decimal text without sign or leading zero.
- The columns are read as SQLite stores them, never re-serialised through the event codec: the hash does not move when a
  codec or a default changes. `hash` and `prev_hash` are stored as 64 lowercase hex characters.

The length prefix makes the encoding injective: two different rows cannot produce the same byte string.

**Writing.** `SqliteJournal.write` chains inside the append's own transaction: it first chains any row found after the
chain's end. Rows found there while the daemon runs were written by another program: they are **not** adopted silently.
The chain becomes `BROKEN` (reason `UNCHAINED_TAIL`) and the append is refused; only the director's acknowledgement of
that exact tail lets the rows in, marked `adopted = 1`. Adoption is otherwise done at a start only (below). The events
are then inserted and chained. A commit holds the events and their links, or neither. The sequence is part of the hash,
so a reorder is a mismatch.

**Existing journals.** `V3` creates empty tables; no SQL computes a hash (SQLite has no SHA-256). At the first start of
1.3 the rows that exist are chained from the genesis, `adopted = 1`, in the open transaction of one start, and a first
checkpoint (kind `ADOPTION`) is signed on the new end, and the start logs how many rows were adopted. That checkpoint is
the recorded anchor: the chain proves nothing about those rows before it. The status reports `adoptedRows`, so the limit is shown, not hidden.

**Checkpoints.** A key pair Ed25519 from the JDK (`Signature.getInstance("Ed25519")`), in `journal-signing.key` of the
data folder: two base64 lines (PKCS#8, X.509), made owner-only (`OwnerOnly`: mode 600, or one ACL entry on NTFS) before
the key is written, then moved into place. `key_id` is the first 16 hex characters of the SHA-256 of the public key.
A checkpoint is signed every `floor42.journal.checkpoint-every` rows (default 1000), after rows were adopted, and at an
orderly shutdown. The signature covers, as UTF-8 lines: `floor42.journal.checkpoint.v1`, the sequence, the chain hash at
that sequence, the time, the key id. Rows go to `journal_checkpoint`.

**The last checkpoint outside the database.** Each checkpoint is also written (sequence, hash, key id) to
`journal-checkpoint.properties` in the data folder, by an atomic move. Verification compares it with the chain: a forger
who drops the triggers, rewrites a row, recomputes the keyless links and deletes the checkpoints from the database still
finds the old hash in this file (`CHECKPOINT_ANCHOR`). If the signing key is missing while a checkpoint is remembered (in
the database or in this file), no new key is made: the chain is `BROKEN` with `CHECKPOINT_KEY` (`keyProblem`
`KEY_MISSING`). After a restore the director prepared, the file is removed: it belonged to the journal before it.

**Verification.** At start and on `POST /api/journal/integrity/verify`: every row is recomputed from its stored columns
and its stored predecessor, and every checkpoint's signature and hash are checked against the chain. The first failure
is reported with a reason: `HASH_MISMATCH` (a row was edited), `PREV_MISMATCH` (the link is wrong), `UNCHAINED_ROW` (a row
below the chain's end has no link: inserted), `MISSING_ROW` (a link without its row: deleted), `CHECKPOINT_SIGNATURE`,
`CHECKPOINT_MISMATCH` (the chain no longer holds the hash a signed checkpoint saw: the tail was cut, or rewritten),
`CHECKPOINT_ANCHOR` and `CHECKPOINT_KEY` (above), and `UNCHAINED_TAIL`. A stored hash that is not 64 hex digits is a
failure at its row (`HASH_MISMATCH`), never an exception: the daemon still starts and answers an append with 503.
`GET /api/journal/integrity` returns the last result: status, first broken sequence, row counts, the last checkpoint and
how many signatures verify. Sequences, hashes and counts only, never a payload.

**Refusing writes (amends ADR-0044).** While the status is `BROKEN`, `append` throws `JournalChainBrokenException`,
mapped to 503 `JOURNAL_CHAIN_BROKEN`. ADR-0044 rejected refusal for the continuity record because that record is a file
the same user can rewrite. The chain differs for a forger without the key: the links are keyless, so he can recompute
them, but he cannot re-sign the checkpoints, and the last one is also kept outside the database (above). Refusing is
therefore not an empty gesture, and it stops the honest case from building on a journal that no longer proves what it
holds. `POST /api/journal/integrity/acknowledge` (the director's browser session only: a script token of any scope and the
director's own bearer token are refused with 403 `COOKIE_ONLY`; the acknowledgement is logged with its sequence and
reason) records the break in `journal_chain_ack` with what the row looked like (the stored hash and the recomputed
one, `V4`). Only that exact state is covered: a different edit of the same row is a new break. Appends resume and chain
from the stored hashes. The status
becomes `ACKNOWLEDGED` and keeps naming the break; a later, different break refuses writes again. Nothing is repaired.
A check that could not run (`UNCHECKED`) refuses nothing: refusing on a check that did not happen would stop a healthy
daemon.

## Rollback to 1.2, and replay

- 1.2 reads `event_journal` exactly as before: same columns, same trigger, same payloads. It does not know `journal_chain`.
- Flyway in 1.2 sees `V3` applied and not resolved locally. Flyway ignores such a "future" migration by default
  (`ignoreMigrationPatterns` defaults to `*:future`) and the project does not override it. This is the documented default,
  not a case the tests run: a rollback is to be tried on a copy of the data folder first.
- 1.2 appends rows without a link. On the next start they are after the chain's end: they are adopted (`adopted = 1`),
  a new `ADOPTION` checkpoint is signed, the start logs it, and the status shows them. The chain cannot tell a row
  appended by 1.2 from a row another program inserted at the end while the daemon was stopped: both are adopted at a start.
  While the daemon runs, such a row is a break (`UNCHAINED_TAIL`). The limit is stated, not solved.
- A row inserted with an explicit sequence below the chain's end has no link and is reported (`UNCHAINED_ROW`).
- A restore from a copy made before V3 gives a journal with no chain: the next start chains it, as any 1.2 journal.

## Options not taken

| Option | Why not |
|---|---|
| `prev_hash` and `hash` columns on `event_journal` | The append-only trigger forbids the UPDATE the backfill needs; dropping it for a migration weakens the guarantee; a 1.2 binary would also have to cope with changed columns. A side table leaves the table 1.x reads untouched |
| Hash over the re-serialised envelope | Moves with the codec and its defaults; a replay-compatible change would break the chain |
| Hash over the payload only | Leaves the origin, actor and time editable |
| A signature on every row | Cost and a key use per append for no gain: the chain already links rows; the checkpoint signs the end |
| Merkle tree | Allows partial proofs nobody asked for; more code for the same detection |
| Key in the journal or the OS keystore | The journal is the thing to protect; no cross-platform keystore in the JDK. The data folder, owner-only, matches the other secrets of the daemon |
| Refuse to start on a break | The director could not reach the screen that explains it; refusing writes keeps the daemon readable |
| Regenerate a lost key silently | A forger could delete the key, get a new one and turn every old checkpoint into `OTHER_KEY`, which is not a break. A key file that exists and cannot be read is an error (`keyProblem`); a missing one beside checkpoints is a break (`CHECKPOINT_KEY`) and no key is made. A first run with no checkpoint makes one |

## Limits

- **Same OS user.** A process with the daemon's rights can read `journal-signing.key`, edit rows, recompute the chain and
  sign new checkpoints, and rewrite `journal-checkpoint.properties`. The chain detects an edit made without the key and
  without that file; it does not stop a forger who has both. The same limit as ADR-0044 D16. A checkpoint copied off the machine (the endpoint returns the last one,
  signature included) is what turns detection into proof: nothing here copies it for the director.
- The chain proves nothing about adopted rows before their adoption.
- Verification reads the whole journal. It runs at start and when asked, not on every request. No timing is claimed here.
- Only the first break is reported. After an acknowledgement the scan goes on and reports the next one.
- Truncating the tail is caught only up to the last checkpoint: rows appended after it, then removed with their links,
  leave nothing to compare with. Continuity (ADR-0044) catches a shorter journal as well.
- Origin is `REAL` on every view: it is computed on the files of the data folder.

## Consequences

- One more table set and a key file in the data folder; backups of the journal carry the chain with it.
- Every append hashes its rows: SHA-256 of the row, one extra index seek for the chain's end. Not measured.
- A new message code, `JOURNAL_CHAIN_BROKEN`, with a banner entry in both catalogues. No new journal event type.
