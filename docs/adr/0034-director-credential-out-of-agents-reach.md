# ADR-0034: The director's credential out of the agents' reach

Date: 2026-10-31 · Status: Accepted · Amends ADR-0007 · The token file for scripts is deprecated by [ADR-0044](0044-sessions-tokens-and-exact-origin.md)

## Context
The director's token was a file, `~/.office/session.token`. Agents run Claude Code as the director's own OS user, and with the shell sandbox off (the default, ADR-0028) an allowed shell command reads any file that user reads. On M-10 an agent did: it read the token, called the API as the director, replaced the installed daemon and stopped it. Whoever holds that token is the director for the whole API: answer questions, accept patches, change permissions. `ShellGuard` refuses commands that name the file, but it reads text: a path built in a variable, decoded at run time or read from a script file gets through. That was debt D12.

Agent sessions already had their own credential (`HookRegistry`, ADR-0027): random per session, passed to that process only in its environment (`OFFICE_HOOK_TOKEN`; the settings and MCP files name the variable, not the value), checked by `/hooks` and `/mcp`, removed when the session ends. It never opened `/api`, but nothing said so explicitly: it fell into the generic 401.

## Options
| Option | What it costs | What it holds against, same OS user on Windows |
|---|---|---|
| A. Keep the file, restrict it with an ACL | Nothing | Nothing: the ACL names the director's user, which is also the agents' user |
| B. Refuse director routes without a browser Origin or a cookie bound at sign-in | A check in the guard | Nothing: `curl -H 'Origin: http://127.0.0.1:7420'` forges the header, and the cookie value is the token read from the file |
| C. Name the caller's process (loopback port to PID, then its ancestry) | Native calls through FFM, per OS | Agents leave the tree easily (`start`, a scheduled task, WMI); brittle |
| D. No token at rest: it stays in the daemon's memory; the browser signs in with a single-use link that the daemon delivers itself, never to the caller | A sign-in route, the launchers, the login panel; scripts lose their bearer token unless they opt in | A plain read of any file, log or environment variable an agent reaches |

## Decision
D, with the agents' credential made explicit:
- The director's token is generated at each start and kept in memory. It is no longer written to a file, and a file left by an earlier start is deleted. `office.director-token-file=true` (`OFFICE_DIRECTOR_TOKEN_FILE`) brings the file back for scripts (bench, demo recording, `office "objective"`), with a warning in the log: it re-opens D12.
- `POST /auth/open`, open to any loopback caller without Origin or with a loopback Origin, issues a single-use code valid 60 seconds (at most one every 2 seconds, at most 5 pending) and **delivers it to the director, not to the caller**: the daemon opens `/auth?code=...` in the default browser (`rundll32 url.dll,FileProtocolHandler` on Windows, no shell), or, in Docker (`OFFICE_SIGN_IN_DELIVERY=stdout`), prints it on the container's standard output, which the host reads with `docker compose logs`. *Corrected by ADR-0044:* processes inside the container could read it too, through `/proc/1/fd/1`, since agents run as the daemon's own uid (checked in a container on 2026-10-31); since ADR-0044 the daemon makes itself non-dumpable on Linux, and the same check is refused. The answer is an empty 202. The link is printed with `System.out`, not the logger, and the API console masks `code=` like `token=`.
- Redeeming the code gives the browser its own random session (HttpOnly, SameSite=Strict cookie), in memory only, distinct from the token. A code used a second time is refused and the session it opened is revoked: someone used it first.
- `bin/office`, `bin/office.ps1` and `bin/office-docker` ask for a link instead of reading the file; the login panel has a button that does the same.
- An agent session's credential presented on any `/api` or `/ws` route gets **403**, as a bearer; as a cookie or a login token it is simply unknown (401). It keeps opening only its own session's `/hooks` and `/mcp`, and dies when the session ends.
- `ShellGuard` keeps its checks (they stop mistakes and plain attempts); security no longer rests on it.

## The attack it stops
An agent's shell command, unconfined, running as the director, that reads files, logs, the environment or the journal to find a credential and calls the API with it. Tested with the fake Claude Code (`[fake:steal]`): it tries every token-shaped string in the data directory, its worktree and its environment on a director route, as a bearer, a cookie and a login token or code; none opens it, its own credential gets 403, and asking for a sign-in link returns nothing. A control run with the token planted in the worktree finds it, so the hunt is not blind.

## The attack it does not stop
A same-user process determined to reach into another process: reading the daemon's or the browser's memory, decrypting the browser's cookie store (DPAPI belongs to the same user), watching new processes' command lines to catch the link on its way to the browser and redeem it first (the director then sees the sign-in fail, and the stolen session dies when the browser's own attempt arrives), or driving the screen and keyboard. An agent can also open sign-in tabs on the director's screen (rate-limited). Against these, the boundary is still the OS: the WSL sandbox (ADR-0028) or Docker, where the agents do not run as a process of the director's desktop.

## Consequences
- Signing in again is needed after each daemon restart, and in a browser that never signed in: `office` (or the panel's button) does it.
- Scripts that used `session.token` need the opt-in, and the docs say what it costs.
- `office "objective"` proposes a mission only with the opt-in; otherwise it says so, and the desk does it.

## Reopen if
Office gets a second user or a remote client (real authentication, ADR-0007), agents run under their own OS account, or the browser can no longer be opened by the daemon.
