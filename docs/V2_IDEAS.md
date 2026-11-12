# Ideas after v1

A parking lot, not a roadmap: nothing here is committed to a release. The scope of v1.0.0 is frozen. Before promoting
an idea, compare it with what real use showed, the technical debt (`DEBT.md`), the security constraints and the
simplest solution. Planned features are not debt.

## Post-v1: known loose ends

- **Secret masking.** `SecretMasker` should also mask a `?sig=` query parameter (signed URLs) and a `cookie:` header
  line.
- **Debt D29 (open, `DEBT.md`).** Two spellings of a secret query parameter still leak: a percent-encoded name
  (`%74oken=abc`) and a value with a blank inside (`?token=abc1 2xyz`, only `abc1` is masked). Both need a URL decoder
  or a grammar of the line, not a pattern.
- **Flaky tests.** `TerminalApiTest` (on Linux, under load), the `RetroDelegation` tests and the 409 races, and
  `ProofGitTest` on Windows; seen once each under load on 2026-11-10, green alone 3 times: `PlanningApiTest`
  (two independent tickets went to the same developer: maybe a real assignment race), `ResumeApiTest` on Linux (the
  test-data cleanup walked a folder while SQLite removed `floor42.db-journal`), the `missionDeliverables` journey on
  Windows (the mission took longer than its 15 s wait). Each has to be traced to its cause, not retried.
- **TypeScript 7.** typescript-eslint 8.72 accepts TypeScript below 6.1 only; the Dependabot update waits for it.
- **Bug hunt of 2026-11-11, minor findings left open.** A script token can mint tokens (by design, ADR-0044: to
  revisit); the sandbox can read the worktree's `.git/config`; a command approval is matched on its summary, not on a
  hash of the full command; the journal stays in rollback mode (WAL and an explicit fsync on the continuity record
  would harden a power cut). `contract.ts` compares the names of the fields of a view, not their types.
- **Static analysis.** Sonar's parser skips `TaskSelection.java` (it uses the unnamed pattern `_`): its findings are
  not seen until the file is rewritten or the parser is updated.

### Open defects of the 2026-11-06 audit (status not verified)

- A director withdrawal of an ACTION_APPROVAL question can still orphan its action.
- The command-prefix ALLOW is matched on the abbreviated summary (newlines collapsed, cut at 300), not the raw command.
- Lowering `max-token-hours` leaves existing Keycloak clients at their old lifespan, so IAM sessions may fail to start.
- The single-use `toolUseId` set (`McpResource`) is never cleared, only bounded at 10,000.

### Open friction from the 2026-11 pilot (status not verified)

- Suggest a mission title from the objective's first line.
- Offer the checks command in the mission creation form.
- Abandon a mission from the board, not only from the desk; a cancelled mission's board should say so.
- No check should start after `MissionCancelled`.
- Show the director's own session time (browser session limits; Keycloak sign-in and delegation expiry in IAM mode).
- Accept part of a delivery (partial accept) in the workshop.
- Reviewers are read-only (D15, ADR-0046), so a review never reruns a test: only Floor42's checks and the delivery proof do.

### Planned features never built

- CI/CD factory line (GitHub Actions, then GitLab), container rack, Git archive wall extensions, a team terminal
  (agents in persona), an undo for a dismissed agent.
- Models of other providers for some tasks, and an economical mode that spends as little as possible per mission.

## Ideas

Each one is a proposal to weigh against real campaigns, in rough order of value.

- **Crash recovery and mission reconciliation.** After the daemon or an agent process stops, reconcile the journal with
  the processes, sessions and worktrees actually there; offer resume, retry, leave suspended or abandon; never repeat a
  side effect whose outcome is uncertain.
- **Adversarial security lab.** A reproducible suite that tries to bypass the controls: injected instructions in
  files and test output, paths outside the worktree, symlinks, indirect shell commands, late or duplicated signals,
  credentials in logs. Deterministic, on the fake runtime.
- **Repository profiles and preflight.** Per project: build and test commands, reference branch, prerequisites,
  sensitive paths; a preflight that shows blockers and warnings before a real mission starts.
- **Governed delivery.** Accepted patch to pull request to CI evidence to release, with evidence tied to the exact
  commit and marked stale when the branch moves; merge and release only on explicit authorization.
- **Exportable evidence package.** A mission dossier (objective, decisions, commits, commands and exit codes, review
  verdict, limits) in Markdown or JSON, redacted by default, each metric labelled measured, computed or estimated.
- **Agent and workflow benchmark.** Representative tasks with known answers, to test whether a workflow or a model
  choice improves the outcome; real-model runs opt-in and capped.
- **Smaller.** Explicit performance budgets (startup, replay, event to screen); a history of configuration changes; a
  compact keyboard-driven operations view beside the 3D office; an agent runtime contract with a second runtime; the
  broker and the vault out of the daemon; Keycloak manages and OpenFGA decides (ADR-0065).
