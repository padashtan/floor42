# From Office to Floor42

The product was called Office during its private development and became Floor42 before v1.0.0, its first public release. On its first start, Floor42 takes over an
existing installation:

- **Data**: when `~/.floor42/floor42.db` does not exist and `~/.office/office.db` does, the journal is copied (a
  consistent SQLite snapshot, checked event for event count), never moved. `~/.office` is left as it was, as the
  backup; the copy is recorded in `~/.floor42/migrated-from-office.properties` and in the daemon's log. Stop the old
  Office daemon first: the copy is taken when Floor42 starts.
- **Worktrees** of earlier missions stay in `~/.office/workspaces`, where git registered them by absolute path, and are
  still found there; new ones are made in `~/.floor42/workspaces`. Agents' existing branches `office/...` stay as they
  are (each mission journaled its own); new branches are named `floor42/...`. Agents' shell commands may reach neither
  folder.
- **Configuration**: `floor42.*` properties and `FLOOR42_*` environment variables; the former `office.*` and
  `OFFICE_*` names are still read, and the daemon logs a deprecation warning for each one it used.
- **Launchers**: the old `office` launchers no longer exist; use `bin/floor42`, `bin/floor42.ps1` and `bin/floor42-docker`.
- **Docker**: the container keeps its `office-home` volume and its home `/home/office` (worktree paths inside it
  stay valid); the data folder becomes `/home/office/.floor42`, copied from `/home/office/.office` the same way.
- **Browser**: what the page kept in your browser (language, panel places, filters) moves to the new names once.
- **Repository**: Floor42 is published as `github.com/padashtan/floor42`.

Architecture decision records written before the rename say "Office": they are kept as written.
