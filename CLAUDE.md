# Floor42: notes for coding agents

## Fast loop (use it; do not run the full `verify` after each change)

- **Dev mode**: `bin/floor42-dev` (Windows: `bin\floor42-dev.ps1`, or `make dev`). Quarkus dev mode on
  http://127.0.0.1:7421 with its own data in `~/.floor42-dev`, next to an installed daemon on 7420 (`~/.floor42` and
  `~/.office` are never touched). A change inside a Java method is swapped in without a restart (about 2 s from the
  save to the new answer, measured); a new field, method or class restarts the app (about 10 s). A Vue or TypeScript
  change reaches the open page through Vite's hot reload in milliseconds (Quinoa runs Vite). Stop with `q` or Ctrl+C. Sessions are played by the
  fake Claude Code unless started with `--real-claude` (`-RealClaude`): no paid call by default. The single-use
  sign-in link is printed in the console.
- **Continuous testing**: `bin/floor42-dev --test` (`-Test`), or press `r` in the dev console. After the first full
  pass, a save reruns only the tests that cover the changed classes; `f` reruns the failed ones, `o` toggles output.
- **One test class**: `./mvnw -q -pl floor42-server -am test -Dspotless.check.skip=true -Dtest=WorkspacesTest
  -Dsurefire.failIfNoSpecifiedTests=false` (no web build: Quinoa is off in tests; the package step that builds the UI
  is not reached).
- **What the diff calls for**: `make quick` (`bin/floor42-quick [-n] [--e2e] [ref]`), from the test map
  `tools/test-map.conf` (ADR-0068). A feature context (`app/<ctx>`, `api/<ctx>`, `iam`) runs the tests of its packages
  and the core set (gates, contract and characterisation tests, the domain); a changed test class runs alone; the
  shared core, a pom, a resource or a path with no rule runs every Java test. Then `vitest --changed`, and the
  `npm run e2e:domain` command of the touched domains, printed (`--e2e` runs it; `-n` prints the selection only).
  A new top-level folder or a new context needs its line in the map: `TestMapGateTest` says so. Not a gate: the full
  gate records what the selection would have missed (`~/floor42-escapes.tsv`).
- **Forks**: lower them when another build runs (`-Dfloor42.test.forks=2`); never share one `target` between concurrent builds.
- **Every test, no web build**: `make test-fast` (Vitest, then every Java test without the format check).
- **Web only**: `cd floor42-web && npm test` (Vitest) and `npx vue-tsc -b` (types, `contract.ts`).
- **Full gate**, before a merge to main and a release only: `make gate-double` (Windows then a fresh Linux clone, one heavy job at a time, verdict line `GATE GREEN|RED`, `tools/gate/`; `make gate-windows` before a push of main; `tools/gate/triage.sh <Class>` classifies a failing class) or the plain `make gate` (= `./mvnw -q spotless:apply; ./mvnw verify`,
  then Vitest). Spotless checks before the compilation, so a format slip fails in seconds.
- **Slow tests**: `@Tag("slow")` ones (real WSL sandbox, real timeouts) are not in `verify`; `make test-slow` (`./mvnw
  verify -Pslow` runs only them) and `make release-gate` (verify + slow + Vitest) before a release (docs/releases.md).
- **Java tests run on 6 JVMs** (`floor42.test.forks` in the root pom; `-Dfloor42.test.forks=1` for the sequential run).
  Each JVM boots its own daemons on a free port and in its own data folder (`floor42.test.boot-id`, one per boot,
  `FreshDataDir`). Surefire hands the classes out one at a time, so a test class must not depend on what another one
  left in the shared daemon (hire what it needs; clean up what it started). Each `@QuarkusTest` class then boots its
  own daemon (106 boots in `verify`, the gate prints the count); with one JVM, one boot per test profile. Measured on this machine (16 logical cores,
  Windows), cold: `verify` 11:01 (the new `verify` was not timed end to end); Java tests 9:15 -> 4:30; Vitest 30 s -> 22 s; e2e 7:25 -> 4:50
  (2 workers, `FLOOR42_E2E_WORKERS=1` for one).
- **Java only, no web build in `package`**: add `-Dquarkus.quinoa=false` (the jar then has no UI: not for e2e or a release).

**Start here: `docs/PROGRESS.md`** (current state, next step, index of the area files in `docs/progress/`: decisions, gotchas, limits). A branch adds or edits lines in the area file it touches (`docs/progress/`); State and Next step are updated only at merge to main.

Decisions: `docs/adr/`.

## Layout

- `floor42-domain/`: pure Java 25 business model. JDK only (enforced by `ArchitectureTest` in `floor42-server`, with the other module boundaries).
- `floor42-server/`: Quarkus daemon. Serves the UI through Quinoa from `../floor42-web`. Layer packages `api`, `app`,
  `config`, `iam`, `journal`, `runtime`, `security` (ADR-0003); `app` and `api` are split by business context
  (ADR-0067): `app.core` (the company, settings, scheduler), `workspace`, `mission` (dispatch, tasks, staff, workshop),
  `delivery` (deliverables, proofs, checks, pull requests, reports), `session` (gate, runners, briefs), `committee`
  (committees, retrospectives, meetings, staffing), `asset`, `demo`, `terminal`, `cost`, `boot` (wiring). `api.<context>`
  holds its resources and views; `api` itself the shared HTTP plumbing; `api.event` the event renderers. Allowed
  dependencies between contexts: `APP_CONTEXTS` and `API_CONTEXTS` in `ArchitectureTest`. Tests mirror the packages.
