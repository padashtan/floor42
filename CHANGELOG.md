# Changelog

All notable changes to Floor42, one line each. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
The reasons behind a change are in the linked decision records ([`docs/adr/`](docs/adr/README.md)); the working
details in [`docs/PROGRESS.md`](docs/PROGRESS.md). Floor42 was developed in private, first under the name Office,
before v1.0.0: that work is not published as versions, and the decision records keep its history and dates.

## [Unreleased]

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
