# ADR-0050: A mission pins its workflow as an opaque document; the director writes company workflows in a list editor

Date: 2026-11-01 · Status: Accepted · Amended by [ADR-0052](0052-workflows-v2.md)

## Context
ADR-0046 made the implicit workflow a value (`Workflow.BUILT_IN`) read by a pure interpreter, and listed what its step 3 needs: a `WorkflowPinned` event recorded with the mission's creation, the workflow opaque to the journal, one reader per schema version in the domain, missions journaled before keeping `BUILT_IN`, and a build that cannot read a schema version keeping the mission readable while refusing to run it. The director gave the go for step 3 on 2026-11-01, knowing it is the point of no return: 1.8.0 does not know the new event types.

The director's decisions that bear on it (ADR-0046): review is never removed for code (2); the director writes workflows and the planner may propose one (2); auto-acceptance is off by default, by rule, with a cost cap (3); a list editor comes first (4); a running step is relaunched, never modified (5).

Facts read from the code on `integ/1.9.0` (d041561):
- The domain is JDK only (`ArchitectureTest`): it has no JSON library.
- `EventCodec` refuses unknown fields and enumerated values (`NEWER_VERSION`); event types are discovered from the sealed hierarchy and checked against `event-types.txt`.
- A real mission is created by `MissionFactory` in several commands: `MissionCreated`, then its first ticket, then assignments, permissions and `MissionPlanSubmitted`. The demo and the rehearsal (ADR-0045) create simulated missions through the domain directly.
- `DomainEvent.java` is 753 lines (D19), at the ceiling `SizeGateTest` allows: no event can be added to it.
- The planner's report is parsed by Floor42 for tickets (`Briefs.plan`, "TICKET, WHY, DONE WHEN, DEPENDS ON, SIZE", ADR-0017); its limit of 8 tickets is a server constant.
- The real-journal copy of 2026-11-01 (python `sqlite3` backup of `~/.floor42/floor42.db`): 4,471 rows, 0 left out, snapshot SHA-256 `473d2863…20964`; `WorkflowEquivalence` over it: 199,775 decisions, 0 differ.

## Options

### 1. How the workflow is stored
| Option | Cost | Verdict |
|---|---|---|
| A. Steps as JSON objects the codec maps to classes (`List<Step>` in the event) | No parser to write. But a step kind or a setting a later build adds is an unknown type or field: the row is quarantined (`NEWER_VERSION`) and the mission loses its workflow, which ADR-0046 rules out | Rejected |
| B. A JSON document carried as text, with its `schemaVersion` (`String document, int schemaVersion`) | The codec sees one string and one number: any document decodes. The domain needs its own small JSON reader and writer (about 250 lines with their tests), and one reader per schema version | **Taken** |
| C. A line format of the domain's own (`plan maxTickets=3`) | A smaller parser, but nothing else reads it, and ADR-0046 says a JSON document | Rejected |

### 2. Which missions are pinned
| Option | Cost | Verdict |
|---|---|---|
| A. Only a mission given a written workflow; the built-in one stays implicit | Fewer rows 1.8.0 cannot read. But an unpinned mission follows whatever `BUILT_IN` says in the build that runs it: a later change of `BUILT_IN` would change missions already running, against decision 5 | Rejected |
| B. Every real mission `MissionFactory` creates, the built-in workflow included, in the same command as `MissionCreated` | After a rollback to 1.8.0, one quarantined row per mission created since (red banner, ADR-0042); the mission itself is read, since no later event builds on the pin. Simulated missions (demo, rehearsal) are scripted by Floor42 and stay unpinned: `create` pins REAL missions only, and applying a pin on a SIMULATED mission stops the replay | **Taken** |

### 3. When the document is read
| Option | Cost | Verdict |
|---|---|---|
| A. At every query | The dispatcher asks for every mission at every wake-up; the equivalence bench asks about 200,000 times on the real journal | Rejected |
| B. Once, when the pin is applied, the result kept on the mission: a `Workflow`, or the reason it cannot run | The reader never throws, so a document it cannot read never stops the replay | **Taken** |

### 4. How the planner proposes a workflow
| Option | Cost | Verdict |
|---|---|---|
| A. A tool `propose_workflow` on Floor42's MCP server (ADR-0027) | A new tool, its permission line, a live-session test; for one optional line of text | Rejected |
| B. An optional `WORKFLOW:` line in the plan report, parsed by Floor42 like the tickets | Same channel as the tickets, testable with the fake Claude Code. The proposal goes to the company's list for the director; it cannot change the mission being planned, which pinned its workflow before the planner started | **Taken** |

