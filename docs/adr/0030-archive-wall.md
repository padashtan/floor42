# ADR-0030: The archive wall shows the repository's commits and each agent's branch

Status: Accepted, 2026-10-30 (W6, P4)

## Context
The director wants to see git in the room: what the repository holds, and what each agent has committed on its own branch.

## Decision
- `GET /api/missions/{id}/archive` reads git when asked: the newest commits (at most 12) of the repository's current branch, and for every worktree of the mission on disk, its branch, how many commits it holds beyond the repository's HEAD, and those commits. Nothing is stored or changed. `available` is false when the mission has no repository or git cannot read it, and the wall stays dark.
- Worktrees are found on disk (`<data>/workspaces/<mission>-<agent>`), whether the journal knows them yet or not: the wall shows what git has, not what Office expects.
- On the director's floor, a wall-mounted archive draws the repository's line and one branch per agent in the agent's colour, a dot per commit beyond the repository. Clicking it opens the list of commits; an agent's name leads to that agent.
- The UI reads the archive again when the mission changes, and shortly after events that may add commits: a tool executed, a delivery submitted, branches merged, a patch accepted.

## Consequences
- Counts and commits come from git, read at the moment of the request (measured); the scene draws counts only.
- Next in P4: Code City (W10) and the library (W16).
