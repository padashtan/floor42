# Changelog

All notable changes to Floor42, one line each. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
The reasons behind a change are in the linked decision records ([`docs/adr/`](docs/adr/README.md)); the working
details in [`docs/PROGRESS.md`](docs/PROGRESS.md). Floor42 was developed in private, first under the name Office,
before v1.0.0: that work is not published as versions, and the decision records keep its history and dates.

## [Unreleased]

## [1.4.0] - 2026-11-18

### Added
- Governed delivery ([ADR-0084](docs/adr/0084-governed-delivery.md)): Floor42 reads the checks of the pull request's exact head commit (check runs per suite and legacy commit statuses), shows them with the commit, marks them stale when the branch moves, and merges only on the director's own click (`gh pr merge --match-head-commit`) when every check of the current head is green and the remote still names the pull request's repository (409 `REMOTE_CHANGED` otherwise); a token gets 403 `HUMAN_ONLY`, IAM on or off, and in IAM mode the merge is an assured act at the strong level (its end-to-end test with the phone is planned for 1.5). New events `DeliveryEvidenceRecorded` and `PullRequestMerged`; a 1.3.x binary sets them aside (`NEWER_VERSION`). Known limit: a check that GitHub has not created yet when the first evidence is read is unseen (ADR-0084).
- Adversarial lab ([docs/security-lab.md](docs/security-lab.md)): 26 deterministic tests through the hook and the brokers, run in `verify`: 23 assert a refusal (its code, the journal row where there is one, and no call reaching the broker's resource), 3 document residual gaps: injected instructions in a file or in test output are not detected (the forbidden follow-up is refused) and a command that hides its program passes the text check (the sandbox is the boundary).

### Security
- A secret in a tool's input or in its result was written to the journal as is; both are now masked before the journal.
- Inside the shell sandbox `config.worktree` is hidden like `config` (DEBT D30).
- A withdrawn approval question denies its pending action instead of leaving it requested.
- An MCP gateway `toolUseId` cannot be replayed while its action is open; spent ids of finished actions are forgotten after one hour or by the bound.

### Changed
- The journal runs in SQLite WAL mode with `synchronous=FULL`: a backup no longer stretches appends (in the same run, the append p95 under a looping backup is at most half the rollback-mode p95; measured about a fifth on the development machine). A clean stop folds the WAL back and returns to rollback mode, so 1.3.x opens the folder; after a crash keep `floor42.db-wal` and `floor42.db-shm` next to the journal. Stop 1.4.0 cleanly before a downgrade.

### Fixed
- A journal append whose commit was durable could be reported as failed when SQLite was busy right after the commit, and a retry would have stored it twice.

## [1.3.1] - 2026-11-17

### Security
- Removing a catalogue resource while OpenFGA was out removed it but kept its grants, and a resource created again under the same name took them back. The grants are now forgotten first: with OpenFGA out the removal answers 503 `FGA_UNAVAILABLE` and removes nothing.

### Fixed
- `PUT /api/iam/agents/{id}/grants` answered 500 when OpenFGA was out; it answers the documented 503 `FGA_UNAVAILABLE`.
- `GET /api/mcp` and `GET /mcp` answer 405 with the JSON error body and the `Allow` header instead of an empty body.

### Changed
- The Jira export says its import was not checked on a live Jira.

## [1.3.0] - 2026-11-17

### Added
- Script tokens bound to a Keycloak session (ADR-0083, IAM mode): issued after a password step-up and backed by their own offline session in the vault, they may approve tickets, nudge, direct, resume and answer agents, never approve a plan; unbound tokens keep the 1.2.0 default deny. A session ended in Keycloak refuses the token (401 `SCRIPT_TOKEN_UNBOUND`), checked at most once a minute. `ApiTokenIssued` gains optional `subject` and `binding`.
- OpenFGA foundation (ADR-0080): the authorization model as code with its tests (`iam/openfga`, `fga model test` in a Linux CI job), a JDK client that fails closed and asks `HIGHER_CONSISTENCY` on every check, the store and the pinned model id kept in `iam/fga.json`, OpenFGA in `iam/compose.yaml`; IAM mode refuses to start without it (`IAM_NEEDS_FGA`, `FGA_UNAVAILABLE`, `FGA_MODEL_MISMATCH`).
- Assured approvals ([ADR-0081](docs/adr/0081-assured-approvals.md)): in IAM mode an ALLOW to a call that reaches outside needs a fresh sign-in at the strong level (a code from your phone), checked by Floor42 itself and enforced in the domain; a token gets 403 `HUMAN_ONLY` with the RFC 9470 challenge. Four eyes (setting `FOUR_EYES`, off by default) adds a countersignature by a second person holding the reviewer role; turning it off needs two signatures. New event `ApprovalSigned`. Changing the IAM switch or the issuer is an assured act too; the director bearer token and a break-glass sign-in are refused on it.
- OpenFGA decides at the brokers (ADR-0080): `call_resource` and `call_mcp` ask `can_call` (the agent's grant and the mission's delegation, the session's agent and mission as a contextual `runner`), after the local revocation check, and fail closed (`FGA_UNAVAILABLE`); grants, delegations and MCP ticks are tuples (a tick's object id holds the tool's hash), each change one `AuthorizationChanged` row (new event type, ids only); the first 1.3.0 start imports `grants.json` and the ticks, journals it and renames the file to `grants.json.imported-1.3.0` (a downgrade to 1.2.0 then refuses every resource call; to restore, rename it back); a start reconciler journals what no row recorded (`reconciled: true`); a grants section per agent in the Identities panel; an allowed call's first line names `fga:<model id>`.
- Trust levels (ADR-0082): each agent is SHADOW, PROBATION or STANDARD, a cap applied after the permission table that only ever narrows it. A shadow agent's write, run and network calls are denied and shown as proposals (Proposals panel, Work menu); an incident, an alert or a quarantine lowers the level at once; five accepted patches with no incident propose a raise as a To do row that only the director's proven click applies. New event `AgentTrustChanged`; staff already hired stay STANDARD, agents hired afterwards start on probation. A 1.2.0 binary sets the row aside and applies the director's table without the cap.

### Security
- The desk's Keycloak sign-in now requires the realm role `floor42-director` in the verified access token (401 `DESK_DIRECTOR_ONLY` otherwise, no session): any other realm user, a reviewer included, could open the desk with director powers and mint a bound token. Add the role to an existing director (`iam/bootstrap.sh` does); the countersignature of a reviewer is unchanged (ADR-0081). The role is checked again at every token refresh, which ends the desk session without it, and at the first seat of every step-up. `iam/bootstrap.sh` creates the role when an existing realm lacks it.

### Fixed
- Linux CI runs the server tests on one fork: two forks raced on Quarkus' test class index (DEBT D31).

## [1.2.0] - 2026-11-16

### Added
- Identity claims in the journal (ADR-0074): `AgentTokenIssued` records `sub`, `azp`, `act` and the scopes of the verified token (all optional, never the token); the proof dossier shows them as recorded (`sub` cut to 8 characters in every export), the identity audit export carries them, older sessions keep the "not recorded" limit. A v1.2 journal row with claims is set aside by an older binary (`NEWER_VERSION`).
- Export for Jira (CSV): a button in the board panel downloads the tickets as a Jira import file (`GET /api/missions/{id}/tickets.csv`, RFC 4180). Masked like any export, a cell that would start a formula gets a leading apostrophe, no assignee column. A snapshot: nothing is sent to Jira. No new event.
- The director's MCP endpoint (ADR-0073): `POST /api/mcp` gives a model six tools (missions, questions, costs; create a mission, answer an agent's open question) with a script token, scope per tool. It never approves anything, whatever the scope; agent-written text comes back in the untrusted envelope. See `docs/headless.md`.
- Governed MCP servers (ADR-0072): a remote MCP server is a catalogue resource with an endpoint path (`/api/iam/mcp-servers`); the director pins its tools by hash, ticks the ones agents may call, and an agent runs them with its own short-lived token through the new Floor42 tools `list_mcp_tools` and `call_mcp` (permission table line "External MCP tools", built-in ASK). IAM mode only; who ticked which tool is not in the journal.
- MCP servers panel (System menu, IAM mode, ADR-0072): declare a catalogue resource as an MCP server, read its tools as an agent ("Refresh tools as"), tick the tools agents may call, and try one with a form built from its schema. Refresh and Try use the chosen agent's own token on the director's behalf, and the screen says so; server text is shown as text, marked untrusted. Web only, no new event.
- The journal is hash-chained in a side table, with signed Ed25519 checkpoints, `GET /api/journal/integrity`, and appends refused (`JOURNAL_CHAIN_BROKEN`) on a broken chain until the director acknowledges ([ADR-0079](docs/adr/0079-hash-chained-journal.md)).
- Commits the director accepts in the workshop are signed with the agent's own SSH key (kept under the data folder, retired when the agent is revoked or dismissed); the proof dossier shows "signed by <agent>, verified" or why not ([ADR-0077](docs/adr/0077-commits-signed-by-the-agent.md)).
- Human oversight report (`GET /api/missions/{id}/oversight-report`, `?format=md`, and an Oversight report panel in the Work menu): who supervised, what the agents could do, what they did, what humans overrode, from the journal, masked, every line naming its events, what is absent listed as a limit ([ADR-0075](docs/adr/0075-human-oversight-report.md)).
- The 9 o'clock standup (ADR-0078): `GET /api/standup` and a Standup panel (Work menu) give each agent's yesterday, today and blocked, built from the journal only (no model call, no mood); it opens once a day after 09:00 local, dismissal kept in the browser, nothing journaled.

### Changed
- `AgentTokenIssued` carries four optional identity fields (ADR-0074); older rows read with nulls.

### Fixed
- Crash recovery: at startup, calls authorized without outcome, approvals whose hook died and worktrees with unexplained changes are recorded once as To do rows, never with an invented outcome ([ADR-0076](docs/adr/0076-crash-recovery-reconciliation.md)).

### Security
- Governed MCP servers: allow-list of servers and tools pinned by hash, SSRF guard, no secret in a brief or a logged answer, every call through the hook and the permission table (ADR-0072).
- The director's MCP endpoint: scope checked per tool, approvals human only (ADR-0073).
- Jira CSV export: a cell that starts a formula is neutralised.
- IAM mode: a script token (any scope) is denied by default: it may only read, create a mission and call the MCP read tools and `create_mission`; approving, answering, resuming, the terminal and the IAM settings answer 403 `IAM_SCRIPT_TOKEN_REFUSED` (ADR-0064 note).

## [1.1.0] - 2026-11-16

### Added

- Day / night mode: a third button in the help bar draws the scene by the clock (default), by day (as at noon) or by night (lit city, a truly dark office). Decor only: no event, no API call, agents and missions unchanged; the choice is kept in this browser and glides over 1.5 s (at once under reduced motion). The office's night light is darker than before, whatever the choice.
- Before the start (ADR-0070): the new mission form names the folder's build tool, proposes a checks command (the last mission's on the project, else the build tool's default; never run) and lists blockers and warnings from `GET /api/preflight`. Create is disabled on a blocker. A required sandbox that does not work is now refused when the mission is created (`SANDBOX_REQUIRED`), not at its first session. No new event.
- Mission proof dossier (ADR-0071): a Work menu panel and `GET /api/missions/{id}/proof-dossier` give what a mission did (agent identities, refused actions, approvals, costs, checks, deliverables, a summary of changes), every line with its source (journal with event numbers, git, Floor42 state or derived) and every figure with its basis; always masked like the replay export, downloadable as Markdown or JSON. No new event. Token claims (sub, act, scopes) are not in the journal and are listed as a limit.
- Mission title optional in `POST /api/missions` (and in `floor42 "..."`): a blank title is the objective's first line cut on a word, one rule in `MissionTitles`.
- Workshop: "Accept the ticked, discard the rest" accepts the ticked files and discards the others after one confirmation that names both counts.
- Ends shown with their day when not today ("tomorrow 09:10", "12 Nov 14:32"): the top bar's session end and the delegation chip, which is also amber 30 minutes before its end.
- IAM mode: a screen warning 30 minutes before a delegation ends and 10 minutes before an agent token ends (no journal event, nothing renewed).

### Security

- A shell call is decided on its full command, not on its one-line summary: a command-prefix rule never allows a second line the summary flattened, and an approval covers that exact command only (compared on summaries for calls replayed from the journal).
- The replay export masks e-mail addresses as `<email>` (a new kind, `EMAIL`, in its preview); documentation-domain and no-reply addresses stay.
- Masking (D29) also hides query signatures (`sig=`, `X-Amz-Signature=`, `X-Goog-Signature=`), the rest of a `Cookie:` or `Set-Cookie:` line, and secret names written URL-encoded (`access%5Ftoken%3D`, `%22api%5Fkey%22%3A`, a URL encoded inside another).
- The sandbox no longer shows the repository's git config (a remote URL with a token, credential helpers): a stand-in holding only the repository format covers it in every layout, and git gets only allow-listed settings read on the host (`core.filemode`, `core.autocrlf`, `core.ignorecase`, `core.symlinks`, `core.repositoryformatversion`, `core.bare=false`, `extensions.*`) as `GIT_CONFIG_*`.

### Changed

- Code organised by feature (ADR-0067) and the repository laid out for parallel work (ADR-0069); browser tests tagged by domain, a test map keyed by path (ADR-0068).
## [1.0.0] - 2026-11-12

First public release. Floor42 is a local daemon and a browser office in which you direct AI agents on your own git
repositories. The agents are Claude Code sessions run with your own account; Floor42 calls no model API itself. This
entry describes the product as it stands; later entries list changes only.

### Added

- Missions on any git repository: Floor42 proposes a plan with tickets and a permission table, and nothing runs before you approve it. Agents work in git worktrees of their own; your checkout and branches are not touched.
- A team you hire, one role per agent; each agent keeps a profile built from its real missions.
- Permissions per tool (auto, ask once, ask every time, forbidden), for a mission, a ticket, an agent or a command, enforced by fail-closed hooks on every tool call and not by prompt text.
- Review: every delivery is reviewed by another agent. In the workshop you read the diff, edit files, send the work back with a directive or objections, accept it as a commit or reject it. A delivery can carry a `PROVE.md` recipe whose steps Floor42 runs in the sandbox on a throwaway copy ([ADR-0058](docs/adr/0058-prove-the-delivery.md)).
- Architecture committees: specialists analyse alone, debate, and the chair writes a decision record you adopt or reject; a critical objection is never outvoted.
- Workflows: an editor and a library of templates (Code change, Light change, Decision), with checks and tasks you define, and a loop or a graph mode per mission (ADR-0052, ADR-0061, ADR-0062).
- A desk for what waits for you: approvals, agents' questions (answer, or ask back), alerts and committee rulings.
- Costs: a cap per session, the plan's usage windows, sessions and tokens, each figure marked measured, provided, estimated or reported.
- An append-only journal in one local SQLite file. The company is rebuilt from it at each start, every event carries its origin (real or simulated), an unreadable row is left out and shown, and the journal is backed up by itself ([docs/backups.md](docs/backups.md)).
- A pull request leaves only on your click. A mission can be exported as a replay file, cleaned by code before it leaves.
- A simulated demo mission and a guided first mission that call no model and run no command, and an online demo that replays a recording of it ([ADR-0031](docs/adr/0031-online-demo-replay.md)).
- The office itself: an isometric pixel-art scene in the browser, in English and French, where each animation is backed by a journaled event.
- The director's HTTP API, described by OpenAPI at `/q/openapi`, usable with script tokens scoped `read`, `missions:write` or `all`, valid 30 days at most (ADR-0044).
- IAM mode, off by default ([ADR-0064](docs/adr/0064-agent-identity-keycloak.md), [`iam/README.md`](iam/README.md)): each agent is a client in a Keycloak realm, the director signs in through Keycloak and authenticates again at each plan approval, and each session gets a token (OAuth 2.0 Token Exchange, 8 hours at most) that Floor42 checks on every hook and MCP call. An agent or one mission's delegation can be revoked alone.
- Token broker (IAM mode): the MCP tool `call_resource` lets an agent call an external HTTP resource you declared and granted, without holding its credential. The daemon's JVM needs `-Djdk.httpclient.allowRestrictedHeaders=host`.
- Session risk (ADR-0066): a rise in a real session's risk level is journaled with its reason codes, and a mission can be quarantined.
- `floor42 doctor` lists what a machine has and lacks, with the command to copy; the app shows the same checklist at the first start.
- Distribution: a release zip that needs only Java 25, `install.sh` for Linux, and a Docker image `ghcr.io/padashtan/floor42` without Claude Code. The zip, the two CycloneDX SBOMs and the image carry a signed build provenance, and the zip is reproducible ([docs/releases.md](docs/releases.md)).

### Security

- The daemon listens on `127.0.0.1` only, refuses foreign Hosts and Origins, and opens its API to the signed-in director only.
- Shell commands that touch Floor42 itself, push or call `gh` are denied whatever the permission table says.
- A real mission's shell runs only in the sandbox ([ADR-0063](docs/adr/0063-sandbox-mandatory-for-real-missions.md), [ADR-0028](docs/adr/0028-shell-sandbox-wsl.md)): WSL 2 on Windows, the same script natively on Linux. A command sees its worktree and the distribution's system files, and the network is off unless you open it. Without a working sandbox a real session does not start (`SANDBOX_REQUIRED`) until you switch unsafe mode on with a reason; every switch is journaled.
- In IAM mode a session's token is accepted only from the process Floor42 launched, and the broker never returns, logs or journals an exchanged token.
- Floor42 never handles your Claude credentials: you sign in to your own, unmodified Claude Code.
- What is enforced, what is not, and how to report a vulnerability: [SECURITY.md](SECURITY.md) and [docs/threat-model.md](docs/threat-model.md). The assurance chain and its recorded baselines: [docs/quality.md](docs/quality.md), [docs/security-scanning.md](docs/security-scanning.md).

### Platforms

- Tested on Windows 11 with WSL 2 (a Debian distribution) and on Linux. macOS is not verified.
- The native Linux sandbox is measured on Debian 13 (WSL 2 kernel) and on Ubuntu 24.04.5, which needs one system change (README, "Ubuntu 23.10 and later"); other distributions are not measured.
- Needs JDK 25, git, and Claude Code 2.1.295 or later for real agents; Node.js 24 only to build from source.

### Known limits

- One maintainer, first public release: expect rough edges.
- On macOS and inside Docker the sandbox is unavailable. A real mission there needs unsafe mode, and its agents' shell then runs as the daemon's own OS user, which can write the journal ([SECURITY.md](SECURITY.md)).
- IAM mode needs the sandbox. Process binding is measured on Windows with WSL only, and against Keycloak 26.8.1 only. A quarantined or suspended mission whose plan is already approved cannot be re-delegated: cancel it and plan again ([docs/iam-revocation.md](docs/iam-revocation.md)).
- `install.sh` was checked against a fake release archive, not yet against a real release.
- The journal is read whole at each start (measured with synthetic events on one machine: 100,000 events, about 40 MB on disk and 1.6 s).
- The browser journeys run on the developer machine before a tag, not in CI.
- API routes marked `x-stability: unstable` may change in any release.
- Open debt is listed in [docs/DEBT.md](docs/DEBT.md).

[Unreleased]: https://github.com/padashtan/floor42/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/padashtan/floor42/releases/tag/v1.0.0
