# Security policy

Floor42 drives AI agents that can read and modify files and run commands. Security reports are taken seriously.

## Reporting a vulnerability

**Please do not open a public issue for a vulnerability.** Use GitHub's private vulnerability reporting on this
repository ("Security" tab, "Report a vulnerability").

Especially relevant:

- a way for an agent to act beyond the permissions of its mission, or to change its own permissions;
- a way for a web page or another local process to drive the Floor42 daemon;
- secrets or credentials leaking into logs, reports, the journal or the UI;
- a simulated event being presented as a real execution.

## Supported versions

Only the latest release receives security fixes.

Releases come with a signed build provenance and software bills of materials; how to verify them is in [docs/releases.md](docs/releases.md).

The threat model, with the code that enforces each control: [docs/threat-model.md](docs/threat-model.md).

## What Floor42 enforces

- **The daemon is local.** It listens on `127.0.0.1` only. Every request must name a loopback Host (against DNS
  rebinding); a request a proxy forwarded (`X-Forwarded-For`, `X-Forwarded-Host`, `Forwarded`) is refused, and a
  remote daemon is reached through an SSH tunnel only ([docs/remote.md](docs/remote.md)). The API needs the director:
  a browser session (HttpOnly, SameSite=Strict cookie), a script token, or the director's token as a bearer header.
