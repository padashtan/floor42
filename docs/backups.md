# Journal backups and restore

Floor42's state is a replay of its journal, `floor42.db` in the data folder (`~/.floor42` by default, `/home/office/.floor42` in the Docker image). Floor42 copies it by itself (ADR-0042):

| Copy | When | Kept |
|---|---|---|
| `startup-<UTC time>.db` | at every start, before migration and replay | `floor42.backup.startup-keep` (5) |
| `daily-<UTC date>.db` | once a day while the daemon runs | `floor42.backup.daily-keep` (7) |
| `manual-<UTC time>.db` | Settings, "Back up now" (`POST /api/backups`) | `floor42.backup.manual-keep` (5) |
| `before-restore-<UTC time>.db` | just before a restore replaces the journal | `floor42.backup.restore-keep` (5) |
| `displaced-<UTC time>.db` | at the stop, when the journal file was replaced while Floor42 ran: the journal it still held | `floor42.backup.restore-keep` (5) |

**These copies live on the same disk as the journal.** A dead disk or a stolen laptop takes both: copy `backups/` to
another machine or a drive that is not always plugged in, on your own schedule (Floor42 does not do it). `floor42 doctor`
warns when the newest copy is older than 2 days.

They are in `floor42.backup.dir`, by default `<data folder>/backups`. Each one is SQLite's consistent snapshot (`VACUUM INTO`), checked before it gets its name. Settings, "Journal backups", lists them with date, kind, events and size. Floor42 refuses a folder inside the agents' worktrees or inside a git work tree, and says so there. Turn copies off with `floor42.backup.enabled=false` (`FLOOR42_BACKUP_ENABLED=false`).

## Restore from Settings (applied when Floor42 stops)

1. Settings, Journal backups: "Restore when Floor42 stops" on the copy you want. Nothing changes yet; the panel says which copy will be restored, and "Cancel" withdraws it. The choice is kept in the daemon's memory only, never in a file.
2. Stop Floor42 in order: `Ctrl+C` in its window, `docker compose down` or `docker compose restart`. Then start it again.
3. During that stop, after everything else has stopped writing, Floor42 checks the copy, copies the current journal as `before-restore-<time>.db`, then rewrites the journal from the copy in place (SQLite's online backup API). At the next start Settings says what was restored and where the replaced journal is kept, checked against the journal and the copies. A missing or damaged copy is not restored, and Settings says why.

A crash, `kill -9` or a power cut forgets the prepared restore: prepare it again. Events written after the copy are no longer on screen; they remain in the `before-restore-*` copy. Worktrees of missions newer than the copy stay on disk: the restored journal does not know them.

**A start never restores anything.** A private build that predates v1.0.0 restored the copy named in `restore-pending.properties` at its next start. Agents can run as the daemon's own OS user (no sandbox, or in Docker), so an agent could write that file, then stop the daemon (`kill -TERM 1` in Docker, where `restart: unless-stopped` starts it again) and roll the journal back silently. Proven in a container of that build on 2026-11-01. Now a start that finds this file restores nothing. It moves the file aside as `restore-pending.refused.properties`, logs an error and shows a red banner. A restore prepared by such a build and not applied before upgrading is refused the same way: prepare it again.

The copy you prepare is fingerprinted (SHA-256) when you click. The stop reads it once into memory and restores those
very bytes, only if they still match; a copy changed since ("changed since it was prepared") is not restored, and no
file stands between the check and the restore. A copy above 1 GiB, or above a quarter of the daemon's heap (`floor42.backup.restore-memory-bytes` sets the
limit; the answer names it), is not restored this way and cannot be prepared: restore it by hand (below), or start the daemon with a larger
heap. What the next start
says about the restore comes from `restore-outcome.properties`, a file the stop left: it is shown as reported by that
file, never as proven, since a process of Floor42's OS user could write one too.

## A journal that went back in time

