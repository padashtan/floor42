# ADR-0063: The WSL sandbox is mandatory for REAL missions; unsafe mode is a journaled exception

Date: 2026-11-05 · Status: Accepted 2026-11-04 (the director's decision), implemented on `debt/d16`; pays D16. The new
event type `UnsafeModeChanged` is part of the director's decision.

Note 2026-11-11: since 1.17 the sandbox also runs natively on Linux (ADR-0028, amended 2026-11-09), so Linux no longer
needs unsafe mode. Native Linux is measured on Debian 13 (WSL 2 kernel) only. The text below is the 2026-11-05 record.

## Context
D16: an agent's shell running as the daemon's own OS user can open Floor42's journal for writing, and read credentials.
A forged row takes effect at the next start. Detection exists (ADR-0044), prevention did not: the sandbox (ADR-0028) was
a setting, off by default, and a REAL mission ran its shell on the machine as is when it was off.

## Evidence (measured on the director's machine, 2026-11-05)
- WSL 2 with the `Debian` distribution is installed. A plain `wsl.exe -d Debian --exec` command runs as `uid=1000`
  (a normal user, in `sudo` and `docker`), and `/mnt/c` is `drwxrwxrwx`: a temporary file next to the journal under
  `/mnt/c/Users/<user>/.floor42` was created, then deleted. So outside the sandbox, a Linux user does not protect
  anything: WSL mounts the Windows drives writable for whoever runs in it, `~/.floor42` included.
- Inside the sandbox (`Sandbox.RUN`, read in full): `unshare -Urm -C -i -u -p -f --mount-proc`, then one user
  namespace deeper `unshare -Urm` with `setpriv --no-new-privs`, `prlimit` and a seccomp filter. It mounts an empty tmpfs
  over `/mnt`, `/run`, `/home`, `/root`, `/tmp`, `/var/tmp`, `/sys`; the only writable project path is the worktree,
  bind-mounted at `/media/work` (plus the worktree's own git directory; the repository's is read-only). The kernel locks
  those mounts against the command (it is a user namespace deeper), so `umount` and `mount --move` fail.
  `SandboxEscapeTest` attacks exactly this (`[ -e /mnt/c/Windows ]`, `umount /mnt`, `nsenter` into other processes). The
  journal, the vault, the settings and the other missions' worktrees are not visible there, so neither written nor read.
- The gap was therefore not what the sandbox does but when it is used: `SHELL_SANDBOX` defaulted to `off`, and
  `SessionTally` rewrote and guarded a Bash call only when the setting was `wsl`.

## Decision
1. **A REAL mission's shell runs only in the sandbox.** `SandboxPolicy.sandboxRequired()` (true unless unsafe mode is
   on, and `floor42.sandbox.mandatory` is true, its default) makes `Sandbox.enabled()` true whatever `SHELL_SANDBOX`
   says. Every caller that already asked `enabled()` (the hook rewrite, the per-call fail-closed refusal, the checks,
   the node tests, IAM mode's `IAM_NEEDS_SANDBOX`) follows, so IAM mode stays consistent: unsafe mode on and the setting
   off means IAM mode refuses to start, as before.
2. **No sandbox, no session.** `ClaudeCodeRuntime.run` (REAL origin only) asks `SessionTally.startRefusal()` before it
   mints a token: when the sandbox is required and does not work, the session fails with `SANDBOX_REQUIRED: ...`, on
   the mission, and nothing is launched.
3. **Unsafe mode**, the explicit exception: `PUT /api/unsafe-mode {on, reason}`, a reason required to switch on,
   director only (the domain refuses another actor and a SIMULATED origin; the access guard refuses a token narrower
   than `all`). Shown in red in the settings (EN and FR texts in `unsafe.*.ts`). Every switch is journaled by the new
   event `UnsafeModeChanged(on, reason)`, Origin REAL, so the weakened state is never silent. Off by default.
4. **Event type**, readers before writers: reader and writer ship together in 1.17 (as `AgentTokenIssued`); 1.16 would
   set the row aside (`NEWER_VERSION`); `RollbackEventTypesTest` lists it for 1.17.
5. **Fakes are exempt.** `floor42.sandbox.mandatory` is false in the `test` and `dev` profiles (the fake Claude Code never
   reaches a real shell); `bin/floor42-dev --real-claude` turns it back on.

## Not done, and why
- **No dedicated Linux user.** The design asked for one. The measure shows it would add nothing: the protection is the
  mount namespace (nothing of `/mnt/c` exists inside), not the uid, and a distribution user is writable-everywhere on
  `/mnt/c` as soon as it is outside the sandbox. Creating one would also need an install step run as root in the
  director's distribution. Nothing was changed in WSL.
- **Unsafe mode, and the Docker image,** still run the shell as the daemon's user: D16 is closed by making that state
  explicit, refused by default and journaled, not by making it safe. A setting `SHELL_SANDBOX=off` is ignored while
  the duty holds. The agents' briefs still describe the sandbox only when the setting is `wsl`.
- The sandbox needs `python3`, `unshare`, `setpriv` and `prlimit` in the distribution (ADR-0028); a machine without WSL
  cannot run a REAL mission until the director installs it or switches unsafe mode on.

## Consequences
A fresh install with no WSL refuses real missions with a code the director can read, and one click (with a reason)
lifts it. Simulated missions are unaffected.

## Amendment 2026-11-05 (pilot, node P1a): reviewers
A reviewer's shell (rerunning the producer's approved tests) is allowed only because this duty holds: it runs in the
sandbox, with the work mounted read-only, and is refused when the sandbox is off or does not work, even in a session
that would otherwise start. Unsafe mode never gives a reviewer a shell. See ADR-0046, same date.
