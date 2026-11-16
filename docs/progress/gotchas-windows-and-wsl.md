# Progress: gotchas on Windows, WSL and the agent shell

Part of the progress notes: see [`../PROGRESS.md`](../PROGRESS.md). A branch adds or edits lines here; the State and Next step of PROGRESS.md change only at merge to main.

- Long bash heredocs containing apostrophes break in the agent shell: write scripts to files.
- Docker in WSL: run scripts with `MSYS_NO_PATHCONV=1 wsl -d Debian -- bash /mnt/c/...script.sh` (Git Bash rewrites
  `/mnt` paths, and quoting through `wsl --` breaks). WSL stops the distribution, and Docker with it, seconds after the
  last WSL process ends: keep one alive (`wsl -d Debian -- sleep 900` in the background) while testing a container.
- The online demo's tape must come from a fresh daemon (a new Docker volume, e.g. `docker run -p 127.0.0.1:7421:7420`
  on a new volume): everything it answers is published. Node's WebSocket cannot send a bearer header, so the recorder
  polls `/api/events`.
- Python string edits of Java/TS sources lose backslash escapes: fix those lines with the Edit tool.
- A running `quarkus-run.jar` locks the jar on Windows: stop the daemon before `./mvnw package`.
- Remove a git worktree only after leaving it (the shell's working directory locks it).
- `.gitattributes` forces LF in the working tree (`* text=auto eol=lf`; CRLF for `*.cmd` only), whatever `core.autocrlf` says. Agent worktrees of other repositories may still have CRLF: product code (`Workshop`) handles both.
- A `&&` chain ending with `grep` commits even when the build failed: check `BUILD SUCCESS` explicitly.
- The VS Code Java extension (jdt.ls, on a JRE 21) builds into the same `target/classes` and leaves empty record classes there, which Maven then takes as up to date: test compilation fails with "cannot find symbol" on record accessors. Build from a git worktree outside the editor's folder (with a short path: Windows limits worktree paths), or turn off its automatic build.