The journal only grows. Floor42 notes what it saw of it (event count, last sequence, a hash of the last row) in
`journal-continuity.properties`, at start, every 30 s of activity and at its stop. A start that finds fewer events, or
the last event it saw missing or different, shows a red banner, "The journal went back in time", until you press
"Seen". This catches an older copy moved over `floor42.db`, by hand or by an agent running as Floor42's user (shown in a
container on 2026-11-01). When the file was replaced while Floor42 ran, its stop also keeps the journal it still held as
`displaced-<time>.db` (the last five are kept): restore it from Settings if the journal in place is not yours. Floor42 measures
the journal through a read-only connection it holds on the file it opened at start, so the record and the
`displaced-*` copy follow that file even when its pool, idle, reopens its connections on the replaced one. Nothing is
refused: the record is a file of the data folder, which that user could rewrite too. This is detection, not prevention
(D16). After a restore by hand (below) the banner appears too: press "Seen".

The banner also says when nothing tells what the last run saw:
- `FIRST_CHECK` (amber notice): `journal-continuity.properties` is missing on the very first check since an older
  Floor42 last opened the journal. Expected once, after an upgrade from a build older than v1.0.0: check that the journal is the one you
  expect and press "Seen". It is a hint (a flag in the journal), not a proof.
- `MISSING` (red): the record is missing beside a journal that holds events, and this is not that first check. Never
  expected: the record was deleted or rewritten, and whether the journal went back in time cannot be told.
- `UNREADABLE` (red): the record cannot be read.
- `ANCHOR_FAILED` (red): the check could not be made on this run (the daemon's log says why); nothing is detected until
  the next start, and "Seen" only hides the banner.

A fresh data folder (no event, no record) says nothing.

## Another program holding the journal

Floor42 does not stop other programs from opening `floor42.db`: a `sqlite3` shell, a database browser or a backup tool of your own user may. While one of them holds a lock (an open transaction, even a read one), Floor42 cannot commit: each append waits SQLite's busy timeout (3 s), then fails with `JOURNAL_LOCKED` (503) and nothing is recorded. A red banner says "Another program holds the journal" until an append succeeds again. Close that program. To read the journal safely, read a copy (`backups/`) or open it read-only and briefly (`sqlite3 -readonly`). Appends begin `IMMEDIATE`, so they take the write lock when they begin rather than half-way through.

## Restore by hand

When Floor42 does not start at all:

1. Stop every Floor42 process that uses this data folder. Never replace the journal of a running daemon.
2. In the data folder, move `floor42.db` aside (keep it), and also `floor42.db-journal`, `floor42.db-wal` and `floor42.db-shm` if they exist. A leftover rollback journal applied to another file corrupts it.
3. Copy the chosen backup to `floor42.db`, for example:
   ```
   cp ~/.floor42/backups/daily-2026-10-31.db ~/.floor42/floor42.db
   ```
   Optional check: `sqlite3 ~/.floor42/floor42.db "PRAGMA quick_check; SELECT count(*) FROM event_journal;"` prints `ok` and the number of events.
4. Start Floor42.

## When the start stops on an event

- "written by a newer Floor42 version": run that version again. Nothing is lost; this build leaves those rows out and says so in a red banner.
- "Event #N (Type) of the journal cannot be applied": the journal is readable but this build's rules disagree with it. Run the version that wrote it, or restore a copy made before that event (its sequence is in the message; each copy's event count is in Settings or in `SELECT count(*)`).

## Sizing and logs

`SqliteJournal.replay()` reads the whole journal into a list at each start (and `readAll()` into a second one): memory
grows with the number of events, and the start takes as long as the replay. Measured by `ReplayVolumeTest` (tagged
`slow`, run with `./mvnw verify -Pslow`) on a developer laptop, 100,000 synthetic events (mission created and completed,
objectives of 300 characters): 39 MB on disk, 1.4 s to read and decode, 1.6 s with the company rebuilt, about 72 MB of
heap held afterwards, 9 s to write them. Real events are larger and more varied, so read this as an order of magnitude,
not a guarantee: the test bounds it at 60 s and 2 GiB. Advice: the default JVM heap (a quarter of the RAM) is ample up to
a few hundred thousand events; past a million, raise `-Xmx` (`JAVA_TOOL_OPTIONS=-Xmx2g`) and expect a start of
several seconds. Restoring a copy from Settings needs the copy in memory too (limit above). Streaming the replay is not
done yet.

The daemon's own log is `<data folder>/logs/floor42.log` (10 MB each, the last five kept, rotated at every start, so a
crash is still in the previous file). `daemon.log` and `daemon.err.log` beside it hold the console output of the last
run, `daemon.prev.log` the one before. No secret is written to them.
