# ADR-0040: Installing plugins on the director's click, and choosing what a mission's sessions receive

Date: 2026-10-31 · Status: Accepted

## Context
The skills catalogue (W3, 1.5.0) lists what is installed and suggests additions, but the director copies the install commands by hand, and measured on Claude Code 2.1.316 none of the director's skills, agents or plugins reaches Floor42's sessions: they run with `--restricted` (no user, project or local settings) and `--strict-mcp-config`. The director asked to install from the marketplace from Floor42, choosing at each install the target: (A) "for Floor42 only", or (B) "into my Claude Code"; and to choose per mission which installed skills and agents its sessions receive.

What Claude Code offers (read from `claude --help`, `claude plugin <cmd> --help` on 2.1.316 and code.claude.com, 2026-10-31):
- `claude plugin marketplace add <source>` (owner/repo, URL or path), `claude plugin install <name>@<marketplace>`, `claude plugin uninstall <name>@<marketplace>`, each with `--json`; `-y` is needed to accept a marketplace-declared command when stdin is not a terminal.
- `CLAUDE_CONFIG_DIR` moves the whole configuration folder (settings, plugins). Checked: `CLAUDE_CONFIG_DIR=<empty folder> claude plugin marketplace list --json` answers `[]` where the director's own answers four marketplaces.
- `--plugin-dir <path>` loads a plugin folder for one session (repeatable; `@inline`, it wins over a same-named installed one). Checked: `claude --restricted --strict-mcp-config --plugin-dir <folder> plugin list --json` lists it `enabled: true, scope: session` while every installed plugin of the director shows `enabled: false`.
- `--add-dir` also loads `.claude/skills` and `.claude/agents` from the added folder, but grants file access to it.

## Options
| Option | What it costs | Verdict |
|---|---|---|
| Install target A with `CLAUDE_CODE_PLUGIN_CACHE_DIR` | Moves the plugins root only: `marketplace add` and `install` still write `extraKnownMarketplaces` and `enabledPlugins` into the director's `~/.claude/settings.json` | Rejected: leaks into the director's Claude Code |
| Install target A with a dedicated `CLAUDE_CONFIG_DIR` (`<data>/claude-assets/config`) | One folder of Floor42's; Claude Code does the fetching, versions and records itself | **Taken** |
| Floor42 clones and copies plugins itself | Reimplements Claude Code's sources (git, git-subdir, url, npm), versions and checks | Rejected |
| Give a mission whole plugins (`--plugin-dir <installed folder>`) | Loads the plugin's hooks, MCP and LSP servers: code running outside Floor42's governed tools, on every event | Rejected |
| Give a mission only chosen skills and agents, laid out by Floor42 in the session's folder under a manifest it writes, with `--plugin-dir` | A copy per session (small, kept as evidence); a skill's bundled scripts sit outside the worktree | **Taken** |
| `--add-dir` a folder of chosen skills | The agent's file tools then reach that folder | Rejected |
| Drop `--restricted` and enable plugins in a settings file | Brings back the director's settings, hooks and permissions | Rejected |

## Decision
- **Director's click only.** `POST /api/plugins/preview` runs nothing: source, publisher, version, licence, contents (skills, agents, commands, hooks, MCP, LSP; read from the plugin's folder when a clone holds it, else only what the marketplace entry declares, marked unknown), warnings (strong for hooks, MCP and LSP servers and unknown contents; an acknowledgement box must be ticked), the exact commands and a digest. `POST /api/plugins/run` needs that digest (`PREVIEW_STALE` otherwise), runs one command line at a time with no terminal (a prompt reads end of input and refuses; Floor42 never passes `-y`, so a command-sourced plugin is refused: `RUNS_A_COMMAND`). Never automatic. Agents cannot: their credential gets 403 on `/api` (ADR-0034), the domain refuses any actor but the director (`FORBIDDEN`), and `ShellGuard` refuses `claude plugin …`, `claude mcp …`, `CLAUDE_CONFIG_DIR` and `CLAUDE_CODE_PLUGIN*` in their shell.
- **Two targets.** `FLOOR42`: commands run with `CLAUDE_CONFIG_DIR=<data>/claude-assets/config`. `CLAUDE`: the director's default folder (`floor42.catalogue.claude-home` when set, passed as `CLAUDE_CONFIG_DIR` so what Floor42 reads is what it changes). Every `CLAUDE*` variable of the daemon's environment is removed first.
- **The state read again decides.** After the commands (or a failure, or a timeout), Floor42 reads `installed_plugins.json` of the target: `INSTALLED`, `NOT_INSTALLED`, `REMOVED`, `STILL_INSTALLED`; `commandsOk` and each command's output are shown beside it. Journaled as `PluginCommandRan(target, action, plugin, marketplace, commands, exitCode, present, version)`, Origin REAL, by the director.
- **Listings.** Marketplaces are those known to each target (`known_marketplaces.json`) plus the official ones in `catalogue.json`. A listing is the newest of Claude Code's clones and the copy Floor42 read from GitHub (`raw.githubusercontent.com/<owner>/<repo>/HEAD/.claude-plugin/marketplace.json`, 4 MB at most), and that network call happens only on "Refresh the marketplace". Installing still goes through Claude Code, which refreshes its own copy.
- **Per mission.** `PUT /api/missions/{id}/assets` journals `MissionAssetsSelected(mission, items)` (REAL, director, real missions not over), items `<floor42|claude>:<plugin>@<marketplace>/<skill|agent>/<name>`. When a live session starts, `ClaudeCodeRuntime` asks the `SessionAssets` port (`MissionAssets`): for each chosen plugin still installed, `<session>/plugins/<plugin>/` gets a `plugin.json` Floor42 writes (name and description only) and copies of the chosen skill folders and agent files (regular files, no links, 2000 files and 20 MB at most), passed with one `--plugin-dir` each, beside `--restricted` and `--strict-mcp-config`. A skill or agent whose frontmatter declares `hooks` or `mcpServers` is never given (`declaresHooks`, `declaresMcp`); an uninstalled one is skipped with a note on the session.
- **Seams.** `ClaudeCli` (`floor42.plugins.claude-command`, `claude`); `Plugins.Fetcher` (`floor42.plugins.listing-base`). Tests: the command is missing unless a test swaps in `src/test/resources/fake-claude-cli/FakeClaudeCli.java` (local marketplace folders, the same files Claude Code writes), the listing base is a closed port, and the fake Claude Code lists `--plugin-dir` plugins in its init line as the real one does.

## Consequences
- Whether a chosen skill reached a session is still read from its init line by the catalogue (`plugin:name`), never assumed. Not yet seen on a real session: the `plugin list` check above is the evidence so far.
- A chosen skill's bundled scripts are copied into the session's folder, outside the worktree: the agent's file tools and `ShellGuard` refuse them, so skills work as instructions, not as script runners.
- Not checked against the real CLI: whether `claude plugin install` into a fresh `CLAUDE_CONFIG_DIR` needs a sign-in (Floor42 shows the output either way).
- Updates (`claude plugin update`) and user-level skills (`~/.claude/skills`) are not selectable yet.

## Reopen when
Claude Code changes `--restricted` so that `--plugin-dir` no longer loads, offers a per-session allow-list of skills, or a skill needs its scripts to run.
