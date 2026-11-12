# ADR-0041: The director's own Claude Code in a terminal, through a pseudo-terminal on the JDK's FFM API and xterm.js

Date: 2026-10-31 · Status: Accepted

## Context
The director wants a terminal in the UI where he can type what he types in Claude Code. He decided what it is: a full
interactive Claude Code session in the chosen project, with **his** rights (not an agent's), paid calls only from his own
keystrokes, a cost counter, and every slash command working (`/context`, `/cost` and the others). Floor42 already drives
Claude Code headless for its agents (`-p --input-format stream-json`, `ClaudeCodeRuntime`); the question was whether
that mode would do, or whether a real terminal is needed, and then how a Java daemon hosts one on Windows.

## Options

| Option | Measured or observed cost | Verdict |
|---|---|---|
| A. A pseudo-terminal (ConPTY on Windows) running the interactive `claude`, streamed over a WebSocket to xterm.js | 782 lines of Java on the JDK's foreign function API, no Java dependency; xterm.js 6.0.0 (MIT, 5.9 MB unpacked, 345 KB `xterm.mjs`) and its fit addon 0.11.0 (MIT, 21 KB). Checked: Windows 11 (build 26200) through ConPTY and WSL Debian with OpenJDK 25 through `posix_openpt`: the fake sees `tty=true`, echoes, takes a resize, its child process dies with it, an exit code comes back (3), the reader gets end of file | **Chosen** |
| B. `claude -p --input-format stream-json` and a chat box | The `system/init` line of a real Floor42 session on Claude Code 2.1.316 (`~/.office/sessions/973415d9…/transcript.jsonl`) lists **54** slash commands; the commands reference (code.claude.com/docs/en/commands, read 2026-10-31) lists **112** commands and bundled skills. **68 are absent** from headless mode, among them `/cost`, `/help`, `/status`, `/permissions`, `/resume`, `/memory`, `/hooks`, `/rewind`, `/plugin`, `/skills`, `/login`, `/statusline`, `/theme`, `/vim`, `/exit`. No TUI either: no permission dialog, no `/context` grid, no Esc to interrupt, no Shift+Tab mode switch. (That session ran under `--restricted`, which may hide more; it is still the mode option B would use.) | Rejected: `/cost` alone, named by the director, is missing |
| A1. pty4j for the pseudo-terminal (`org.jetbrains.pty4j:pty4j`) | On Maven Central, maintained by JetBrains (0.14.0 released 2026-10-02, 435 stars, not archived), Windows via ConPTY. **Licence EPL-1.0**, which the FSF lists as incompatible with the GPL: Floor42 is AGPL-3.0 (ADR-0008), so shipping it would need an additional permission from the copyright holder. Size measured on Maven Central: pty4j 2.8 MB + JNA 5.19.1 1.9 MB + JNA Platform 1.4 MB + Kotlin stdlib 2.4.20 1.9 MB, about 8 MB, and a Kotlin runtime in a Java daemon | Rejected: the licence decides; the size and the Kotlin runtime confirm |
| A2. The JDK's own FFM API (`java.lang.foreign`, final since JDK 22) | The code above: `CreatePipe`, `CreatePseudoConsole`, `InitializeProcThreadAttributeList`/`UpdateProcThreadAttribute`, `CreateProcessW`, `ResizePseudoConsole`, `ClosePseudoConsole` on Windows; `posix_openpt`, `grantpt`, `unlockpt`, `ptsname_r`, `ioctl(TIOCSWINSZ)` and `setsid` on Linux. Floor42 owns native code it must keep right; no macOS | **Chosen** |

## Decision
- **A real terminal.** `runtime/pty`: `Pty.start(command, directory, environment, columns, rows)`; ConPTY on Windows
  (the standard handles are nulled with `STARTF_USESTDHANDLES`: a daemon whose own output is redirected would pass them
  on), a Unix 98 pseudo-terminal on Linux started through `setsid -w /bin/sh -c 'exec 0<>"$0" 1>&0 2>&0 && exec "$@"'`
  so that the program, not the daemon, makes it its controlling terminal (the daemon is PID 1 in Docker). The packaged
  jar declares `Enable-Native-Access: ALL-UNNAMED`; tests run with `--enable-native-access=ALL-UNNAMED`.
- **The director's own Claude Code.** `floor42.terminal.command` (default `claude`), started in the folder chosen,
  with no `--restricted`, no `--strict-mcp-config`, no `--tools`, no hook, no brief, no prompt. Floor42 writes nothing to
  it but the bytes the director's browser sends (keystrokes, pastes) and the window size: paid calls come only from his
  keystrokes. Its environment is the daemon's without every `FLOOR42_*` and `OFFICE_*` variable, any value holding the
  director's token, and the markers `CLAUDECODE`, `CLAUDE_CODE_ENTRYPOINT`, `AI_AGENT` (and, since the amendment
  below, `CLAUDE_CODE_CHILD_SESSION`, `CLAUDE_CODE_SSE_PORT` and any `CLAUDE_CODE_*` naming a session or a parent);
  plus `TERM=xterm-256color`.
