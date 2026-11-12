# ADR-0019: Hiring for a role, tickets in parallel, one desk each, a night when the quota is spent

Date: 2026-10-21 · Status: Accepted. The director chose: tickets in parallel; hiring by role with the model to choose; the night message gives the reset time measured by Claude Code. Supersedes "one ticket at a time" in ADR-0017.

## Context
Up to now one developer (Chris) and one reviewer (Dehren) were wired in the code, and a mission ran one ticket at a time. The director wants to hire agents to go faster, to see each one at its own desk, to see them come in and leave, and to see the office close when the plan's quota is spent.

## Decision
- **Work roles.** An agent is hired for a role: `DEVELOPER`, `REVIEWER` or `RESEARCHER` (events `AgentHired` and `AgentRoleAssigned`). `PLANNER` stays Geoffrey's role. What each role may do is enforced by code, not by the brief:
  - A reviewer gets read-only tools, and rules naming it deny writing, the shell and the network before every review.
  - A developer works in its own git worktree.
  Staff hired before roles existed keep the role Office always gave them (`Staff.roleOf`).
- **Dismissal.** `AgentDismissed` is accepted only when the agent has no ticket in progress or in review and no open session. The agent leaves the office and no longer takes work. Its history stays in the journal and its profile remains readable.
- **Parallel dispatch.** `LiveMissions.produce` became a dispatcher. Under one dispatch lock shared by all missions (changed by the 2026-10-21 review, so an agent is never reserved twice), it starts every task that can run now, up to `office.parallel.max-sessions` per mission (default 2; 1 restores the old behaviour). When a task ends, it looks again.
  - **Order of tasks.** Reviews first, then tickets in progress, then approved tickets whose dependencies are done, in their order.
  - **Developers.** A developer carries one ticket at a time, counting both in progress and in review. A reviewer therefore never reads changes that belong to the next ticket.
  - **Who takes a ready ticket.** The developer who produced its dependencies takes it, so the work builds on that worktree. Otherwise its assignee takes it if free, else any free developer, and the ticket is reassigned in the journal.
  - **One ticket, one clean state** (added 2026-10-22). A developer whose worktree still holds the changes of a finished ticket, not yet accepted or rejected by the director in the workshop, takes no new ticket; a ticket that builds on that work waits for it. Agents do not commit, so without this the next review would read both tickets and a rejection would undo both. Measured with git on the worktree, shown on the board.
  - **Work on another branch** (added 2026-10-22). A ready ticket whose dependencies were done by several developers, or by one no longer on staff, is not handed over silently: Office asks the director, without blocking the mission, "Go on without it" (the brief then lists the work that is not in the worktree) or "Drop the ticket".
  - **Agents.** One session per agent at a time, across missions.
  - **Reviews.** A review goes to a free reviewer who did not produce the ticket.
  - **Questions.** A ticket with an open question for the director waits, for instance after being rejected twice. A question that makes the whole mission wait (plan limit, budget, retry, escalation) stops all dispatch, as before.
- **Branches are merged by Office before a dependent ticket (2026-10-29, pays D10).** Before a ticket whose dependencies were done by other developers starts, Office merges their branches into the worktree of the developer taking it, one `--no-ff` merge commit per branch, and records `BranchesMerged`. Only accepted work is on a branch (accepting commits), so a dependency whose worktree still holds unaccepted changes is not merged: the ticket waits for the director to accept, or, when its producer has left, the director is asked at once. A conflict undoes that merge (`git merge --abort`), names the files, and asks the director: go on without it, or drop the ticket. Office never resolves a conflict itself. Before this change, branches were not merged by Office (recorded as debt D10).
- **The office.** A third room, the open space, has ten desks, and each agent gets its own for good. Agents work there; reviewing also happens at the desk. A door in the west wall opens as someone walks through. Agents hired while the page is open come in through it, and dismissed agents leave through it.
- **The night.** The usage view carries `night` when no session is running and either:
  - a usage window is measured at its limit (or blocked) and has not reset, or
  - the director answered "Wait for the reset" and no session started since.

  `resumesAt` is the reset time reported by Claude Code. The page then dims the lights and turns the screens off, everyone walks out, and a banner says good night with that time. The page expires the night itself at that time, because nothing is journaled when a window resets. No night is shown while a session runs.

## Consequences
- Two developers spend the plan's quota about twice as fast. The hiring form says so, and the plan-limit hold (ADR-0016) still checks before every session.
- In-memory state is limited to the tasks running now. After a restart, the dispatcher reads from the journal what is next (D9 still holds).
- Verified with the fake Claude Code: `StaffApiTest` shows two developers on two independent tickets whose sessions overlap in time, each in its own worktree, each reviewed by someone else. Hiring rules are covered, and `StaffTest` covers the domain rules. The scene was checked on screen: a hired agent walks in; at the last session's end with a 100% window, the lights go down and everyone walks out; the banner leaves at the reset.

## Reopen if
Parallel branches conflict often enough that Office should merge them itself; a mission needs more than one planner; or the director wants a different limit per mission.
