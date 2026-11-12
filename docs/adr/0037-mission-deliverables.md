# ADR-0037: A mission's deliverables, viewed and downloaded without the daemon running them

Date: 2026-10-31 · Status: Accepted

## Context
At the end of a mission the director asked "where are the deliverables?". They were scattered: each ticket's deliverable text on the board, the agents' files on their branches (the archive wall shows commits, not files; the workshop only while the mission is open), and outputs an agent generated but never committed (the PDFs of a document mission in `~/.office/workspaces/M-1-bobi/docs/out/`, ignored by git) shown nowhere. The director wants to see them, download them, "bref tout", for any mission, open or closed, Office-era ones included.

The content is written by agents: an HTML page, an SVG or a script served by the daemon's origin would run with the director's session (the cookie, every `/api` route). Viewing must never mean executing.

## Options
| Option | What it costs | Verdict |
|---|---|---|
| A. Extend the workshop to closed missions | The workshop writes (edit, accept, reject) and checks access per mission state; mixing a read-only archive into it widens a write surface | Rejected |
| B. A read-only Deliverables panel and four routes, files served only when the listing names them, under a sandboxing CSP | One service reading the journal and git; a whitelist per request (git status, or the branch's diff) | **Taken** |
| C. Copy the deliverables into a folder at mission end | A second copy to keep consistent; outputs created later are missed; disk use | Rejected |

## Decision
- `GET /api/missions/{id}/deliverables` (`app/Deliverables`): tickets with their deliverable text, last verdict and every review's findings; per agent branch (journaled workspaces, then worktrees found on disk under the mission's name, Office's former data folder included) the files changed between the commit the worktree was made from (`Workspace.startCommit`, new in the domain: `baseCommit` moves when the project is merged in) and the branch tip (`git diff --name-status -z -M`, sizes from `ls-tree -l`, last commit per file), its commits with whether the project's current branch holds them (`merged`), and the worktree's uncommitted files (`git status -z -uall`; with `ignored=true` also `--ignored=traditional`, otherwise only a count). 500 files per list, 30 commits.
- `GET .../deliverables/file?agent&path&source`: a file is served only if the listing would name it. `COMMITTED`: the path must be in the branch's diff (not deleted), read with `git cat-file blob <tip>:<path>` at a tip Floor42 resolved itself (no revision from the caller), so it works after the worktree is gone. `WORKTREE`: `Confinement.check` (no `../`, no absolute path, no link leading out), no `.git` segment, git must report it as not committed, not a link or junction (opened with `NOFOLLOW_LINKS`), real path inside the worktree.
- Serving: every answer carries `X-Content-Type-Options: nosniff`, `Cache-Control: no-store` and `Content-Security-Policy: sandbox; default-src 'none'` (images: plus `img-src 'self'`). Text (by name, no NUL, at most 1 MB) goes out as `text/plain` whatever it is, HTML, SVG and JavaScript included; the UI shows it in `TerminalBlock`. Images only when their bytes are PNG, JPEG, GIF or WebP; a PDF only when it starts with `%PDF-` (25 MB cap each). A PDF gets `default-src 'none'; object-src 'self'; frame-ancestors 'self'` without `sandbox`: Chromium's viewer refuses to render a sandboxed PDF; the bytes are rendered by the browser's PDF viewer, never as an HTML page of the daemon. The UI embeds it in an `<object>`. Anything else is download only: `application/octet-stream`, `Content-Disposition: attachment` with a name of `[A-Za-z0-9._-]` plus `filename*` percent-encoded (no quote or line break reaches the header).
- `GET .../deliverables/zip`: streamed; `MANIFEST.txt` first (each entry's origin: "committed at <commit> on <branch>" or "not committed: exists only in the worktree <path>"; what was left out and why), `tickets/T-n.md`, `branches/<agent>/committed/...` (through git), `branches/<agent>/not-committed/...`. At most 512 MB of files by the listed sizes; links and names that could climb out on extraction are left out and listed.
- `POST .../deliverables/open-folder {agent}`: starts `explorer.exe` / `open` / `xdg-open` with the folder as its only argument (no shell), only for a worktree of that mission whose real path is under `<data>/workspaces` or Office's former one. A seam (`Deliverables.useForTests`) keeps tests from opening windows. In Docker there is no explorer: 409 `OPEN_FOLDER_UNAVAILABLE`.
- Director only, like every `/api` route: an agent session's credential gets 403 (tested on the four routes).
- Not journaled: viewing and downloading are readings and change no fact; the ZIP download is logged in the daemon's log only. A journal entry would record that the director looked, which no rule uses.
- The panel opens by itself once per mission when the board's mission ends while the page watches (remembered in the browser, never in the journal).

## Consequences
- What an agent left uncommitted and ignored (build outputs) is visible and downloadable, marked as existing only in the worktree; once the worktree is removed it is lost, and the panel says so.
- "Merged" is read from ancestry: a squash merge or a cherry-pick into the project shows as not merged.
- Files the director gave the mission (merged into the worktree) show among the branch's changes, attributed to that merge's side.
- Not checked by hand yet: the PDF in Chrome and Firefox's viewers under this CSP, and the Windows explorer launch.
- Reopen if a viewer must render rich content (HTML preview): that would need a separate origin, not a looser CSP.
