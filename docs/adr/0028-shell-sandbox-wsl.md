# ADR-0028: Shell commands and checks isolated in a WSL2 sandbox

Date: 2026-10-29 · Status: Accepted, amended 2026-11-03 (sandbox escape fixed); no longer opt-in for REAL missions since 1.17, see [ADR-0063](0063-sandbox-mandatory-for-real-missions.md)

## Context
ADR-0012 confined the file tools to the agent's worktree, but not the shell: an approved Bash command, or the director's checks running code an agent wrote, could read or change anything the director's account reaches. That was debt D5. The director installed WSL2 (Debian 13) on 2026-10-29 and gave the go.

## Options
| Option | What it costs | Verdict |
|---|---|---|
| A. Run Claude Code itself inside WSL | Node and Claude Code installed and logged in a second time inside Linux; sessions, hooks and paths bridged | Rejected: heavy, and the file tools would move too |
| B. Keep Claude Code on Windows; Office's PreToolUse hook rewrites each allowed shell command to run in a WSL sandbox | One script; one `wsl.exe` start per command (about a second) | **Taken** |
| C. Containers (Podman, Docker) | An engine to install and keep; image upkeep | Not needed while B holds |

## Decision
- A company setting, `SHELL_SANDBOX` (`off` by default, `wsl`), and `SANDBOX_NETWORK` (`off` by default).
- When on, Office answers an allowed Bash call with `updatedInput`: the same command, base64-encoded so no shell re-reads it, run by `wsl.exe` through `run.sh` (written under the data directory). Measured on Claude Code 2.1.312 on 2026-10-29: a hook answering `allow` with `updatedInput` makes Claude Code run the rewritten command (probe: `echo ORIGINAL` ran as `echo REWRITTEN-BY-HOOK`; about 0.002 USD).
- `run.sh` starts unprivileged Linux namespaces (`unshare -Urm -p -f`, plus `-n` unless the network is on). Inside, the worktree is mounted at `/media/work` and is the working directory; `/mnt` (the Windows drives), `/run` (WSL's bridge that starts Windows programs), `/home`, `/root` and `/tmp` are replaced by empty ones. Measured on this machine: Windows drives and home folders invisible, no DNS, a Windows program copied into the worktree does not start, writes land in the worktree.
- Git works in the sandbox: when the worktree is a linked git worktree, the repository's git directory is mounted read-only (its hooks, config and other branches cannot be changed from inside) and the worktree's own git directory read-write; `GIT_DIR` and `GIT_WORK_TREE` point at them, and the director's `core.autocrlf` is passed on so Windows line endings read as the director's git reads them.
- The director's checks run the same way when the setting is on.
- Fail closed: when the sandbox is required and does not work (measured by running `echo` in it, again each time the director turns it on), no shell command and no check runs; the agent and the director are told why.
- The brief tells agents their shell is a Linux sandbox at their worktree, with or without network, and to say so when a tool is missing rather than work around it.
- `GET /api/status` reports `sandbox.enabled` and `sandbox.working`.

## Verified
- 2026-10-30, on a throwaway instance with Debian's git, OpenJDK 25, Maven and Node installed by the director: a real mission whose developer ran `git log --oneline`, `git status --short` and `mvn -v` through the sandbox: the commit listed, a clean status, Maven 3.9.9 on Debian's Java 25 (about 0.002 USD). A first run on 2026-10-29 had shown `git` failing on the worktree's Windows git path and `ls /mnt/c/Users` refused; that run led to the git mounts above.
- By hand through the real script: writing a hook in the repository's git directory and changing its config were refused (read-only file system); the repository was unchanged.

## Consequences
- With the setting on, Bash set to Auto no longer reaches the director's files: the warning about full shell access applies only when the sandbox is off.
- Commands only find the tools installed in the WSL distribution. A fresh Debian has `sh` and coreutils, not git, a JDK, Maven or Node: installing them is the director's decision (system packages).
- The file tools (Read, Write, Edit, Glob, Grep) are still confined by ADR-0012, not by the sandbox.
- Paths with spaces or quotes are refused for the sandbox (Office's own data directory has none).

## Amendment 2026-11-03: the escape and its fix

**The escape.** The masks over `/mnt`, `/run`, `/home`, `/root` and `/tmp` were mounted by the sandbox's own user namespace, and the command ran in that same namespace, as its root. The kernel does not lock mounts a namespace made itself, so a command could `umount /mnt` (the Windows drives back) and `umount /run` (WSL's `/run/WSL/*_interop` sockets back). WSL's binfmt handler for Windows programs is global to the VM and `-n` does not cut unix sockets, so `/mnt/c/Windows/System32/cmd.exe /c ver` then ran on the Windows host as the director. Verified on this machine with a read-only command (`Microsoft Windows [version 10.0.26200.9168]`). The same command could also remount the repository's git directory read-write and see `/run/docker.sock`. Every agent Bash call and every check in the sandbox was exposed.

**Options.**
| Option | Cost | Verdict |
|---|---|---|
| A. Run the command one user namespace deeper (`unshare -Urm` again, no fork) after the mounts | One more `unshare` per command, no new file tree | **Taken** |
| B. `pivot_root` into a minimal tree without `/mnt` or `/run/WSL` | Every tool the director installed in the distribution must be bound in one by one; a missed path breaks builds | Rejected while A holds |
| C. Turn interop off in `/etc/wsl.conf` (`[interop] enabled=false`) | The director's machine setting; breaks his own use of WSL | Not changed by Floor42; the director may add it (defence in depth) |

**Decision.**
- After setting up its mounts, `run.sh` enters a nested user and mount namespace. Mounts copied into a mount namespace owned by a less privileged user namespace are locked (`MNT_LOCKED`): `umount` fails (`EINVAL`), `mount --move` fails, the read-only git mount cannot be remounted writable, and the fresh `/proc` of the sandbox's PID namespace cannot be removed to show the VM's processes.
- A mask that fails to mount now stops the sandbox (no more `|| true`): fail closed.
- The sandbox mounts its own binfmt_misc instance (per user namespace, Linux 6.7 and later; WSL's kernel is 6.18): it is empty, so WSL's handler that hands Windows programs to the host is not applied inside, even if a socket were reachable. The registrations a command may add there run Linux programs inside the sandbox only.
- `setpriv --no-new-privs`; `prlimit --nproc` (default 2048, `floor42.sandbox.max-processes`) and `--fsize` (default 4 GiB, `floor42.sandbox.max-file-bytes`; drvfs writes land on the Windows disk); a wall timer in `init.py` (default 1800 s, or the caller's own limit for checks and proof steps, `floor42.sandbox.timeout-seconds`). The timer is the PID namespace's first process: when it ends, the kernel kills every process left in the sandbox.

**Verified 2026-11-03.** The repro above, through the new script: `umount: /mnt: not mounted.`, `/run/WSL` absent, `cmd.exe: not found`. `SandboxEscapeTest` (real WSL, skipped where it is absent) tries `umount` and `umount -l` of `/mnt`, `/run` and `/proc`, `mount --move`, every `/proc/*/root` and `/proc/*/cwd`, `nsenter` into every visible process, every `*_interop` socket found with `WSL_INTEROP`, a copied `cmd.exe`, registering a WSLInterop handler in binfmt_misc, and remounting the git directory writable; it asserts no way out is open and that a folder only a Windows program could create never appears. On the old script it failed on nine of these; on the new one it passes, as do the process, file size and wall time bounds.

**Second counter-check, same day: two more routes, both fixed.**
- *AF_VSOCK.* `-n` does not isolate vsock: from inside, `connect((2, 50002))` answered 9P2000.L, and attaching `drvfs;path=C:\` read and wrote all of `C:` (measured: Rattach, then a file created and written). Fix without installing anything (bubblewrap is absent): the command's first process is now `init.py` (Python standard library, written next to `run.sh`, copied into the sandbox before the masks). It sets no_new_privs and installs a hand-built seccomp BPF filter: architecture other than x86_64 kills the process, x32 syscalls get EPERM, `socket` keeps only AF_UNIX, AF_INET, AF_INET6 and AF_NETLINK (the last three stay inside the sandbox's own network namespace when the network is off; loopback is useful to tests), `socketpair` only AF_UNIX, and `io_uring_*` get ENOSYS (its socket operation is not seen by seccomp). The filter is inherited by every process and cannot be removed. The same process reaps orphans (with `timeout` as PID 1, orphans stayed zombies and counted against the process cap: 20 measured, 0 now) and kills every process at the wall time. The distribution must have `python3` (Debian has it); without it the sandbox fails closed and the readiness check says so.
- *Git pointers.* The worktree's git directory is writable, so a command could repoint `commondir`, the worktree's `.git` file or `config.worktree` at a git directory it wrote, with `core.fsmonitor` set to a Windows command; the next git on Windows (Floor42's, or Claude Code's own) ran it. Fix on both sides: in the sandbox, `commondir`, `gitdir`, `config.worktree` (created empty when absent) and the worktree's `.git` file are bind-mounted read-only (locked like the masks; index, HEAD and logs stay writable); in a plain repository, `.git/config` and `.git/hooks` too. On Windows, every git Floor42 runs gets `core.fsmonitor=false`, `protocol.ext.allow=never` and `diff.ignoreSubmodules=all` through `GIT_CONFIG_*`, which a git started in a submodule inherits: a nested repository recorded as a submodule (also measured: its fsmonitor ran from Floor42's `git status`) is no longer entered. Before running git in a worktree, Floor42 checks that `.git` points outside the worktree, that `commondir` names the repository that holds it and that `gitdir` points back; otherwise it refuses and logs a warning.
- `core.hooksPath` and `core.sshCommand` are not overridden. Hooks gate the commits the director accepts (`WorkshopHoldTest` pins it), and an empty `sshCommand` would break pushes; both are read only from places the sandbox cannot write any more (the repository's git directory, a plain repository's `config` and `hooks`, the links checked above).
- `SandboxEscapeTest` gained the vsock socket and connect, writing and moving each pointer file, and orphan reaping; `GitProcessTamperTest` (any machine with git) repoints `commondir` and `.git` and records a nested repository as a submodule, each with a planted fsmonitor. All failed before, pass after.

**Third counter-check, same day: five more routes, all fixed.**
- *The director's terminal (critical).* Every `wsl.exe --exec` process gets fd 5, a read-write `/dev/ptmx` master whose slave is the director's live login shell, outside every namespace; nothing closed it, so a command could type into that shell. `run.sh` now starts again with no descriptor but 0, 1 and 2 (`python3 -I` runs `os.closerange(3, SC_OPEN_MAX)` and execs `sh` on the script) before anything else, and `init.py` closes them again before it starts the command. Measured before: `/dev/ptmx` on fd 5 of PID 1 and of the command; after: no descriptor past 2 in any process of the sandbox. The same terminals were still reachable by path (`/dev/pts/*` was the VM's) until the fourth counter-check gave the sandbox its own `/dev`.
- *Freezing the timer.* The command, root of its own user namespace, could `PTRACE_SEIZE` and `PTRACE_INTERRUPT` PID 1, which then never killed anything at the wall time (measured: `State: t (tracing stop)`, the run outlived its 4 s limit). The filter now answers EPERM to `ptrace`, `process_vm_readv`, `process_vm_writev`, `kcmp` and `pidfd_getfd`, and `init.py` is not dumpable. After: `seize -1 errno 1`, killed at 4 s. Debuggers that use ptrace (strace, gdb) no longer work in the sandbox.
- *A `.gitmodules` with `ignore = none` (critical).* It beats `diff.ignoreSubmodules`, so Floor42's `git status` entered a nested repository planted in the worktree and ran its clean filter on Windows. `GitProcess` now adds `--ignore-submodules=all` after `status`, `diff`, `diff-files` and `diff-index` (the command line beats every configuration) and pins `submodule.recurse=false`. `git add` enters every populated submodule whatever the configuration or `.gitmodules` say (measured with `GIT_TRACE`; `commit`, `reset` and `stash` do not): Floor42 now refuses `add` when a gitlink of the index has a `.git` in the worktree. `git worktree add` never populates submodules, so only a planted repository is refused; an accept in such a worktree fails and says why.
- *HEAD.* It was writable from the sandbox: `git symbolic-ref HEAD refs/heads/main` made the director's accept commit on main. HEAD is now a read-only bind like the pointer files (agents cannot commit in the sandbox anyway: the objects are read-only), and the Workshop refuses to commit when the worktree is not on the mission's branch (`WRONG_BRANCH`).
- *Plain repositories.* A plain `.git` could receive a `commondir`, which `checkLinks` did not look at. It now refuses a plain `.git` that holds one, and the sandbox mounts a plain repository's `config`, `hooks`, `commondir` and `HEAD` read-only. No caller sandboxes a plain repository today (the probe's scratch folder is not one).
- Tests: `SandboxEscapeTest` gained the descriptor and terminal check, HEAD repointing, and the ptrace freeze; `GitProcessTamperTest` the `.gitmodules` case (clean filter, `diff.external` and textconv in the nested repository, with `status`, `diff`, `add` and `commit`) and the plain `commondir`; `WorkshopHoldTest` the accept on a worktree moved to main. Each failed before and passes after.

**Fourth counter-check, same day: five more, all fixed.**
- *The VM's devices.* `/dev` was the VM's: the director's live login shells (`/dev/pts/0`, `/dev/pts/1`), `/dev/ptmx`, `/dev/dxg`, `/dev/fuse`, `/dev/net/tun` and `/dev/vsock` opened read-write by path, `/dev/kmsg` read-only, and `/dev/tty` opened the director's terminal (every `wsl.exe --exec` process has it as controlling terminal). The sandbox now mounts its own `/dev`, locked like the other masks: a tmpfs with `null`, `zero`, `full`, `random`, `urandom` and `tty` bound from the VM, a new devpts instance (`newinstance,ptmxmode=0666`) with `ptmx` linked to it, a private `shm`, and `fd`, `stdin`, `stdout`, `stderr` linked to `/proc/self/fd`. `init.py` starts the command in a new session (`setsid`), so `/dev/tty` answers ENXIO. Before: nine devices opened; after: none, and a pseudo-terminal still works (`/dev/pts/0` of the sandbox's own instance).
- *git-lfs.* A committed `.gitattributes` (`filter=lfs`) and `.lfsconfig` (`lfs.url` at a listener) made Floor42's `git worktree add` and `git reset --hard` POST to that URL, network setting or not, and `filter.lfs.required=true` made them fail. Floor42's git now pins `filter.lfs.smudge` and `filter.lfs.process` empty, `filter.lfs.required=false` and `GIT_LFS_SKIP_SMUDGE=1`. The clean filter is kept (it works offline), so an LFS project's accepted files are still stored as pointers; the worktrees Floor42 makes hold pointers, not the files. Before: git-lfs reached the listener (`batch response: Repository or object not found: http://127.0.0.1:<port>/EXFIL/objects/batch`) and `worktree add` failed; after: no request, both commands succeed.
- *An untracked nested repository.* `git add --all` turned it into a gitlink, which then armed the director's own git. Floor42 now refuses `add` when `git ls-files --others` lists a folder that holds a repository (trailing slash). Before: the gitlink was staged; after: refused, nothing staged.
- *A planted `CHERRY_PICK_HEAD`.* The worktree's git directory is writable, so a command could leave `CHERRY_PICK_HEAD` (or `REVERT_HEAD`, `MERGE_HEAD`, `MERGE_MSG`, `SQUASH_MSG`, `sequencer`, `rebase-merge`, `rebase-apply`), and Floor42's commit took that commit's author. Floor42 now refuses to commit while any of them is there (it never leaves one: a conflicting merge is aborted). No explicit `--author` was added: without these files the author is the identity Floor42 sets. Before: the commit's author was the planted one; after: refused.
- *Plain repositories.* The `[ -e ]` test skipped binding a `commondir` the command could create afterwards. The sandbox now mounts a plain repository's whole `.git` read-only (git still reads; it cannot write the index there). The gitlink check before `add` now fails closed when git cannot list the index.

**Fifth counter-check, same day: two more, fixed.**
- *The VM's sysfs and cgroups.* `/sys` was the VM's, with its cgroup2 and no cgroup namespace: 169 files were writable, `cgroup.kill`, `cgroup.freeze`, `cgroup.procs` and `memory.max` of `user@1000.service` and `app.slice` among them, so a command could kill or freeze the director's WSL shells; debugfs, tracefs, configfs and fusectl were reachable too. The sandbox now runs in its own cgroup namespace (`unshare -C`: `/proc/self/cgroup` reads `0::/`) and mounts an empty read-only tmpfs over `/sys`, locked like the other masks; the VM's mounts under `/sys` still appear in `/proc/self/mounts` but cannot be reached. Java, Node, Maven, git and `nproc` work (measured: `openjdk version "25.0.5"`, Maven 3.9.9, `nproc` 16): they read no limit from an empty `/sys`, as in a container. Before: `sys-writable .../user@1000.service/cgroup.procs`, `cgroup-not-own 0::/non-systemd`; after: no file under `/sys`, `0::/`.
- *The environment.* `unset WSLENV` in `run.sh` came after wsl.exe had already forwarded what WSLENV listed: a fake `ANTHROPIC_API_KEY` and `FLOOR42_TOKEN`, and the desktop's `DISPLAY`, `WAYLAND_DISPLAY`, `PULSE_SERVER`, `DBUS_SESSION_BUS_ADDRESS` and `XDG_RUNTIME_DIR`, reached the command (WSL also adds `WSL_DISTRO_NAME`, `WSL2_GUI_APPS_ENABLED`, `PERF_EXEC_PATH`). The restart that closes descriptors now also builds the environment from an allow-list: LANG, LC_ALL, TERM and TZ are kept by name; `run.sh` then sets PATH (a fixed value), HOME (`/tmp`, the sandbox's own) and the GIT_* variables it documents, and unsets OLDPWD. The restart is keyed on a positional marker, not on a variable the environment could carry. On Windows, every wsl.exe Floor42 starts (the checks, the probe, the readiness check) gets a scrubbed environment (`Sandbox.scrub`: no WSLENV, no ANTHROPIC*, CLAUDE*, FLOOR42*, OFFICE*, AI_AGENT), and the agent's shell line starts with `WSLENV=`. Before: the fake key and token were printed by `env`; after: `env` shows HOME, LANG, PATH, PWD and TERM only.
- *Init's memory and environment.* The command runs as the same user as `init.py` in their user namespace, so it can read `/proc/1/environ` and `/proc/1/mem`. The user namespace maps one user only, so the command cannot run as another; init's environment is now the allow-list and it holds no secret. Accepted.

**Sixth counter-check, same day: small, fixed.**
- *SysV IPC.* Without an IPC namespace the command (the same kernel user as the director) could list, read and remove the director's SysV queues, shared memory and semaphores. The outer `unshare` now adds `-i` (IPC) and `-u` (UTS). Before: a queue made with `ipcmk` outside was listed inside; after: not.
- */var/tmp.* It was writable and persisted on the distribution's disk; it is now an empty tmpfs like `/tmp`. A whole-filesystem walk in `SandboxEscapeTest` finds nothing writable outside the in-memory masks, the private `/dev` and `/media` (the worktree and its git directory).
- *Markdown.* `floor42-web/src/ui/markdown.ts` appended a wrapped list line with a string replacement, so `` $` `` in the agent's text pasted the earlier rendered `<a href=...>` back in and added attributes (`onmouseover`; the daemon page's CSP blocks the script). It is now a function replacement; the other `.replace(` calls in `markdown.ts` and `outputs.ts` use constant templates, functions or removals. `markdown.inject.test.ts` parses the output with DOMParser for the reported payload and a small `` $` ``, `$&`, `$'`, `$1` corpus: no attribute but href, title, rel, target and class.
- *The agent's shell line.* `wrapForShell` joined paths unquoted into a Git Bash line; every argument after `sh` is now single-quoted (a quote in an argument is refused). Checked through Git Bash and WSL with a worktree named `` R&D;$(id)`id`x ``.
- `Sandbox.scrub` matches names case-insensitively (Windows names are).

**Residual risks.**
- The VM's sysfs and cgroup2 mounts nested under `/sys` stay in the mount table, read-only and unreachable behind the empty `/sys`; the VM's mount layout is visible in `/proc/self/mounts`.
- With the network on, the desktop's endpoints (X11, Wayland, PulseAudio, D-Bus) were reachable through the forwarded variables; the allow-list closes that path, but a command can still guess a socket or a TCP port on the host.
- Command output reaches Floor42 (the checks' output, the agent's transcript) with its escape sequences: Vue's text bindings show the raw characters without interpreting them, and the terminal panel is the director's own terminal, not an agent's output; `TerminalBlock.vue` renders Markdown as HTML and was not reviewed for this. A follow-up should check it and strip ANSI and OSC sequences before any output is rendered by a terminal emulator or copied to the director's clipboard.
- The director's own git or IDE opened on an agent's worktree does not get Floor42's settings: a nested repository recorded as a submodule there can run what its configuration names. Claude Code's own git on Windows passes `--ignore-submodules` and an empty fsmonitor and hooks path on its calls (measured by the counter-check).
- Junctions and symbolic links an agent creates in the worktree are followed by Windows readers other than Floor42's BlobSource (Claude Code's Read tool, the director's editor).
- Total disk written, memory and file count are not bounded; only one file's size, processes and wall time are.
- WSL's mirrored networking mode was not verified; the measures above are on the default NAT mode.
- With the network on (`SANDBOX_NETWORK`), the VM's abstract unix sockets (systemd's buses) and the host's network are reachable; interop stays closed (no path socket, no binfmt handler).
- Kernel bugs in user namespaces, devpts and seccomp are out of Floor42's reach.
- `git lfs` hooks installed in the director's repository (`post-checkout`, for file locking) still run on Floor42's `git worktree add`; with lockable files they may contact the URL a committed `.lfsconfig` names.
- `--fsize` caps one file, not the total written; a command can still fill the disk with many files up to the wall time.
- The limits bound one command; Claude Code may start several in parallel.
- Defence in depth for the director: `[interop] enabled=false` in the distribution's `/etc/wsl.conf` closes the bridge for every program of that distribution, Floor42's included.
- A proof step of ADR-0058 runs through the same arguments (`Sandbox.argvOffline` shares `tail`): the same masks, seccomp
  filter and limits, with the network always cut and no git folder mounted.

## Amendment 2026-11-09: native Linux backend (the director's decision, before v1.0.0)
Floor42 runs entirely on Linux with the same confinement. The operating system picks the launcher (`runtime/SandboxHost`):
Windows goes through `wsl.exe` as above; Linux runs the same script with `sh`. No setting selects it, no bubblewrap.
- **Allowlist root, not masks.** On WSL the host's own files are few, and a list of masks (`/mnt`, `/run`, `/home`, ...)
  hides them. On a real Linux host everything not named stays visible (`/etc`, `/opt`, `/srv`, `/var/lib`, a data
  directory outside `/home` with the vault key). On Linux the script therefore builds a new root (`runtime/SandboxNative`):
  a tmpfs into which only `/usr`, `/bin`, `/sbin`, `/lib*` and a short list of `/etc` entries are bound read-only, a
  generated `passwd`, `group`, `hosts` and `nsswitch.conf`, a fresh `/proc`, `/dev` and an empty read-only `/sys`, empty
  tmpfs for `/tmp` and `/var/tmp`, then `pivot_root` and the old root is detached. The worktree, git and dependency
  mounts are the script's own, moved under `/media`. One script text: the native one is the WSL one with the masks block
  and the network lines replaced. The WSL path is unchanged (its denylist is enough there and stays measured by
  `SandboxEscapeTest`).
- **Network on is a network of its own.** On native Linux the host's network namespace is never shared: it would give
  the agent the daemon's loopback port, every other local service and the abstract unix sockets of the desktop.
  Network on runs the sandbox under `pasta` (package `passt`, user-mode networking: no port forwarding, the gateway is
  not mapped to the host, `--no-map-gw`). Without `pasta` the script refuses with the exit code 126 and the line
  `sudo apt install passt`; the Doctor and the machine checklist have a `pasta` row. Network off is an empty network
  namespace, as on WSL. On WSL, network on is the VM's network (NAT), not the Windows loopback.
  **Measured**, 2026-11-09, Debian 13 (trixie) in WSL 2, kernel 6.18.54.1-microsoft-standard-WSL2, passt
  `0.0~git20250503.587980c-2+deb13u1`: from inside, with the network on, a connection to `127.0.0.1` and to the default
  gateway on the ports 7420, 7421, 8180 and one free port, each held open on the host's loopback by the test, fails;
  an abstract unix socket bound on the host cannot be connected to; `example.com` resolves and answers on port 443
  (`SandboxNativeEscapeTest`). **Not measured:** other distributions and kernels, IPv6, WSL's mirrored networking mode,
  and a host service bound to `0.0.0.0` or to another address of the machine: pasta forwards connections to those as the
  host's network would from the LAN, so such a service stays reachable (a firewall is the answer, not the sandbox).
- **Dependency, toolchain and cache mounts** are made at the point of use. A proof copy's `node_modules` (found by
  `DependencyMounts` at walk time) are opened by `sandbox-mounts.py` one component at a time with `O_PATH|O_NOFOLLOW`,
  both in the source and in the copy; each must be a plain folder, the source's real path must stay beneath the
  worktree, and the mount is made from `/proc/self/fd/N`, so a folder swapped for a link after the walk, or a link
  planted in the copy, is refused (this closes a gap that predates the native backend and that mattered more once the
  real home folder was reachable). On native Linux the new root also shows, read-only and at the same path, only the following (`ToolchainMounts`):
  `~/.m2/repository` (Maven stores no credentials there; `settings.xml` is outside it and is not shown); and the install
  root of each of `java`, `node`, `mvn`, `npm`, `gradle`, `python3` that the daemon's absolute `PATH` entries find
  (empty and relative entries are ignored: they mean the daemon's working folder), with its `bin` on the sandbox's `PATH`.
  The binary is resolved to its real path first. Under the home folder a root is shown only when it matches a known
  layout (`ToolchainMounts.LAYOUTS`: `~/.sdkman/candidates/*/*`, `~/.nvm/versions/node/*`, `~/.pyenv/versions/*`,
  `~/.asdf/installs/*/*`, `~/.local/share/fnm/node-versions/*/installation`, `~/.local/share/uv/python/*`,
  `~/.local/share/mise/installs/*/*`). Outside the home folder (and the system folders) only a folder recognised by a
  toolchain's own markers: a JDK has `release` and `bin/java`, a Node `bin/node` and `lib/node_modules`, Maven `bin/mvn` and
  `boot`, Gradle `bin/gradle` and `lib`, Python `bin/python3` and `lib/python3*`. The folder above an arbitrary `bin` (for
  instance `~/.local`, or `/data/u` behind a `PATH` link) is never shown. The markers must be those of the tool (a `node` in a Python-marked prefix is not shown). A tool
  that is neither, or that lies under a folder the sandbox does not have (`/etc`, `/var`, `/run`, `/tmp`, `/media`, `/mnt`),
  is hidden: the Doctor and the machine checklist show one row per such tool, with its name and the way to make it visible. Not the npm cache:
  `make-fetch-happen` stores the `Authorization` header in the cache index when a registry answers `Vary: Authorization`
  (on the measuring machine none of the 116 entries had one, but the case exists), and npm runs offline from the
  project's `node_modules`. `/media`, `/mnt`, `/tmp`, `/etc`, `/var` and `/run` are never roots, and a home that is a link
  (`/home` to `/var/home`) is judged by its real path.
  **Credentials inside a shown root** (`~/.nvm/versions/node/v20/etc/npmrc`, `conf/settings.xml` of a Maven install,
  `pip.conf` of a pyenv version) are masked by `sandbox-mounts.py` after the bind: an empty read-only file over each
  file whose name, compared without regard to case, is `npmrc`, `.npmrc`, `settings.xml`, `settings-security.xml`,
  `toolchains.xml`, `pip.conf`, `pip.ini`, `.pypirc`, `.netrc`, `_netrc`, `credentials`, `.git-credentials`,
  `gradle.properties`, `.pgpass`, `.env`, `auth.json`, `.yarnrc.yml`, `.condarc`, `.env.*`, `.docker/config.json`,
  `*.pem`, `*.key`, `*.p12`, `*.pfx`, `*.ppk`, `*.jks`, `*.keystore`, `id_rsa*`, `id_ed25519*`, `id_ecdsa*`, `id_dsa*`,
  or one of these with a `.bak`, `.old` or `~` suffix (an empty read-only tmpfs over a folder of such a name). The walk
  follows no link and stops at 16 levels or 200000 entries; a root that hits a cap, or holds a folder that cannot be
  read, is not mounted and the reason is printed ("too deep", "too many files", "a folder cannot be read"), and the
  other roots and the command go on. This is defence in depth, not a promise: a credential under another name in a
  toolchain folder is visible. A read-only remount keeps the flags the mount already has (nosuid, nodev, noexec, and its
  atime mode: noatime, relatime or strictatime, with nodiratime), read from the mount, so a home on a noexec or
  strictatime filesystem does not stop the sandbox. WSL: unchanged (the distribution's
  own tools). The read-only system folders and these binds are read-only recursively: a mount below one is made
  read-only or detached, and if neither works the command is not started.
- **Host requirements.** Unprivileged user namespaces. Ubuntu 23.10 and later restrict them with AppArmor
  (`kernel.apparmor_restrict_unprivileged_userns=1`): the Doctor says so and names `sudo sysctl -w
  kernel.apparmor_restrict_unprivileged_userns=0` or an AppArmor profile for `unshare`; Floor42 changes nothing itself.
  `ci.yml` (the build job: `verify` runs `SandboxNativeEscapeTest`, and a step runs the slow `SandboxEscapeTest`) and
  `nightly.yml` (the slow tests, then `SandboxNativeEscapeTest`) set the sysctl and install `passt` on `ubuntu-24.04` so
  that the native sandbox tests run there: configured, not yet observed in a run.
- **Proof.** `SandboxNativeEscapeTest` plants a data directory with a `vault.key` under `/srv` and `/opt` (in a mount
  namespace of its own), opens host loopback ports and an abstract unix socket, swaps a dependency folder for a link,
  plants a mount below `/usr`, and runs probes that must all fail. Before the allowlist root: 12 leaks on the files
  probes (vault key, `/srv`, `/opt`, `/etc/hostname`, `/etc/machine-id`, `/etc/shadow` visibility, `/etc/ssh`,
  `/var/log`, `/var/lib`, `/proc/1/root`, writes in `/srv` and `/opt`) and both socket leaks; after: none. The tests
  that need `pasta` skip with a printed reason when it is absent (and the refusal has a test of its own); they never
  pass because a command was refused.
- Stopping a session ends the tree as on WSL (the namespace's init). Limits are those of the script (`prlimit`, wall
  time, seccomp).

## Measured 2026-11-11 (Claude Code 2.1.322)

A PreToolUse hook on Bash, with a rewritten command in `updatedInput`, was tried four ways:

1. The main agent's Bash call: the hook is called and the rewritten command runs.
2. A sub-agent's Bash call: the hook is called (its input carries `agent_id` and `agent_type`) and the rewritten command runs.
3. A skill's dynamic command (a `!` followed by a command in backticks) with `allowed-tools` in the skill: the hook is never called; the command runs unwrapped on the host.
4. The same skill without `allowed-tools`: the same, never called, unwrapped.

Two more results, with `disableSkillShellExecution` and `--restricted`:

5. The setting `disableSkillShellExecution: true`, given through the session's own `--settings` file, works: a plugin skill's `!` line is replaced by `[shell command execution disabled by policy]` and does not run. It is a restrictive key: it can only tighten.
6. `--restricted` loads neither the project skills (`.claude/skills`) nor the project agents (`.claude/agents`) of the working folder: neither appeared, nothing ran.

So the wrapping holds for every Bash call a session makes itself, sub-agents included, and does not hold for a skill's dynamic command. The root fix is that every session Floor42 launches carries `disableSkillShellExecution: true` in its settings (`ClaudeCodeRuntime.writeSettings`), sandbox on or off. The second line: with the sandbox on, no plugin skill holding such a line is given (`DynamicSkills`, `MissionAssets`: reason `runsCommand`; any case of `skill.md`, any depth, links followed, an unreadable, looping or oversized folder counts as holding one).

## Reopen if
Claude Code stops honouring `updatedInput`, WSL changes how it mounts drives or starts Windows programs, or containers become available and worth their upkeep.

## Amendment 2026-11-11: Maven cache as a tail, private loopback
- **Maven cache.** On Windows the script mounts `%USERPROFILE%\.m2\repository` read-only under `/media/.mroot` (outside the `/mnt` mask; `ToolchainMounts.windowsCache`, no path with a space); on native Linux it is the cache mount that already existed. Both export `MAVEN_ARGS=-Dmaven.repo.local.tail=<that path>` (Maven 3.9+): the cache is read, never written, and the local repository stays empty under `/tmp`. `settings.xml`, `settings-security.xml` and `wrapper` are not shown. Measured, Debian 13 WSL, Maven 3.9.9: with the tail an offline `mvn test-compile` succeeds and writes no jar; without it the same command fails. Debian's own default plugin versions may be absent from a cache filled by another Maven.
- **Loopback.** A network namespace is created with `lo` down. With the network off the script brings `lo` up (`sandbox-mounts.py loopback`): a command may serve itself on `127.0.0.1`; the loopback of the host is another namespace and stays out of reach (`SandboxEscapeTest`).
