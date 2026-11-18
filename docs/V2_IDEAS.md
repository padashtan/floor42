# Ideas after v1

A parking lot, not a roadmap: nothing here is committed to a release. The scope of v1.0.0 is frozen. Before promoting
an idea, compare it with what real use showed, the technical debt (`DEBT.md`), the security constraints and the
simplest solution. Planned features are not debt.

## Post-v1: known loose ends

- **Flaky tests.** `TerminalApiTest` (on Linux, under load), the `RetroDelegation` tests and the 409 races, and
  `ProofGitTest` on Windows; seen once each under load on 2026-11-10, green alone 3 times: `PlanningApiTest`
  (two independent tickets went to the same developer: maybe a real assignment race), `ResumeApiTest` on Linux (the
  test-data cleanup walked a folder while SQLite removed `floor42.db-journal`), the `missionDeliverables` journey on
  Windows (the mission took longer than its 15 s wait). Each has to be traced to its cause, not retried.
- **TypeScript 7.** typescript-eslint 8.72 accepts TypeScript below 6.1 only; the Dependabot update waits for it.
- **Bug hunt of 2026-11-11, minor findings left open.** A script token can mint tokens (by design, ADR-0044: to
  revisit); the sandbox can read the worktree's `.git/config`; a command approval is matched on its summary, not on a
  hash of the full command. (Done since: the sandbox hides `config` and `config.worktree`; the journal runs in WAL with `synchronous=FULL`;
  `contractTypes.test.ts` compares field types, v1.4.)
- **Static analysis.** Sonar's parser skips `TaskSelection.java` (it uses the unnamed pattern `_`): its findings are
  not seen until the file is rewritten or the parser is updated.

### Open defects of the 2026-11-06 audit (status not verified)

- The command-prefix ALLOW is matched on the abbreviated summary (newlines collapsed, cut at 300), not the raw command.

### Open friction from the 2026-11 pilot

- Accept part of a file (hunks) in the workshop; whole files are done (accept the ticked).

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
- **Adversarial security lab (built, v1.4: `docs/security-lab.md`).** Still open: the IAM-mode run (Schemathesis
  against Keycloak, OpenFGA, the vault and the sandbox) and detection of injected instructions in tool results.
- **Repository profiles and preflight.** Built in v1.1, derived only (ADR-0070): build tool, proposed checks and a preflight before the click. Still open: an editable saved profile, reference branch, sensitive paths. Per project: build and test commands, reference branch, prerequisites,
  sensitive paths; a preflight that shows blockers and warnings before a real mission starts.
- **Governed delivery (built, v1.4: ADR-0084).** Still open: release on explicit authorization, and a run against a
  real throwaway repository.
- **Exportable evidence package (built, v1.1: ADR-0071).** A mission dossier (objective, decisions, commits, commands and exit codes, review
  verdict, limits) in Markdown or JSON, redacted by default, each metric labelled measured, computed or estimated.
- **Agent and workflow benchmark.** Representative tasks with known answers, to test whether a workflow or a model
  choice improves the outcome; real-model runs opt-in and capped.
- **Smaller.** Explicit performance budgets (startup, replay, event to screen); a history of configuration changes; a
  compact keyboard-driven operations view beside the 3D office; an agent runtime contract with a second runtime; the
  broker and the vault out of the daemon; Keycloak manages and OpenFGA decides (ADR-0065).
- The real Jira, through the Atlassian MCP server declared with the MCP feature (no code of Floor42's own): creating and updating issues, links, epics and a two-way sync. v1.2 only exports a CSV.
- **CIBA and a phone passkey (ADR-0081).** Decoupled approval pushed to the phone (Keycloak's backchannel grant) needs a channel the phone reaches (LAN bind, tunnel or push relay): reopen with a remote access story. A passkey on the phone (WebAuthn hybrid transport) as an alternative to the TOTP code, once measured on Chrome and Edge with rp id `localhost`.
