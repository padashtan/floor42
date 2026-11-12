# Architecture decision records

One file per structural decision: context, decision, consequences, and the signal that would make us reopen it.

The product was called Office until 1.5.0, when it became Floor42: the records written before say "Office", `office.*`
and `~/.office`, and are kept as written. Read them with today's names (`floor42.*`, `~/.floor42`).

Numbers 0048 and 0057 are not used. The verdict on each record (current, superseded, outdated detail, to
revisit) and the evidence in the code are in [`2026-11-06-review.md`](2026-11-06-review.md).

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-local-web-application.md) | Local web application served by a daemon | Accepted |
| [0002](0002-java-quarkus-and-vue.md) | Java 25 + Quarkus daemon, Vue 3 + TypeScript UI | Accepted |
| [0003](0003-module-boundaries.md) | Three modules, framework-free domain | Accepted |
| [0004](0004-pixelated-low-poly-rendering.md) | Low-poly 3D rendered as pixel art | Accepted, validated by spike |
| [0005](0005-provenance-and-action-lifecycle.md) | Mandatory provenance and explicit action lifecycle | Accepted |
| [0006](0006-claude-code-as-agent-runtime.md) | Agents are Claude Code sessions, not API calls | Accepted, validated by spike |
| [0007](0007-local-daemon-security.md) | Loopback-only daemon with session token | Accepted, amended by [0034](0034-director-credential-out-of-agents-reach.md) and [0044](0044-sessions-tokens-and-exact-origin.md) |
| [0008](0008-licensing.md) | AGPL-3.0 + commercial license + CLA | Superseded by [0043](0043-apache-2-license.md) |
| [0009](0009-event-sourced-company.md) | Event-sourced company over an append-only SQLite journal | Accepted, amended 2026-10-31 |
| [0010](0010-director-chosen-autonomy.md) | Autonomy is chosen by the director, tool by tool | Accepted |
| [0011](0011-live-missions.md) | How live missions run | Accepted |
| [0012](0012-workspace-confinement.md) | File tools confined to the agent's worktree | Accepted |
| [0013](0013-engineering-workshop.md) | The engineering workshop | Accepted |
| [0014](0014-verification-and-budget.md) | Checks measured by Office, budgets held between sessions | Accepted |
| [0015](0015-architecture-committee.md) | The architecture committee | Accepted |
| [0016](0016-plan-usage-limits.md) | Plan usage windows and the hold before a session they would cut | Accepted |
| [0017](0017-ticket-by-ticket-validation.md) | A planner proposes tickets, the director validates them one by one | Accepted, "one at a time" superseded by [0019](0019-hiring-and-parallel-work.md) |
| [0018](0018-review-consultation-and-research.md) | A reviewer in doubt consults a peer or a web research, once per review | Accepted |
| [0019](0019-hiring-and-parallel-work.md) | Hiring for a role, tickets in parallel, one desk each, a night when the quota is spent | Accepted |
| [0020](0020-end-of-mission-retrospective.md) | An end-of-mission retrospective, with lessons the director validates | Accepted |
| [0021](0021-settings-and-screen-language.md) | Company settings in the journal, the screen's language in the browser | Accepted |
| [0022](0022-staffing-review.md) | Reinforcing the team: the planner advises, the director answers yes or no | Accepted |
| [0023](0023-skills-and-subagents.md) | Agents may load skills and start subagents | Accepted |
| [0024](0024-idea-committee.md) | The idea committee, before a new project | Accepted |
| [0025](0025-session-cost-cap.md) | A cost cap on every session | Accepted, amended 2026-10-31 |
| [0026](0026-agents-words-and-conversations.md) | Agents' words are recorded; a thinking agent talks over a coffee | Accepted |
| [0027](0027-office-mcp-server.md) | Office's own MCP server; agents ask the director mid-session | Accepted |
| [0028](0028-shell-sandbox-wsl.md) | Shell commands and checks isolated in a WSL2 sandbox | Accepted, amended 2026-11-03; mandatory for REAL missions since 1.17 ([0063](0063-sandbox-mandatory-for-real-missions.md)) |
| [0029](0029-project-picker.md) | Picking a project: the ones already used, and a read-only folder browser | Accepted |
| [0030](0030-archive-wall.md) | The archive wall shows the repository's commits and each agent's branch | Accepted |
| [0031](0031-online-demo-replay.md) | The online demo replays a recording of the simulated mission | Accepted |
| [0032](0032-invented-names.md) | Built-in characters have invented names | Accepted |
| [0033](0033-sandbox-readiness.md) | Sandbox readiness: what a project needs, what the sandbox has, how to install the rest | Accepted |
| [0034](0034-director-credential-out-of-agents-reach.md) | The director's credential out of the agents' reach | Accepted, amends [0007](0007-local-daemon-security.md); token file deprecated by [0044](0044-sessions-tokens-and-exact-origin.md) |
| [0035](0035-shareable-mission-replay.md) | A mission exported as one self-contained replay file, cleaned by code | Accepted |
| [0036](0036-pull-requests.md) | Pull requests on GitHub, on the director's click, with the director's own gh | Accepted |
| [0037](0037-mission-deliverables.md) | A mission's deliverables, viewed and downloaded without the daemon running them | Accepted |
| [0038](0038-resume-work-and-sessions.md) | Resuming work: the existing paths, and Claude Code's --resume only when measured safe | Accepted |
| [0039](0039-end-of-mission-report.md) | The end of a mission: a report, the next steps in one place, a small ceremony, never a silent end | Accepted |
| [0040](0040-plugin-install-and-mission-assets.md) | Installing plugins on the director's click, and choosing what a mission's sessions receive | Accepted |
| [0041](0041-director-terminal.md) | The director's own Claude Code in a terminal, through a pseudo-terminal on the JDK's FFM API and xterm.js | Accepted |
| [0042](0042-journal-quarantine-and-backups.md) | An unreadable journal row is left out and shown; an event that cannot be applied still stops the start; the journal is copied at every start and every day | Accepted, restore marker superseded by [0044](0044-sessions-tokens-and-exact-origin.md) |
| [0043](0043-apache-2-license.md) | Apache-2.0, contributions under the DCO; supersedes ADR-0008 | Accepted, supersedes [0008](0008-licensing.md) |
| [0044](0044-sessions-tokens-and-exact-origin.md) | Sessions and script tokens in memory, the exact Origin, and what an agent of the same user still reaches | Accepted, amends [0007](0007-local-daemon-security.md) and [0034](0034-director-credential-out-of-agents-reach.md) |
| [0045](0045-guided-first-mission.md) | A guided first mission, simulated on Floor42's own sample repository | Accepted |
| [0046](0046-numbered-committees-and-workflow-interpreter.md) | Committees are numbered within their mission; the implicit workflow becomes a value read by an interpreter | Accepted |
| [0047](0047-director-meeting.md) | The director's meeting: hold at the next tool call, close the session cleanly, resume from the brief | Accepted, amended by [0049](0049-meeting-voices.md) |
| [0049](0049-meeting-voices.md) | Turns of speech at the director's meeting, and the meeting room in the scene | Accepted, amends [0047](0047-director-meeting.md) |
| [0050](0050-workflow-pinned-to-a-mission.md) | A mission pins its workflow as an opaque document; the director writes company workflows in a list editor | Accepted, amended by [0052](0052-workflows-v2.md) |
| [0051](0051-deliverables-destination-and-file-viewer.md) | Where a mission's deliverables land, and a file viewer panel | Accepted |
| [0052](0052-workflows-v2.md) | Workflows v2: a timeline editor, steps the director writes, a library of workflows | Accepted |
| [0053](0053-reviewer-calibration.md) | A reservation cites a criterion and says what to change | Accepted |
| [0054](0054-mission-archive.md) | Archiving a closed mission | Accepted |
| [0055](0055-uncommitted-deliverables-at-acceptance.md) | Work not in a commit is said before the director accepts a mission | Accepted |
| [0056](0056-docker-for-a-mission.md) | Docker for a mission, by the director's switch | Rejected, replaced by ADR-0065 |
| [0058](0058-prove-the-delivery.md) | Prove the delivery: a recipe from the producer, run by Floor42 in the sandbox, finished by the director | Accepted |
| [0059](0059-ask-back.md) | Ask back before answering an agent's question | Accepted |
| [0060](0060-objections-at-acceptance.md) | Objections at acceptance become work in the same mission | Accepted |
| [0061](0061-workflow-tasks.md) | Workflow tasks (increment 4 of ADR-0052) | Accepted |
| [0062](0062-graph-driven-missions.md) | Graph-driven missions | Accepted |
| [0063](0063-sandbox-mandatory-for-real-missions.md) | The WSL sandbox is mandatory for REAL missions; unsafe mode is a journaled exception | Accepted |
| [0064](0064-agent-identity-keycloak.md) | Agents get a Keycloak identity, delegated per mission, with a token broker for external resources | Accepted (v1.0.0) |
| [0065](0065-container-broker.md) | Containers for missions through a per-mission broker, reached with an exchanged token | Proposed |
| [0066](0066-session-risk-detection.md) | Session risk v1: detection, alerts and a quarantine the director applies | Accepted (v1.0.0) |