### 5. Company workflows: their key
| Option | Cost | Verdict |
|---|---|---|
| A. An identifier type (`WorkflowId`) | A new identifier in the codec and the API, for a list the director names anyway | Rejected |
| B. The name, unique, `built-in` reserved | A rename is a save under the new name, then the director removes the old one | **Taken** |

## Decision
- **D19 first.** `DomainEvent.java` is split into one file per area (`MissionEvent`, `TicketEvent`...), the records nested in their area: the simple names the journal stores do not change, `event-types.txt` and the goldens are the check. A new area `WorkflowEvent` holds the new types.
- **Events** (all REAL, written only by 1.9.0 and later):
  - `WorkflowPinned(mission, document, schemaVersion)`: emitted by `missions().create(ctx, title, objective, workspace, workflow)` right after `MissionCreated`, in the same command, so a mission is never created without it and never pinned after its first ticket. Applying a second pin, or a pin on a mission that already has a ticket, stops the replay (the code disagrees with the journal, ADR-0042).
  - `CompanyWorkflowSaved(name, document, schemaVersion)`, `CompanyWorkflowRemoved(name)`: the director's list.
  - `WorkflowProposed(mission, proposal, document, schemaVersion)`: the planner's proposal (an agent's event, the mission's), numbered from 1 across the company. `WorkflowProposalClosed(proposal, adopted)`: the director saved it (edited or not) or dismissed it.