- **Only the daemon's own page drives it** (ADR-0044). SameSite does not tell ports apart, so a page on another local
  port gets the cookie sent along: an Origin must be exactly the daemon's (scheme, host and port), a browser request
  that changes something or opens a WebSocket must name its Origin, and one that changes something must also carry
  `X-Floor42-Request: 1`, which another origin cannot set without a CORS preflight the daemon never grants. The page
  runs under a strict Content-Security-Policy (no inline script, no eval, no plugin, never framed; the only inline
  exception is the terminal's own `<style>` elements), with `nosniff`, no referrer and `X-Frame-Options: DENY`.
- **No director credential at rest** (ADR-0034, ADR-0044). The director's token lives in the daemon's memory. The
  browser signs in with a single-use, 60-second link that the daemon opens itself in your default browser
  (`POST /auth/open`; in Docker it is printed on the container's output, read from the host); the caller never
  receives it. A link used twice revokes the session it opened. A browser session ends after 8 h unused or 24 h;
  System > Sessions and tokens lists every session, ends one, signs out, or signs out everywhere (which also replaces
  the director's token). Scripts use a token issued there: shown once, scoped (`read`, `missions:write`, `all`, enforced
  by the access guard), at most 30 days, gone at the next restart; the journal keeps only its SHA-256.
  `FLOOR42_DIRECTOR_TOKEN_FILE=true` is deprecated: it still writes the token to `~/.floor42/session.token`, where
  unconfined agents can read it.
- **Agents have their own credential, never the director's.** Each agent session gets a random credential, in its
  process environment only, that opens its own hooks and Floor42's MCP server and dies with the session. Presented to
  any director route, it is refused with 403.
- **Shell commands never reach Floor42 itself.** Whatever the table says, a command that names Floor42's data directory
  outside the agent's own worktree (journal, the director's token, the installed daemon, other agents' work), or that
  stops the daemon, is denied; so is a `git push` or any call of `gh`. After an upgrade from Office, its former data
  directory (`~/.office`, kept as the backup with the old journal and earlier worktrees) is guarded the same way. This reads the command's text: it stops mistakes and plain attempts, not a command
  written to hide what it touches. The sandbox below is the boundary.
- **Permissions are enforced by code.** Every tool call goes through Floor42's fail-closed hooks and the mission's
  permission table (auto, ask once, ask every time, forbidden). A hook that cannot reach the daemon denies.
- **Agents work in their own git worktree**, on their own branch. File tools cannot leave that worktree, whatever the
  table says. The director's checkout and branches are never touched, but for the director's own files: on the director's click,
  Floor42 commits files they give a mission on their branch. Accepted work is a commit on the agent's branch.
- **No credentials pass through Floor42.** Agents run your own, unmodified Claude Code, signed in with your account.
  Floor42 never calls a model API and never stores an API key.
- **A pull request you confirm leaves your machine** (ADR-0036); so do the plugin installs and marketplace refreshes
  below, also on your click only. Everything else Floor42 does stays local.
  On your click in the workshop, Floor42 pushes one branch of the mission to the repository's remote and opens a pull
  request with your own `gh`. What bounds it:
  - only you can trigger it: director routes under `/api`, refused by the domain for any agent and for Floor42 itself;
    no MCP tool and nothing in agents' briefs offers it, and an agent's shell command may not `git push` or call `gh`;
  - nothing is pushed before you read remote, branch, base, title and body in a confirmation dialog, and Floor42 pushes
    only what that dialog showed;
  - never a force push (a plain refspec, no `+`, no `--force`), never `main`, `master` or the default branch as head;
  - Floor42 stores no GitHub token and never signs in: it uses your existing `gh auth`, and only tells you to run
    `gh auth login` when gh is signed out;
  - after an error or a timeout it looks on GitHub before offering a retry, so a retry never opens a second one.
  What it cannot prevent: gh acts with your account's full rights, and your own git hooks run on the push.
- **Plugins are installed only on your click** (ADR-0040), with Claude Code's own `claude plugin` commands, after a
  confirmation that shows the source, publisher, version, licence, contents and the exact commands; reading a
  marketplace's listing from GitHub happens only on "Refresh the marketplace", and installing fetches the plugin from
  its source like Claude Code always does. Agents cannot: their credential is refused on these routes, the domain
  refuses them, and their shell may not run `claude plugin` or `claude mcp`. A mission's sessions receive only the
  skills and agents you chose, copied by Floor42, never a plugin's hooks, MCP or LSP servers. What it cannot prevent:
  a plugin installed **into your own Claude Code** loads in your own sessions with its hooks and MCP servers, as any
  plugin you install yourself.
- **Your own terminal is yours, not an agent** (ADR-0041). The Terminal panel runs your own Claude Code with your
  rights and settings: no Floor42 hook, permission table, budget or cap applies to it, and the panel says so. What
  Floor42 enforces around it: only the director opens it (the API and `/ws` guard; an agent's credential gets 403; the
  screen also needs a single-use ticket and a browser Origin), only on a click, one per project; nothing is typed into
  it but what your browser sends (keystrokes, pastes, and a command the skills catalogue prepares on your click,
  from an allow-listed name, never with Enter: ADR-0041's amendment); its environment holds no Floor42 variable, no director token, no agent credential;
  closing it stops its process tree; only "opened" and "closed" are journaled, never what is typed or printed. What it
  cannot prevent: whoever drives your signed-in browser can type into it.
- **Spending is capped** by per-mission budgets and the plan's usage windows; a session that would exceed them waits
  for the director.
- **Origins are explicit.** Every event says whether it is real or simulated; the simulated demo calls no model and
  runs no command.

## What Floor42 does not enforce

- **Shell commands are confined by default for real missions, not in unsafe mode, in Docker or on macOS.** Outside
  development and test the sandbox is mandatory (`floor42.sandbox.mandatory`, ADR-0063): without it a real session does
  not start unless unsafe mode is switched on. Where no sandbox runs, a shell command an agent is allowed to run
  executes with your user's rights: it can read your files and act as you. This happened in a real mission: an agent allowed to run Bash
  read the director's token (then a file), called the API as the director and stopped the daemon. The token no longer
  sits in a file, but a process running as you can still, with effort, read another process's memory or your
  browser's cookie store, or catch a sign-in link on its way to the browser: Floor42 does not defend against that. Keep
  shell commands on *ask* unless you trust the mission, never let an agent deploy Floor42 itself, and confine them:
  - on Windows, the `wsl` shell sandbox setting runs them in a WSL distribution that sees only the worktree; on Linux
    the same script runs natively (measured on Debian 13 with a WSL 2 kernel only);
  - with Docker ([`docs/docker.md`](docs/docker.md)), they run in the container, which sees only the folder you mount.
- **Inside the container, or without a sandbox, agents run as the daemon's own OS user** (ADR-0044, checked by
  `[fake:forge]` and in a built container). They can **write Floor42's journal**: a forged row takes effect at the next
  start. No credential is rebuilt from the journal, but a forged permission or approval would count (debt D16). They can
  **read Claude Code's sign-in** (`~/.claude`): they are Claude Code and need it. On Linux and in Docker, the daemon
  makes itself non-dumpable, so they cannot read its standard output (where Docker's sign-in link goes), its
  environment or its memory. Running agents under a uid of their own would close the journal,
  but needs root in the container and your repositories writable by that uid: not done.
- **What the daemon reads at its next start is kept out of their reach where Floor42 can do it**. Agents
  can stop the daemon, and in Docker `restart: unless-stopped` starts it again, so without these defences they could change what
  it does next. Each of these was shown on 2026-11-01 in a container of a private build that predates v1.0.0 and had
  none of them ([docs/docker.md](docs/docker.md)):
  - a planted `restore-pending.properties` rolled the journal back. Now a restore is prepared only through the
    director's API, kept in the daemon's memory and applied during its own orderly stop. A start never restores; a
    planted file is refused, kept aside, logged, and shown in a red banner;
  - a planted `.env` or `config/application.properties` in the working directory (`/home/office`) turned the
    non-dumpable protection off. Now the image runs the daemon from the root-owned `/opt/floor42`. Anywhere, a `.env` or
    a `config/` folder in the daemon's working directory refuses the start (`ConfigGuard`). So does a journal URL other
    than the data folder's, an added JDBC driver property, or SQLite's extension loading, and the open connection is
    asked to refuse `load_extension()`. `/api/status` reports whether the daemon is non-dumpable, asked of the kernel;
  - `/opt/floor42` belonged to uid 1000, so agents could replace the jar or a library. Now the application is
    root-owned (0755/0644) and only the `office-home` volume is writable by uid 1000;
  - the JVM's attach mechanism was open: `touch /tmp/.attach_pid1; kill -QUIT 1` made the daemon open an attach
    socket through which any same-uid process could load code into it (non-dumpable does not cover attach). Now the
    image, `bin/floor42`, `bin/floor42.ps1` and the dev launchers (`FLOOR42_DEV_ATTACH=1` opts out) start the JVM with
    `-XX:+DisableAttachMechanism`, and `/api/status` reports `process.attachDisabled` as the JVM says it;
  - moving an older copy over `floor42.db`, then a restart, replayed the old journal silently. This one is **detected,
    not prevented**: the file stays theirs to replace (D16). The stop keeps the journal it still held as `displaced-*`,
    and the next start compares the journal with what the last run saw (event count, last sequence, a hash of the last
    row, in `journal-continuity.properties`). Fewer events or a different last event give a red banner until you
    acknowledge it; so does a record that is missing beside a journal holding events, or unreadable. The record is
    measured through a read-only connection the daemon holds on the file it opened, so an idle pool reopening its
    connections on the swapped file changes nothing. Writes are not refused: the record is a file they could rewrite
    too, so a refusal would prove nothing against a forger (and a lock on `floor42.db` already stops the office);
  - the outcome of a restore that the next start reports, and the copy prepared for one, could be swapped. The outcome
    is now shown as reported by a file, never as checked. The copy's SHA-256 is taken when you prepare it; the stop
    reads the copy once into memory and restores those very bytes, so a copy that changed is refused and no file stands
    between the check and the restore. `migrated-from-office.properties`, which decides which folders are guarded, is
    read once at start.
- **On Windows and Linux without Docker, the installed daemon is the user's own files.** `bin/floor42.ps1` and
  `bin/floor42` run it from a copy in `~/.floor42/app`, which belongs to you, from `~/.floor42`. Any process of your
  user can replace that jar, edit the launcher, or write the data folder: an unconfined agent's shell command can. The
  working-directory configuration is refused there too, and `ShellGuard` denies commands that name the data folder,
  but that reads the command's text: it stops plain attempts, not a determined one. Only the shell sandbox (WSL on Windows,
  native on Linux) or the Docker container confine agents away from the installed daemon. Without them, keep shell commands on *ask*.
  This holds for any installed version on Windows. On Windows, any process of your user can
  open the daemon's process and read or write its memory (`OpenProcess` with the user's own rights), inject a thread,
  or run `jcmd` against a JVM that allows attach. There is no non-dumpable equivalent. `-XX:+DisableAttachMechanism`,
  which the launchers now pass, closes only the JVM's own attach (`jcmd`, loading a Java agent), the easy door. It does
  not stop a native debugger or memory reads. On Windows the WSL sandbox or Docker is the confinement, nothing less.
- **Another program can hold the journal.** Floor42 does not stop a process of your user from opening `floor42.db` (a
  `sqlite3` shell, a database browser). While it holds a lock, appends fail after SQLite's 3 s busy timeout with
  `JOURNAL_LOCKED` and a red banner; nothing is recorded until it lets go ([docs/backups.md](docs/backups.md)).
- **A script token is a bearer secret** for as long as it lives: whoever reads it where your script keeps it acts with
  its scope. Prefer `read` or `missions:write`, and short lives. `missions:write` can answer the questions of a mission,
  approvals included.
- **The network is not confined** inside the container; the permission table forbids network tools by default.
- **Review what you accept.** An independent review by another agent catches mistakes, not a determined attacker
  writing into your repository.
