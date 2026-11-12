# ADR-0055: Work not in a commit is said before the director accepts a mission

Date: 2026-11-03 · Status: Accepted (the director approved the new event type `UncommittedWorkAccepted`; its point of no return is the release that ships it)

## Context
Seen on a real mission: it was accepted while its whole deliverables folder in the mission worktree had never been
committed. Nothing stopped it. The end-of-mission report did list `OUTPUTS_NOT_COMMITTED`, but only on that screen, and
`Workspaces.pending` reads a failed `git status` as an empty list: a worktree git could not read looked clean. The
acceptance itself (`POST /complete`, `DeliverablesHome.complete`) checked nothing of the kind. On code missions the
copy line also read "0 written, 3 left out" with nothing to say what "left out" means.

## Decision
- `UncommittedDeliverables.check` measures each worktree of the mission just before acceptance: `CLEAN`, `DIRTY` (files
  changed or never committed, 20 named, all counted; build and cache artefacts of `BuildArtefacts.KINDS` not counted) or
  `UNKNOWN` with a reason code. The real path is resolved first and only a folder under Floor42's own `workspaces`
  (`MissionBranches.madeByFloor42`, links and junctions resolved) is read: anything else is `NOT_A_MISSION_WORKTREE`. A
  missing folder is `WORKTREE_MISSING`. A `git status` that fails, times out or cannot start is `GIT_FAILED`
  (`Workspaces.pendingIfKnown`), never clean.
- The measure has a token (SHA-256 of every state and path). `GET /api/missions/{id}/uncommitted` serves it.
  `POST /complete` measures again and, when not clean, is refused with 409 `UNCOMMITTED_DELIVERABLES` unless its
  `acknowledged` equals the token: the director accepts exactly what they saw, and a measure that changed asks again.
- The override is journaled: `UncommittedWorkAccepted(mission, worktrees)`, the director as actor, the mission's origin,
  in the same command as the copy record and `MissionCompleted`. The measure is read only; the journal entry and the
  completion are one command, so no crash can leave one without the other.
- Lock: the check and the acceptance run under the mission's close lock (`DeliverablesHome.closing`, a write lock;
  cancel takes it too). Exactly three other actions take the same lock shared (`DeliverablesHome.changing`): the
  workshop's edit of a file, its accept (the commit) and its reject, through `WorkshopResource`. None of them can land
  between the measure and the record. Nothing else is covered: an agent session's own writes, the director's terminal,
  a merge or anything run outside Floor42 can still change a worktree after the second measure, and
  such a change is not in the record. Neither side waits: a busy mission answers 409 `CLOSING`.
- `MissionReports`, `Progress` (the workshop line of the waits panel) and `PullRequestDraft` (uncommitted changes
  left out of a pull request) use `pendingIfKnown` too: a worktree whose status git cannot give is
  `WORKTREE_UNREADABLE`, a workshop line "cannot tell" (no count) and a pending count of -1 ("cannot tell"), never 0.
- Screens: every acceptance (board, mission control, end-of-mission screen) goes through `completeMission`, which reads
  the check and asks the director with the list before sending the token; the end-of-mission screen and the
  deliverables panel show the block (`UncommittedAlert.vue`); a failed check is said, never shown as clean. The copy
  line explains "left out" (`home.leftOutHelp`).

## Options not taken
- A warning on the screen only: the case seen came through the board's button, which never showed the report.
- Refusing the acceptance outright: the director may knowingly leave scratch files; the override is theirs, recorded.
- A field on `MissionCompleted`: changes a serialized form every journal holds.

## Reopen when
- Directors accept with uncommitted work most of the time: the agents' commit step needs work, not the alert.
- Agent sessions can still write in a worktree while a mission closes: today the completion is refused while a ticket is
  open or an action is pending or running (`MissionClosure.requireFinished`), but a session's own writes are not under
  the close lock.
