# ADR-0042: An unreadable journal row is left out and shown; an event that cannot be applied still stops the start; the journal is copied at every start and every day

Date: 2026-10-31 · Status: Accepted · The restore marker is superseded by [ADR-0044](0044-sessions-tokens-and-exact-origin.md) (amendment of 2026-11-01)

## Context
Debt D14: the company is a replay of the journal (ADR-0005), and replay stopped at the first row the code could not decode. One corrupt row, or one event type written by a later Floor42 and read by an earlier one, stopped the daemon at startup and hid every mission. The workflow engine will add event types: running an older build on a newer journal (a rollback, a second checkout) becomes likely. There was also no copy of the journal other than Office's former folder (1.5.0 migration).

Two failures differ:
- **Decoding** a row (JSON, type, fields, envelope columns). The code learns nothing from the row; leaving it out loses exactly what that row recorded.
- **Applying** a decoded event (`Company.apply`). Each stored event passed the domain's checks when it was written, so a failure now means the rules that apply it are not the rules that wrote it (a changed `apply`, an edited journal), or that an event it builds on is missing. `apply` changes state in place: an event can fail halfway and leave part of its change behind.

## Options
| Option | What it costs | Verdict |
|---|---|---|
| A. Keep stopping on any failure (today) | One bad row blocks every mission until someone edits SQLite by hand | Rejected: the debt itself |
| B. Leave out every failure, decode or apply, and start | A failed `apply` may have half-changed the state; the screen then shows a state that never existed, with a banner that undersells it. A bug in `apply` would pass silently on every start | Rejected |
| C. Leave out decode failures; stop on an apply failure, unless an earlier row was already left out | The start still stops when the journal is readable and the code disagrees with it: that is a bug to fix or a version to run, not to hide | **Taken** |
| D. Block every command while rows are left out (read-only daemon) | The director could not even cancel a mission or answer a question; the newer-version case (the common one) gains nothing, since the rows come back once that version runs | Rejected; recorded as a signal to reopen |

## Decision
- **Decode failures are quarantined.** `SqliteJournal` decodes each row on its own; a row it cannot read becomes an `Unreadable(sequence, eventId, type, mission, reason, detail)` and every read (`replay`, `readAll`, `latest`, `since`) leaves it out. The row stays in the journal, untouched (the table is append-only anyway).
  - `NEWER_VERSION`: an event type this build does not know, an unknown field (`FAIL_ON_UNKNOWN_PROPERTIES` stays on), an unknown enum value in the payload or the envelope (origin, actor kind), or a row whose `format_version` is above 1. Said as "written by a newer Floor42 version: upgrade", never "corrupt".
  - `CORRUPT`: broken JSON, a missing or malformed field, a malformed `occurred_at` or mission id.
- **Apply failures.** If every row before it was applied, a failing event **stops the start** with a `JournalException` naming its sequence and type, telling the director to run the newer version or restore a backup (`docs/backups.md`). Leaving it out would show a state that never existed. If a row was already left out before it, the failure is expected (it builds on what is missing): it is quarantined as `NOT_APPLICABLE` "after an earlier event was left out", because the state is already declared incomplete. Accepted risk: such an event may have half-applied. The banner already says the state may be incomplete, and the list names the row.
- **Never the payload.** The detail names the kind of failure and the field path (`unknown field workflow`, `JsonMappingException at title`, `not valid JSON`), never a stored value: a parse error's own message quotes the text, so it is dropped. For an apply failure only the exception's class and, for a `NullPointerException`, the JVM's message (method and field names). The log lists each row the same way (`left out: #12 MissionCreated (CORRUPT) not valid JSON`).
- **The director sees it.** `GET /api/journal/unreadable` (director only, 403 to an agent credential). A red banner "N events of the journal could not be read; the state may be incomplete", with "written by a newer Floor42 version: upgrade" when that applies, and a panel listing sequence, type, mission, reason and detail.
- **Commands stay allowed** while rows are left out (option D rejected). A command is checked against the state on screen; once the newer version runs, it reads the old rows again, in their place.
- **Backups.** `JournalBackups` with `BackupStore`: `VACUUM INTO` through a read-only connection, the same as the 1.5.0 migration. It is a consistent snapshot: writers carry on, and a copy holds whole commands only (tested with a writer appending two-event commands during twelve copies). Each copy is written as `*.partial`, checked (`PRAGMA quick_check`, events counted), then renamed.
  - When: at every start, after the Office migration and before Flyway and replay (`startup-<UTC stamp>.db`). Then once per UTC day while the daemon runs, checked every `floor42.backup.check-minutes` (60): `daily-YYYY-MM-DD.db`. And on demand: `POST /api/backups`, `manual-<stamp>.db`.
  - Rotation: `floor42.backup.startup-keep` 5, `daily-keep` 7, `manual-keep` 5. `before-restore-*` copies are never deleted.
  - Where: `floor42.backup.dir`, default `<data-dir>/backups` (`~/.floor42/backups`). The folder is not used if it lies inside the agents' worktrees (`<data-dir>/workspaces`, the former folder's too) or inside a git work tree (an ancestor holding `.git`, the home folder itself excepted). Copies are then off: an error in the log and in Settings. A copy holds every event, and an agent or a commit could carry it away. Tests copy to `${java.io.tmpdir}/floor42-test-backups/<uuid>`, never `~/.floor42`, never inside the repository.
  - A failed copy never blocks the start: it is logged and shown in Settings (`lastError`).
- **Restore is prepared, then applied at the next start.** The live journal is never swapped: Agroal's open connections would keep writing to the file they hold. `PUT /api/backups/restore {name}` checks the copy and writes `restore-pending.properties` in the data folder; `DELETE` cancels. At the next start, before anything opens the journal, `PendingRestore` checks the copy again and copies the current journal as `before-restore-<stamp>.db`. If SQLite cannot read the current journal, its raw files are copied instead. It then removes any stale `-journal`/`-wal`/`-shm` file and moves the copy into place atomically. A missing or damaged copy is not restored: the journal stays as it was, and Settings says why. The marker is removed either way. Names are matched against the store's own pattern, never taken as a path. The manual procedure is in `docs/backups.md`.
  - *Superseded on 2026-11-01 (1.8.0, ADR-0044 amendment):* an agent of the same OS user could write that marker and restart the daemon, rolling the journal back. The restore is now held in memory and applied during the daemon's own orderly stop through SQLite's online backup API; a start never restores, and a marker it finds is refused and shown.

## Consequences
- D14 is paid: a row this version cannot read no longer stops the daemon (`JournalQuarantineTest`, `JournalReplayTest`).
- An apply failure on a readable journal still stops the start, on purpose. The message points at the two ways out.
- A row left out is gone from the screen until the newer version runs or the row is repaired. Nothing rewrites it.
- `VACUUM INTO` holds a read lock for the copy's duration. In rollback-journal mode a writer waits for it (SQLite's busy timeout, 3 s by default). For today's journals (a few MB) the copy takes milliseconds.
- A process that can write the data folder could prepare a restore, until 1.8.0: no file asks for one any more (ADR-0044 amendment).

## Reopen when
- A copy takes more than about a second (large journal): move to WAL mode or to the online backup API in steps.
- A command run while rows were left out turns out to have contradicted one of them once read (option D).
- `apply` becomes transactional (it works on a copy and keeps it only on success): an apply failure could then be quarantined without the half-applied risk.
