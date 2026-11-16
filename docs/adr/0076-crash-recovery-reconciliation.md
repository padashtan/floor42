# ADR-0076: Crash recovery and journal reconciliation at startup

Date: 2026-11-16 · Status: Proposed (v1.3) · Extends ADR-0038 and D9 (`MissionRecovery`)

## Context

`MissionRecovery.recover()` already closes a session the last daemon left open (outcome `interrupted`, its cap kept as a
bound, a "Retry" question), clears stale git locks, counts launches that left no session and resumes the director's
last answer (D9). Three other traces of a stop in the middle of work were not shown to the director: a call authorized
and never reported, an approval whose hook died with the daemon, and a worktree holding changes that no ticket in
progress explains.

## Decision

`Reconciliation` (`app/boot`, called by `MissionRecovery.recover()` for every in-progress or suspended real mission,
after its sessions are closed and before the dispatcher resumes) records each as a question on the desk, a "To do" row
with the single option "Leave it". It never changes the journal's facts and never invents an outcome:

1. **Action authorized, no outcome** (stage AUTHORIZED, session gone): "The outcome of {tool} is unknown". The text says
   Floor42 recorded no result and that the call may or may not have run.
2. **Approval whose hook is gone** (an open ACTION_APPROVAL whose call is still REQUESTED, nobody working): the question
   stays open, the row says answering it cannot resume the call.
3. **Worktree with changes, no ticket claimed** (git reports pending paths, the agent has no ticket IN_PROGRESS or
   IN_REVIEW): the row names the agent and says nothing was committed or discarded.

Idempotent: a row carries a phrase code and a `key` (action id, approval id, agent). A restart asks nothing for a key
already asked on the mission, open or answered. No new journal event: rows are ordinary `QuestionAsked`. These rows
never offer "Retry", so they do not hold the mission (`MissionHolds.holdsMission`).

## Alternatives not taken

- A new `RecoveryReconciled` event: needs the director's approval (v1.3 rule); a question already says it and is visible.
- Closing the orphan approval ourselves: a remembered scope may rest on it, and "closed by whom" would be a claim.
- Failing the action in the journal (`ActionFailed`): an invented outcome.

## Limits

- A key answered once is never asked again: new changes in the same worktree after "Leave it" are not re-flagged.
- IN_REVIEW is read as claimed: pending changes of a ticket in review are normal.
- A worktree git cannot read is skipped (state unknown, never taken for clean).
- A call authorized in a session that ended normally without a result is flagged once at the next restart too.
