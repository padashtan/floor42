# Progress: known limits

Part of the progress notes: see [`../PROGRESS.md`](../PROGRESS.md). A branch adds or edits lines here; the State and Next step of PROGRESS.md change only at merge to main.

Technical debt is tracked in `docs/DEBT.md`. Product limits that are planned work:

- One planner (Galen); researchers are hired like the other roles. The parallel limit is one company setting (`PARALLEL_SESSIONS`, ADR-0021) applied to each mission, not chosen per mission.
- The skills catalogue lists and judges what is installed and suggests additions; since ADR-0040 the director installs plugins from it and chooses per mission which plugin skills and agents its sessions receive (never hooks or MCP servers; a skill's bundled scripts are outside the worktree).
- Not built: the CI/CD factory line, the container rack, a team terminal (agents in persona), models of other providers, an undo for a dismissed agent. See `docs/V2_IDEAS.md`.
