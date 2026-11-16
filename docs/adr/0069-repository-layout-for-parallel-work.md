# ADR-0069: A repository laid out for parallel work by several agents

Date: 2026-11-13 · Status: Accepted

## Context

Floor42 is written by one developer and several coding agents working at once, each in its own git worktree. The
modularisation study of 2026-11-12 counted where their branches collided and what each agent had to read before
its first edit:

- a few registry files changed on almost every branch: 79 merges touched `CHANGELOG.md`, 51 touched
  `floor42-web/src/api/openapi.json`;
- single large files changed for unrelated features: `floor42-web/src/api/client.ts` and `floor42-web/src/style.css`;
- `docs/PROGRESS.md`, which every agent reads first, had grown into a long file that was mostly history;
- the browser tests ran as one block: a red journey meant rerunning all of them;
- the release gate lived in shell history, not in the repository.

ADR-0067 settles the Java side (feature packages). This record covers the rest of the layout, which follows the same rule.

## Decision

**One subject, one place.** A change for one feature touches the files of that feature, not shared files.

1. **Java code by feature** (ADR-0067). `app.<context>` and `api.<context>`, cycles between them forbidden by ArchUnit.
2. **Web client by domain.** `src/api/client.ts` is a 30-line barrel. Each domain has its calls in
   `src/api/client/<domain>.ts`. Styles live in 22 files under `src/styles/`, imported by `src/styles/index.css`.
3. **A short entry point.** `docs/PROGRESS.md` stays at about 50 lines: current state and next step. Gotchas and
   history move to `docs/progress/*.md`. An agent reads the topic files only when its task needs them.
4. **Merges that resolve themselves.** `.gitattributes` declares merge drivers:
   - `CHANGELOG.md merge=union` keeps the lines of both sides;
   - generated files (`openapi.json`, goldens) use `merge=ours` and are then regenerated, never merged by hand.

   `make setup-git` declares the `ours` driver once per clone.
5. **Browser tests by domain.** Every journey carries a domain tag (`{ tag: '@ui' }`, list in `e2e/fixtures.ts`).
   - Default: 3 workers.
   - `npm run e2e:domain -- @x` replays one domain; `npm run e2e:failed` replays the last failures.
   - One journey may check several things with `expect.soft`.
   - Tests must not depend on the calendar. The daily "remote today" draw is off in fixtures and forced in
     `remote.e2e.ts`.
6. **The gate in the repository.** `tools/gate/` contains:
   - `gate-windows.ps1` and `gate-linux.sh`, each printing one verdict line, `GATE GREEN|RED <host> <sha> <minutes>`;
   - `triage.sh`.

   The Linux gate tests a fresh clone at a given commit, so uncommitted work can never make it green.

   Since 2026-11-13 the main gate is the public CI on the release pull request: `tools/gate/remote.sh` (`make
   gate-remote VERSION=X.Y.Z`) pushes a swept snapshot of the private tree to `release/vX.Y.Z`. The two local
   scripts are the fallback.
7. **Tests chosen by the change.** The daily loop runs only the tests the diff reaches (`bin/floor42-quick`, `make quick`).
   The path-to-test map and the grouping of Quarkus boots are decided in ADR-0068.

## Consequences

- Two agents working on two features rarely edit the same file. Registry files merge without a human.
- A new domain adds its own files: a package pair, `client/<domain>.ts`, `styles/<domain>.css`, a test tag.
- A shared file is now a design smell to explain in review, not a habit.
- `make setup-git` is one more step per clone. Without it, generated files conflict as before; that is safe, only slower.
- More, smaller files: a reader must know the convention. This record and `CLAUDE.md` state it.
- Windows: browser tests on 3 workers take about 5 minutes on the developer machine, measured on 104eed6d.

## Reopen if

- Merges on a single file come back: the merge log shows one file in most merges again.
- A domain's files are routinely changed together with another domain's: the split follows the wrong line.
- Another layer gains a large shared file, such as `PROGRESS.md` going back above about 100 lines.
- Builds or test runs outgrow one machine. Then Maven submodules, set aside in the 2026-11-12 study, are weighed again.
