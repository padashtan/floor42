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
- **What the diff touches**: `make quick` (`bin/floor42-quick [ref]`): the changed test classes and `<Class>Test*` for
  each changed main class (Maven `-Dtest`), then `vitest --changed`. A changed pom or resource runs every Java test.
  Not a gate.
- **Forks**: lower them when another build runs (`-Dfloor42.test.forks=2`); never share one `target` between concurrent builds.
- **Every test, no web build**: `make test-fast` (Vitest, then every Java test without the format check).
- **Web only**: `cd floor42-web && npm test` (Vitest) and `npx vue-tsc -b` (types, `contract.ts`).
- **Full gate**, before a merge to main and a release only: `make gate` (= `./mvnw -q spotless:apply; ./mvnw verify`,
  then Vitest). Spotless checks before the compilation, so a format slip fails in seconds.
- **Slow tests**: `@Tag("slow")` ones (real WSL sandbox, real timeouts) are not in `verify`; `make test-slow` (`./mvnw
  verify -Pslow` runs only them) and `make release-gate` (verify + slow + Vitest) before a release (docs/releases.md).
- **Java tests run on 6 JVMs** (`floor42.test.forks` in the root pom; `-Dfloor42.test.forks=1` for the sequential run).
  Each JVM boots its own daemons on a free port and in its own data folder (`floor42.test.boot-id`, one per boot,
  `FreshDataDir`). Surefire hands the classes out one at a time, so a test class must not depend on what another one
  left in the shared daemon (hire what it needs; clean up what it started). Measured on this machine (16 logical cores,
  Windows), cold: `verify` 11:01 (the new `verify` was not timed end to end); Java tests 9:15 -> 4:30; Vitest 30 s -> 22 s; e2e 7:25 -> 4:50
  (2 workers, `FLOOR42_E2E_WORKERS=1` for one).
- **Java only, no web build in `package`**: add `-Dquarkus.quinoa=false` (the jar then has no UI: not for e2e or a release).

**Start here: `docs/PROGRESS.md`** (current state, decisions taken along the way, gotchas, next step). Keep it updated at the end of every increment.

Decisions: `docs/adr/`.

## Layout

- `floor42-domain/`: pure Java 25 business model. JDK only (enforced by `ArchitectureTest` in `floor42-server`, with the other module boundaries).
- `floor42-server/`: Quarkus daemon. Serves the UI through Quinoa from `../floor42-web`.
- `floor42-web/`: Vue 3 + TypeScript + Vite. The 3D scene must not depend on Vue.
- Spikes are throwaway experiments on local `spike/*` branches: they are not pushed to the public repository and are
  never merged into product code. ADR-0004 and ADR-0006 cite the first two as history.

## Commands

- Build and test everything: `./mvnw spotless:apply && ./mvnw verify` (needs `JAVA_HOME` on a JDK 25 and `npm ci` in `floor42-web` first; `verify` fails on formatting).
- Dev mode: `bin/floor42-dev` (see "Fast loop"), then http://127.0.0.1:7421.
- Web only: `cd floor42-web && npm run build` and `npm test` (Vitest: scene mapping, navigation, layout, diff view).
- A changed view or route: `./mvnw verify -Dfloor42.openapi.write=true`, then align `floor42-web/src/api/types.ts` until `contract.ts` type-checks.
- Browser tests, the gate before a release (not part of `verify`, too slow): `make e2e`, or `./mvnw -q package -DskipTests` then `cd floor42-web && npm run e2e`. Playwright drives the installed Chrome (`FLOOR42_E2E_BROWSER=msedge` for Edge, `FLOOR42_E2E_HEADED=true` to watch; no browser is downloaded; on Linux `FLOOR42_E2E_EXECUTABLE=/usr/bin/chromium`, and on WSL also `FLOOR42_E2E_GPU=true`: headless Chromium there renders WebGL in software and the journeys time out, GPU mode runs headed on WSLg with the Windows GPU) against a daemon of its own: the packaged jar on a free port, a temp data folder, the fake Claude Code and terminal, sign-in through the link the daemon prints (`floor42.sign-in.delivery=stdout`). Needs Java 25 (`FLOOR42_JAVA` or `JAVA_HOME`). Screenshots per journey in `floor42-web/e2e-results` (gitignored); no console error is allowed. A new action in `ui/actions.ts` needs its panel in `e2e/bar.ts`. Mandatory on the developer machine, on Windows and Linux, before every tag (docs/releases.md); in CI the "Browser tests" workflow runs by hand only (GitHub's runners have no GPU).
- Server tests drive a fake Claude Code (`floor42-server/src/test/resources/fake-claude`); its markers are listed in the gotchas of `docs/PROGRESS.md`.

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
