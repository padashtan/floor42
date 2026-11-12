# ADR-0012: File tools confined to the agent's worktree

Date: 2026-10-20 · Status: Accepted

## Context
Through I4, real agents could only read: writing was forbidden until "isolation" (I6). Two facts changed the plan:

- The director's machine has neither a WSL distribution nor a container engine. Installing either is a machine-level decision for the director (administrator rights, Docker Desktop license terms), not something Office should do on its own.
- Reading was set to "Auto" with no limit on *where*: an agent could have read `~/.ssh` or any file the director's account can reach without anyone being asked. The table said what tool, never what place.

## Options
| Option | What it isolates | Cost | Verdict |
|---|---|---|---|
| A. Confine file tools by path, in Office's hook | Read, Glob, Grep, Write, Edit (the tools whose target Office sees) | No install; a check per call | **Taken now** |
| B. WSL2 distribution, sessions run inside it | Everything, shell included, at the OS level | Install and admin rights; Windows paths and worktrees bridged into Linux; Claude Code installed and logged in twice | Next step (I6b), the director decides |
| C. Containers (Docker/Podman) | Everything, with mounts and network controlled | Install, licensing for Docker Desktop, image upkeep; the spec warns that Docker alone is not a guarantee | Alternative to B |
| D. Keep writing forbidden | Nothing new | None | Rejected: agents could never deliver code |

## Decision
- File tools (read and write categories) only reach paths inside the agent's worktree. The check resolves paths on the real file system, so a symbolic link or junction inside the worktree that points elsewhere counts as elsewhere. Paths starting with `~`, invalid paths and unresolvable paths are refused (fail closed); glob patterns that are absolute or climb with `..` are refused.
- Writes never touch `.git`, `.claude` or `.mcp.json`, even inside the worktree: they change what runs later outside the session (git hooks, Claude Code settings, MCP servers).
- The check runs before the permission table is consulted and the table cannot override it: such a call is denied with reason `OUTSIDE_WORKSPACE`, recorded like any other denial.
- With that guard, the starting plan asks once for Write and Edit instead of forbidding them. The reviewer gets explicit rules denying writes, shell and network: its independence is enforced, not just implied by the tools it is offered.

## Consequences
- Agents can now change code, in their worktree only; the director's checkout is untouched and the changes stay on the branch `office/<mission>-<agent>`.
- The shell is **not** confined: a command can read or change anything the director's account can. Bash therefore stays "ask for each exact command", the table warns when it is set to Auto, and the desk says it in plain words. This is the remaining gap that B or C closes.

- Verified on 2026-10-20 with a real mission on a sandbox repository ("add a CONTRIBUTING.md, and tell me which folders sit next to the repository"): the producer wrote `CONTRIBUTING.md` in its worktree after one approval; its Glob on the parent folder was refused as `OUTSIDE_WORKSPACE`, and so was the reviewer's; the director's checkout stayed untouched. Both agents reported the refusal honestly instead of guessing. About 0.047 USD estimated.
- A test reads and writes through a real junction (Windows) or symbolic link pointing outside the worktree: both are refused.

## Reopen if
The director installs WSL2 or a container engine (then do I6b), or an agent needs files outside its worktree for a legitimate reason.
