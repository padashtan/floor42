# Floor42: progress notes

The resume point for coding agents and contributors. Read this first, then `docs/adr/` (index in `docs/adr/README.md`),
`docs/DEBT.md` and `CLAUDE.md`. Keep it short: what is true today, how to work, what to avoid. The history of each
change is in git and `CHANGELOG.md`; the reasons are in the decision records.

**Rule: a branch adds or edits lines in the area file it touches (`docs/progress/`); State and Next step below are updated only at merge to main.**

## State

- Version in the poms: 1.0.0. This content is the
  first public v1.0.0 (director's decision 2026-11-11; the scope is frozen: new ideas go to `docs/V2_IDEAS.md`). The
  public repository receives it as one single commit, without the private history, on the director's go.
- What it is: a daemon (Quarkus, Java 25) that runs Claude Code agents under governance enforced by code, an event
  sourced domain (`floor42-domain`), an append-only SQLite journal, and a Vue 3 + three.js office as the interface
  (`floor42-web`). Agents are Claude Code sessions, never direct model calls.
- Agent identity with Keycloak (IAM mode, opt-in, ADR-0064; `iam/README.md`), the token broker, the mandatory WSL
  sandbox for REAL missions with a journaled unsafe mode (ADR-0063), session risk detection (ADR-0066, detection and
  alerts only), 1.2.0: a script token starts no agent work in IAM mode (`LocalAccessGuard.scriptMayInIam`, default deny, 403 `IAM_SCRIPT_TOKEN_REFUSED`, ADR-0064 note), the OIDC conformance run, the assurance chain (`docs/quality.md`, `docs/security-scanning.md`).
- 1.3.0 N3, assured approvals (ADR-0081): `AssurancePolicy` (domain table), `AssuredActs` (step-up and four eyes, `iam`), event `ApprovalSigned`, setting `FOUR_EYES`, realm LoA flow with Configure OTP disabled (`iam/enrol-window.sh`); the decision point is the port `Countersigners`, wired to N1's `Authorizer` at INT; unmeasured: a user without a factor against the real realm, a phone passkey.
- 1.4.0 (proven under attack): adversarial lab `floor42-server/src/test/java/dev/floor42/server/lab/` and `docs/security-lab.md` (run `-Dtest='*LabTest'`, about 13 s, in `verify`); journal in WAL with `synchronous=FULL`, left at a clean stop (`SqliteJournal.leaveWal`), appends commit in autocommit so a durable commit is never reported failed; governed delivery (ADR-0084, `app/delivery/PullRequests`, events `DeliveryEvidenceRecorded` and `PullRequestMerged`, merge from the director's cookie only); `MissionGovernor` masks tool inputs and results before the journal.
- The 90 code scanning alerts of the first public run (CodeQL, Scorecard) are triaged one by one in
  `docs/security-scanning.md` ("Code scanning triage (v1.0.0)"): fixed with a test, or justified by the check that
  guards the value. A value from a request is logged through `security/LogSafe`.
- Debt: `docs/DEBT.md` (at most 5 open items, `DebtGateTest`). Ideas and known flaky tests: `docs/V2_IDEAS.md`.
- Server layout since 2026-11-12 (ADR-0067): `app` and `api` are split by context (`app.core`, `app.mission`,
  `app.session`, `app.delivery`... and `api.<context>`). Notes below and in claude-mem that cite `server/app/X` or
  `server/api/X` predate it: find the class by name, its context is in the ADR's table. After pulling, delete
  `floor42-server/target/classes` and `target/test-classes` once (stale classes of the old packages).
- Decision records written before 1.5.0 say "Office" (the former name); some cite `spike/*` branches, which are
  throwaway experiments that are not in this repository.

## How to resume

- Fast loop (CLAUDE.md): `bin/floor42-dev` for dev mode, `./mvnw -q -pl floor42-server -am test -Dspotless.check.skip=true -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false` for one class, `make test-fast` for every test without the web build. The full gate, `./mvnw -q spotless:apply; ./mvnw verify` (`make verify`), only before a merge to main and a release.
- Build and run: `cd floor42-web && npm ci && cd .. && ./mvnw verify`, with `JAVA_HOME` on a JDK 25 (or `FLOOR42_JAVA`). Stop a running daemon before `./mvnw package` (Windows locks the jar). `bin/floor42` (`bin\floor42.ps1` on Windows) copies the build to `~/.floor42/app`, keeps data in `~/.floor42`, serves the UI on http://127.0.0.1:7420 and asks the daemon to open a single-use sign-in link in the browser (ADR-0034). Scripts that need a bearer token use a script token from the desk (System > Sessions and tokens, ADR-0044; `FLOOR42_TOKEN` for `floor42 "objective"`).
- Tests: domain (JUnit, fast), server (`@QuarkusTest`, SQLite per run, fake Claude Code in `floor42-server/src/test/resources/fake-claude`), web (`npm test`, Vitest), browser journeys (`make e2e`, before a release). No test may call a paid model.
- Changing a view or a route: refresh the contract snapshot with `./mvnw verify -Dfloor42.openapi.write=true` (or the single `OpenApiContractTest`), then fix `floor42-web/src/api/types.ts` until `contract.ts` type-checks.
- The director writes improvement notes in Floor42 (desk, checklist): read them with `GET /api/notes` and apply the open ones.

## Areas

Each file holds one area. Add new notes to the file of the area you touch.

- [`progress/decisions-governance.md`](progress/decisions-governance.md): behaviours worth knowing, agents and governance, session risk v1 (ADR-0066).
- [`progress/decisions-identity.md`](progress/decisions-identity.md): agent identity (1.17.0, ADR-0064).
- [`progress/decisions-api.md`](progress/decisions-api.md): API and cockpit.
- [`progress/decisions-office.md`](progress/decisions-office.md): the office on screen, panel layout and the way out of every action.
- [`progress/gotchas-build-and-tests.md`](progress/gotchas-build-and-tests.md): build, tests, dev mode and every fake (Claude Code, gh, terminal) with its markers.
- [`progress/gotchas-windows-and-wsl.md`](progress/gotchas-windows-and-wsl.md): Windows, WSL, Docker in WSL, agent shell, git worktrees, line endings.
- [`progress/gotchas-code-and-ui.md`](progress/gotchas-code-and-ui.md): code structure rules by refactor, the top bar, the web client.
- [`progress/gotchas-iam.md`](progress/gotchas-iam.md): Keycloak and IAM mode.
- [`progress/known-limits.md`](progress/known-limits.md): product limits that are planned work.

## Next step

v1.0.0 is the first public release (2026-11-12). Next:

1. Check `install.sh` from the published release on WSL (Debian).
2. Run a real IAM mission against Keycloak and the real scenarios of `iam/README.md` (paid: agree a spend ceiling first).
3. Then `docs/V2_IDEAS.md`.
