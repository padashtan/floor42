# ADR-0029: Picking a project: the ones already used, and a read-only folder browser

Status: Accepted, 2026-10-30 (W2, first increment of P4)

## Context
A real mission needs a git repository. Until now the director typed its path. The wish: pick a project already used, or find one on the machine. A browser page cannot read absolute paths from the operating system's folder dialog, so the daemon must list folders itself.

## Decision
- `GET /api/projects` lists the repositories real missions already used (from the journal), newest first, with what git says of each now: branch, last commit, how many files have uncommitted changes. Projects Office created for an idea committee are marked and left out of the picker.
- `GET /api/projects/folders?path=` lists the sub-folders of a folder: names only, hidden ones left out, at most 400 (the view says when it cut). It marks the folders that hold a `.git`, and reads git for the folder itself when it is a repository. Without a path it starts from the home folder and the drives. It never reads a file and changes nothing.
- Both routes need the director's session token, like the rest of `/api`. Agents hold only their session's hook token, which does not open `/api`.
- The mission form offers the projects already used, a "Browse…" panel, and under the path what git says. It warns that uncommitted changes stay out of the agents' worktrees, which start from the last commit.

## Consequences
- Git facts are read when asked (measured from git), never stored.
- Still to do in P4: the archive wall (W6, commits visible in the room), Code City (W10), the library (W16).