- **The document, schema version 1** (`WorkflowDocument`, domain): `{"name":"…","steps":[{"step":"plan","maxTickets":3},{"step":"approval"},…]}`. The writer is canonical (fixed key order, a setting at its default left out), so the built-in workflow written and read back is `Workflow.BUILT_IN` again. The v1 reader refuses, with a reason the director reads: invalid JSON, a duplicate or unknown key, an unknown step, an unknown setting or one out of range, and anything `Workflow` refuses. Any other schema version is "written by a newer Floor42 (workflow schema N; this build reads 1): upgrade to run it". A reader per version is a `case` of its own; a version's reader is never changed once released, and any change of what `Workflow` accepts, or of a setting's range or default, comes with a new schema version.
- **Per-step settings in v1**, each honoured by code: `plan.maxTickets` (1 to 8, default 8, both literals of the v1 schema in the domain: the planner's brief, the planning ticket's criterion and the check of its plan read the pinned value). The other steps take no setting in v1. A retrospective model per workflow was considered and left out: its natural default, the company setting read when the retrospective runs, would let a later change of the setting change a pinned mission.
- **Bounded documents.** A document is at most 8,192 characters and nests at most 4 levels; the reader counts its depth before it descends, so no document can exhaust the stack, and it never lets a JDK exception out: a number is at most 2 digits and checked before it is parsed, an escape is checked before it is decoded, so every malformed input is a refusal it raises itself. A `schemaVersion` of 0 or less is refused as corrupt, not read as "newer".
- **`Workflow` invariants**, checked when a workflow is built, so when it is written and again when it is read: a non-blank name; the steps in Floor42's order, each once; the director's approval and acceptance; produced work reviewed (the director's acceptance in place of the review stays refused until it is built, as in ADR-0046); the settings in range.
- **Workflows without production.** A workflow without `Produce` is a decision workflow: it must hold a `Committee`, a mission on it opens only as a committee, and its arbitration does not offer "adopt and implement", so an answer naming it is refused as any answer not offered, since nothing would implement it; opening a mission that is not a committee on it is refused (`WORKFLOW_PRODUCES_NOTHING`). A ticket that no step of the mission's workflow takes is named by the stall diagnosis (`NO_STEP_TAKES_IT`) instead of waiting silently. `WorkflowSubsetsTest` runs every subset of steps through `Workflow` (12 allowed of 32) and checks what follows a ruling and an end; `WorkflowStallTest` checks that a ticket no step takes, to do or in progress, is never started and is named.
- **What a mission follows** (`MissionWorkflow`, `company.workflows().of(mission)`): `Unpinned` (every mission journaled before 1.9.0, and simulated missions) follows `Workflow.BUILT_IN`; `Pinned(workflow)` follows its own for good; `Unrunnable(schemaVersion, reason)` is read and shown but does not run.
- **Unrunnable, enforced by code.** The interpreter's position is `Refused(reason)` (until the mission ends): the dispatcher starts nothing, the plan's approval is refused (`WORKFLOW_UNREADABLE`), no implementation follows a ruling, no retrospective follows the end, and the stall diagnosis shows the reason on the mission panel. `MissionView.workflow` carries the name, the steps and the reason.
- **The interpreter per mission.** `MissionInterpreter` (domain, holds nothing) reads the mission's `MissionWorkflow` and asks a `WorkflowInterpreter` of that workflow. It replaces the one built-in interpreter bean: `TaskSelection`, `EventReactions`, `Retrospectives`, `StallDiagnosis` and `MissionFactory` ask it per mission.
- **Opening a mission with a workflow.** `POST /api/missions` takes an optional `workflow` (a company workflow's name; absent: the built-in one). `MissionFactory` resolves the workflow once, into a value: the opening is asked of that value and the same value is written into the pin, so a save from another tab between the two cannot split them. Refused before anything is recorded: an unknown name (`UNKNOWN_WORKFLOW`), a company workflow this build cannot read (`WORKFLOW_UNREADABLE`), committee members asked of a workflow without a committee, the planner asked of a workflow without a plan step, and a mission that is not a committee on a workflow that produces nothing.
- **Names.** A name is 1 to 60 characters of Latin letters, digits, spaces and `.-_'`, compared after NFKC normalisation and case folding; `built-in` is reserved. Saving under an existing name replaces it only when the director asks for it (`replace`), otherwise it is refused (`WORKFLOW_EXISTS`); a proposal is adopted under a name of the director's choice by the same rule.
- **The planner's proposal is checked before it is recorded.** Floor42 reads at most one `WORKFLOW:` line per report, builds the `Workflow` value from it (its steps only, settings at their defaults), and records `WorkflowProposed` with the document Floor42 itself writes and `schemaVersion` 1; a line the reader refuses is not recorded, and the reason is logged. The agent never chooses the version or the document's text.
- **The list editor** (Workflows panel): the built-in workflow (read only) and the company's; a workflow's name, its steps in Floor42's order (optional steps added or removed, approval and acceptance always there) and each step's settings; the planner's proposals, to edit and save, or dismiss. Editing or removing a company workflow never changes a pinned mission: the pin is a copy of the document. A company workflow this build cannot read is shown read only, with its reason, and cannot be picked. The mission form offers the company's workflows.
- **Auto-acceptance (decision 3) is not built here.** ADR-0046 lists it among step 3's needs as a step setting, and this step builds per-step settings. Its behaviour (Floor42 accepting a reviewed ticket's work under a cost cap measured in `Usd.micros`) is a new path to the workshop's commit, which needs its own design and review: it is the next step. Until then the v1 reader refuses an `autoAccept` setting by name, so no document can claim it; since readers are frozen, auto-acceptance comes with schema version 2. This amends ADR-0046's "What step 3 needs", whose auto-acceptance line moves to the next step.

## Consequences
- **Point of no return, announced.** A rollback to 1.8.0 quarantines every `WorkflowPinned`, `CompanyWorkflow*` and `WorkflowProposal*` row (`NEWER_VERSION`, red banner). Missions stay readable there and follow 1.8.0's built-in workflow; company workflows and proposals are lost to that build only. `CHANGELOG.md` says so.
- **Equivalence.** `WorkflowEquivalence` runs every journal twice: as journaled, and with a `WorkflowPinned` of the built-in document inserted after each REAL `MissionCreated` that has none. Both compare every decision with c62612d's rules (`LegacyWorkflowRules`) through `MissionInterpreter`, as the daemon now asks. Those two runs only prove that the built-in workflow, pinned or not, behaves as before; what is new is proven by mutant pins on the same journals: a pin of schema version 2 turns every decision of its mission into a refusal (and an interpreter that falls back to the built-in workflow on an unreadable pin fails the bench); a pin without the retrospective differs from c62612d on the retrospective decisions and nowhere else. Server tests prove that `MissionFactory` writes the pin right after `MissionCreated` (the next sequence number), and that `plan.maxTickets` changes the planner's brief and the check of its plan only.
- **No apply reads a pinned workflow** beyond the pin's own checks (one pin, before the first ticket). So a journal whose pinned mission went on under 1.8.0 after a rollback (more tickets than its `maxTickets`, a retrospective its workflow leaves out) replays on 1.9.0 again: `WorkflowPinTest.eventsThatContradictAPinStillReplay` holds such a journal (more tickets than its plan allows).
- The goldens change where a test journal now holds a pin or a brief now names the workflow: re-recorded in a commit of their own, each diff checked to remove no line.

- **Known limit.** None left in the domain: `missions().create` without a workflow is the simulated mission only, and a REAL mission is created by the overload that pins its workflow (debt D24, paid). A REAL mission without a pin exists only in a journal written before 1.9.0, and tests build it by applying its `MissionCreated` row by hand.

## Reopen if
- A setting or a step needs state outside the journal (a timer, a quota read at run time).
- A schema version 2 is needed: its reader is added, version 1's stays as it is.
- A rollback to 1.8.x must keep company workflows: then the reader ships one release before the writer.
