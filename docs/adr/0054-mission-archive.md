# ADR-0054: Archiving a closed mission

Date: 2026-11-03 · Status: Accepted (the director decided the behaviour: hidden, restorable, worktrees kept)

## Context
The director asked to "close, archive a mission and clean the board". Measured on 1.12.0:
- "Closed" already has one meaning in the domain: a mission is `COMPLETED` (the director accepted the delivery, `MissionCompleted`) or `CANCELLED` (abandoned, `MissionCancelled`); both are terminal (`MissionStatus.isTerminal`, no way back). `MissionClosure` says what completing requires; cancelling is allowed from every other state (planning, awaiting approval, in progress, suspended).
- Closing is reachable from the desk's Mission tab in every open state: "Abandon the mission" for any of them, "Accept the delivery" for a mission in progress whose tickets are all done or dropped (also on the board). A suspended mission is resumed or abandoned. No state was found where a mission cannot be closed from the screen, so nothing about closing changes here.
- Every closed mission stays in `GET /api/missions`, the Work menu, the palette, the Deliverables panel and the meeting's mission picker. After a few weeks the lists are mostly finished work.
- A closed mission is not idle in every case: a session the cancel is stopping is still open until its end is recorded, and Floor42 convenes a retrospective after the end, whose sessions start on the closed mission.

The director decided: an archived mission is hidden and restorable. It leaves the board and the mission lists, stays in the journal, appears under "Archives" from where it can be restored. Its worktrees are not deleted.

## Options
| Option | What it costs | Verdict |
|---|---|---|
| A. A view of this browser (localStorage), like the board choice | No event; but a second browser or a script sees every mission again, and the director's decision leaves no trace | Rejected: the director's decision to put work away is a fact of the company |
| B. Two events, `MissionArchived` and `MissionUnarchived`, each carrying only the mission | Two new event types (a point of no return: an older build cannot read a journal holding them, ADR-0042 sets the row aside) | Chosen |
| C. One event `MissionArchiveChanged(mission, archived)` | One type instead of two, a boolean to read | Rejected: the journal reads better with one fact per event, as for suspend and resume |
| D. Delete the worktrees on archive | Frees disk | Rejected by the director: archiving is reversible, deleting is not |