- `floor42-web/`: Vue 3 + TypeScript + Vite. The 3D scene must not depend on Vue.
- Spikes are throwaway experiments on local `spike/*` branches: they are not pushed to the public repository and are
  never merged into product code. ADR-0004 and ADR-0006 cite the first two as history.

## Commands

- Build and test everything: `./mvnw spotless:apply && ./mvnw verify` (needs `JAVA_HOME` on a JDK 25 and `npm ci` in `floor42-web` first; `verify` fails on formatting).
- Dev mode: `bin/floor42-dev` (see "Fast loop"), then http://127.0.0.1:7421.
- Web only: `cd floor42-web && npm run build` and `npm test` (Vitest: scene mapping, navigation, layout, diff view).
- Web code is split by domain to avoid merge conflicts: a new API call goes in its domain's file in `floor42-web/src/api/client/` (`client.ts` only assembles them), a new style in its area's file in `floor42-web/src/styles/` (`index.css` fixes the cascade order).
- A changed view or route: `./mvnw verify -Dfloor42.openapi.write=true`, then align `floor42-web/src/api/types.ts` until `contract.ts` type-checks.
- Browser tests, the gate before a release (not part of `verify`, too slow): `make e2e`, or `./mvnw -q package -DskipTests` then `cd floor42-web && npm run e2e`. Playwright drives the installed Chrome (`FLOOR42_E2E_BROWSER=msedge` for Edge, `FLOOR42_E2E_HEADED=true` to watch; no browser is downloaded; on Linux `FLOOR42_E2E_EXECUTABLE=/usr/bin/chromium`, and on WSL also `FLOOR42_E2E_GPU=true`: headless Chromium there renders WebGL in software and the journeys time out, GPU mode runs headed on WSLg with the Windows GPU) against a daemon of its own: the packaged jar on a free port, a temp data folder, the fake Claude Code and terminal, sign-in through the link the daemon prints (`floor42.sign-in.delivery=stdout`). Needs Java 25 (`FLOOR42_JAVA` or `JAVA_HOME`). Screenshots per journey in `floor42-web/e2e-results` (gitignored); no console error is allowed. A new action in `ui/actions.ts` needs its panel in `e2e/bar.ts`. Every test carries a domain tag (`{ tag: '@tickets' }`, the list is `DOMAINS` in `e2e/fixtures.ts`; an untagged test fails): while working run one domain with `npm run e2e:domain -- @tickets` or the failed ones with `npm run e2e:failed`, and the full suite still runs before a merge to main and before a tag. Mandatory on the developer machine, on Windows and Linux, before every tag (docs/releases.md); in CI the "Browser tests" workflow runs by hand only (GitHub's runners have no GPU).
- Server tests drive a fake Claude Code (`floor42-server/src/test/resources/fake-claude`); its markers are listed in `docs/progress/gotchas-build-and-tests.md`.

## Known pitfalls

- i18n: a sentence built by the server never reaches a translated screen. The server sends a code plus parameters, the UI translates; the journal stays English and language-neutral; the UI never stores a translated default text in a request.
- Paths: resolve the real path (junctions, links) before any check. Refuse protected folders (.git, .claude, .github, tool configs, the data folder, the home folder, disk roots, UNC) anywhere on disk. Check the caller's token scope, not only its role.
- Tests: every fix has a test that fails without it, checked by reverting the fix. Fixtures come from real output (goldens, the server's constants), never from invented strings.
- Concurrency: name the lock that guards each check-then-act, and prove it with a latch test.
- Partial failure: a side effect and its journal entry never drift apart. Choose the order, and test the crash between them.
- Environment: agents run in a sandbox. Never assume a desktop browser, the network or a Windows shell inside it.
- Version bump: after `versions:set`, run the golden tests before pushing.
- Guards that enforce the first, third and last: `noTranslatedRequest.test.ts`, `SentenceCodeGateTest` (shrink-only allow-list), and the version masking in `ResponseBodiesCharacterisationTest`.

## Rules

- Code, docs and commit messages in English. UI texts exist in English and French: add each one to both catalogues in `floor42-web/src/i18n/` (ADR-0021).
- Never present a simulated event, an estimate or an unverified result as real or measured. Every event carries an `Origin`.
- Permissions are enforced by code (hooks, policy), never only by prompt text.
- Tests never call a paid model or a real agent.
- Line-count gates (Java file 400 lines) tolerate 10 percent: between the target and target+10 percent (440) the gate passes and `SizeGateTest` prints a warning; above it fails. Aim for the target.
- Technical debt lives in `docs/DEBT.md`. Above 5 open items the build fails (`DebtGateTest`): pay debt back before adding features. Record new debt there when you knowingly leave some.