- **The one thing added: the cost counter.** `--settings <terminal folder>/settings.json` holding a single key,
  `statusLine`: a command that copies the JSON Claude Code gives its status line to `status.json`, then runs the
  director's own status line command (read from the project's `.claude/settings.local.json`, then
  `.claude/settings.json`, then `~/.claude/settings.json`, as Claude Code picks it), unchanged, so his bar still shows.
  Floor42 reads `cost.total_cost_usd` once a second and shows it as "cost reported by Claude Code" (Claude Code's own
  estimate at list price, not Floor42's measure). `floor42.terminal.cost-from-status-line=false` removes even that. On
  Windows Claude Code runs status lines through Git Bash (else PowerShell): the copy is POSIX shell, so the counter needs
  Git Bash, which Claude Code's Bash tool needs anyway.
- **Director only, on his click.** `GET/POST /api/terminals`, `POST /api/terminals/{id}/attach`,
  `DELETE /api/terminals/{id}`; the screen on `/ws/terminal/{id}`. Both are under the access guard (an agent's
  credential gets 403); the WebSocket also needs a single-use ticket returned by the POST (60 s) and a browser Origin,
  so a page of another loopback port, which shares the SameSite cookie, cannot attach. Nothing starts at boot; one
  terminal per project (409 `TERMINAL_OPEN`, also a rule of the domain).
- **Journaled facts only.** `TerminalOpened(terminal, project)` by the director, `TerminalClosed(terminal, project,
  reason, exitCode)` by the director (`closed`) or Floor42 (`exited`, `interrupted` at the next start when the daemon
  stopped with it open), Origin REAL. What is typed or printed is never journaled, logged or written: the last 256 KB of
  the screen stay in memory for a browser that reattaches.
- **Closing kills the tree.** The descendants, then the process, then the pseudo-console; also at daemon shutdown.
- **UI.** A Terminal button in the top bar opens a panel: project from the recent ones (the active mission's first) or a
  typed path; xterm.js with a fixed monospace font, fit to the panel when it is resized, Copy and Paste buttons and
  Ctrl+Shift+C / Ctrl+Shift+V (Ctrl+C stays Claude Code's interrupt; the page's own shortcuts, Escape included, are off
  inside it). Labelled "your own Claude Code, not an agent, not governed by Floor42". npm versions pinned exactly.

## Consequences
- The director can now spend his plan from Floor42's page, outside any budget, cap or permission table of Floor42's:
  that is what he asked for, and the label says it. SECURITY.md says what this terminal is and is not.
- Anyone who can drive the director's browser session can type into his Claude Code: the same exposure as the rest of
  the director's API, now with a shell behind it. The ticket and Origin checks narrow it to his own pages.
- No macOS (no `setsid`, and BSD needs `TIOCSCTTY`): `GET /api/terminals` says why. Docker works (Linux, util-linux).
- Tests drive `FakeTty.java` (a fake interactive program, never Claude Code, never a model) through the real
  pseudo-terminal: tty seen, echo, `/cost` line, resize, the child process killed on close, the exit code recorded.
  The real `claude` in the terminal and the status line copy through Git Bash were not checked by hand yet.

## Signal to reopen
Claude Code ships a supported protocol that carries the whole interactive session (every slash command, permission
dialogs); a Windows or JDK change breaks the ConPTY calls; the director needs macOS; or pty4j changes its licence.

## Amendment (2026-11-02): the catalogue prepares a command in the terminal
The director decided that a click on a skill or an agent in the skills and agents catalogue opens his terminal and
types the command for him, without Enter: `/<skill-name> ` for a skill, `Use the <agent-name> agent to ` for an agent
(`plugin:name` for one read from a plugin). He reads it, completes it and validates it himself.
- **The only bytes Floor42 itself puts in the terminal** are this prepared command. They are typed through the
  director's own browser, on the same WebSocket as his keystrokes, after his click (a script's synthetic event is
  refused: `isTrusted`), never on any other trigger, never with a carriage return or a line feed. No new API: the
  request lives in the page (`state/terminalPrefill.ts`), so no other page and no agent can ask for it. What is typed
  is still never journaled, logged or written.
- **Built only from the catalogue's name**, checked against an allow-list (`ui/prefill.ts`): a letter, then letters,
  digits, `.`, `-`, `_`, at most 64 characters, one `plugin:` prefix at most. Never a digit first: typed into a
  selector, a digit could pick an option. Any other name (a control character, ESC, CR, LF, the bracketed-paste end
  `ESC[201~`, U+2028, U+2029, anything outside ASCII) is refused and its button disabled. A skill named like one of
  Claude Code's own commands (`clear`, `exit`, `logout`, `resume` and the others listed there) is refused too, with
  the reason under its button. The text is checked once more just before it is sent: printable ASCII only.
- **The project** is the item's own (a project-level item: the mission's repository), else the terminal on screen,
  else the last one shown, else the panel's default. The open terminal of that project is reattached; with none, one
  is opened as before (one per project, on the director's click: this click).
- **Typed only on Claude Code's empty input box, default deny** (the director's choice). A quiet screen is not enough:
  the folder trust screen and a permission dialog are static. The page reads the visible screen (xterm.js's buffer)
  and types only when it recognises Claude Code's input box exactly: two full rules (or the `╭─╮`/`╰─╯` box of 1.x)
  around one line that is the prompt character (`>` or `❯`) then at most what the director typed (dim or italic text
  there is Claude Code's placeholder suggestion, read from the cells' attributes, and counts as empty), then anywhere
  below (the director's status line may come first, any length) one of the footers Claude Code draws ("? for
  shortcuts", the permission-mode lines such as "auto mode on (shift+tab to cycle)"), and below the box no cursor or
  option line, no "Esc to cancel", no "Enter to confirm", no numbered option, no other box. Any other screen gets nothing typed: on a
  dialog at once, on an unrecognised screen after 20 s; the command goes to the clipboard when the browser allows it,
  and the panel says to answer or check the screen, then paste (or type) it.
- **Never clobbered.** The page tracks whether the director typed on the line since his last Enter or Ctrl+C, and reads
  what the screen shows on the input line. When the line holds something, or when it cannot know (a terminal opened
  before this page), it asks him first, then reads the screen again (it may have changed while he read), and the
  command goes after a space. One preparation runs at a time: a second click meanwhile is refused (two quick clicks
  would otherwise type "/a /b " without asking). The turn has an owner (a token): only its holder releases it; a turn
  left for a minute goes to the next click, and the older preparation, still waiting on its question, types nothing.
- Checked by the coordinator against the real Claude Code 2.1.316 (2026-11-02): on the folder trust screen nothing
  was typed and the clipboard hint showed; its input screen, as read there, is a test fixture that must be ready (the
  placeholder marked dim), and its trust screen one that must not. If its drawing changes, the cost is nothing typed
  (the command handed over), not a wrong keystroke. The same check found the terminal inheriting
  `CLAUDE_CODE_CHILD_SESSION` from the daemon (transcript saving off): it is dropped now, with any `CLAUDE_CODE_*`
  naming a session or a parent and `CLAUDE_CODE_SSE_PORT`. The browser test drives the fake terminal
  (`FakeTty.java --claude-prompt`), which draws that box and footer and, on `ask`, a trust-like question.