## Decision
- `MissionArchived(mission)` and `MissionUnarchived(mission)`, in `MissionEvent`. Recorded by the director only (`DIRECTOR_ONLY` otherwise), with the mission's origin and the director as actor, like every event. No text is recorded: the journal stays language-neutral; the event lines are `ev.MissionArchived` and `ev.MissionUnarchived` in both catalogues.
- `Mission.archived`, set and cleared by those events only. A journal without them replays exactly as before: every mission listed (the golden scenario's earlier lines did not change; it now ends with the archive commands).
- Archiving is refused, each with its code and a sentence in both catalogues (`refusal.<CODE>`):
  - `MISSION_STILL_OPEN`: the mission is not completed nor cancelled;
  - `SESSION_RUNNING`: a session of the mission is still open (a cancel stops sessions, their end is recorded after);
  - `RETRO_UNDER_WAY`: its retrospective is open (the director may close it early).
  Archiving an archived mission, or restoring one that is not, records nothing.
- Once archived, nothing starts on the mission until it is restored: a session start and the convening of a retrospective are refused with `MISSION_ARCHIVED`.
- Status and outcome never change: archiving is not a new way to close a mission.
- Archiving and restoring are the director's filing, not activity on the mission: they do not move its
  `lastActivity` (`CompanyCore.apply`), so archiving never reorders the recent projects.
- If Floor42 meets the archive when it convenes the retrospective (the mission was archived right after it ended),
  the refusal is the expected outcome: logged at INFO, without a stack trace.
- A restore never convenes anything by itself (the director's decision: paid calls are opt-in). A closed mission
  without a retrospective shows "Convene the retrospective" (desk Mission tab, and the Archives tab's line for the
  mission just restored): `GET /api/missions/{id}/retro/offer` says whether it can be convened and why not
  (`RETRO_DISABLED`, `RETRO_NOT_IN_WORKFLOW`, `NO_PARTICIPANTS`; `DUPLICATE_RETRO` once it had one), `POST
  /api/missions/{id}/retro/convene` convenes it through Floor42's own convening path (`Retrospectives.openNow`, then its
  turns); the paid sessions start only on that click, after a confirmation. When it cannot be convened the button is
  absent and the reason is shown. A second click gets `DUPLICATE_RETRO`, shown as a refusal, never logged as an error.
  Like archiving, it needs a token of scope `all`.

## Concurrency
The check and the event are one domain command, run by `OfficeService.execute`, which is `synchronized`: the office's monitor is the lock. A session start (`SessionLog.start`), a session end, a cancel and the convening of a retrospective are commands under the same lock. So an archive and a session start (or a retrospective) cannot interleave: whichever takes the lock first is seen by the other, which then refuses or proceeds on what it sees. `MissionArchiveRaceTest` forces each order with latches: the first command holds the lock until the second thread is observed `BLOCKED` on it, then:
- archive first: the retrospective is refused (`MISSION_ARCHIVED`), none is convened;
- retrospective first: the archive is refused (`RETRO_UNDER_WAY`);
- a session started before the cancel: the archive is refused (`SESSION_RUNNING`) until its end is recorded; an end recorded while the director archives makes the archive wait, then pass.
There is no "reopen": a closed mission never goes back to an open state (`MissionStatus.canMoveTo`), so no command can race an archive by reopening.

## API and screen
- `GET /api/missions` lists the missions not archived; `?archived=true` lists only the archived ones. `MissionSummary` and `MissionView` carry `archived`.
- `POST /api/missions/{id}/archived` archives one, `DELETE /api/missions/{id}/archived` restores it (`GET /api/missions/{id}/archive` is already the archive wall's route, so the new routes avoid that path).
- "Clean the board": `GET /api/missions/archivable` lists what can be archived now; the screen asks for confirmation with the count, then `POST /api/missions/archive` with the ids confirmed. One command, one `MissionArchived` per mission archived; each mission is checked again under the lock, and one that changed in between is left out with its code (the screen says why, in its language).
- The desk: an "Archive the mission" button on a closed mission (desk Mission tab and board title), a confirmation first; an "Archives" tab with the archived missions, "Restore" on each, and "Archive all closed missions". The board, the Work menu, the palette and the Deliverables panel read `GET /api/missions`, so archived missions leave them all.
- Archiving needs a token of scope `all` (the browser has it); `missions:write` scripts cannot archive nor restore
  (403, tested).
- Recent projects: a project whose missions are all archived stays listed, with its activity, but names no last
  mission (`lastMission` and `lastTitle` null, `missions` empty); archived missions are left out of `missions` and
  of "used by".

## Costs and usage: archived missions stay, marked (default to confirm with the director)
Money spent stays visible: the costs overview and the usage page keep archived missions, in the period totals and in
the per-mission rows, each row marked `archived` (`MissionCost.archived`, `UsageLine.archived`, shown as "archived" in
both languages). Accounting is history: hiding a mission must not make spending look smaller. This is the default
taken on the director's behalf; the director may still choose to hide archived missions from the per-mission rows
(the period totals would keep them either way).

## Consequences
- Two new event types: a journal holding them is not fully readable by 1.12.0 or older (the rows are set aside, ADR-0042, and the missions show again as not archived). Point of no return for the release that ships them.
- The domain goldens gain `archived=false` on every mission; the event views and the API bodies goldens record the archived M-1 of the golden day.
- Costs and usage keep counting archived missions, marked: what was spent was spent.

## Reopen when
- The director asks to delete an archived mission's worktrees, or disk use from archived worktrees is measured as a problem.
- A "reopen" of a closed mission is ever added: it must then refuse an archived mission or restore it in the same command.
