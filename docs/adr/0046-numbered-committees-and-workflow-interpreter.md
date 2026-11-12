# ADR-0046: Committees are numbered within their mission; the implicit workflow becomes a value read by an interpreter

Date: 2026-11-01 · Status: Accepted

## Context
The configurable workflow engine was designed and reviewed adversarially (verdict "broken", then fixed). The director decided:

1. Review is never removed for code; only the director's own acceptance may replace it.
2. The director writes workflows; the planner may propose one.
3. Auto-acceptance is off by default, granted by rule with a cost cap.
4. A list editor comes first.
5. A running step is cancelled and relaunched, never modified.

The review set a safe order of seven steps. This record covers steps 1 and 2 only, the two that change nothing a journal already holds. Step 3 (a workflow pinned to a mission) is the point of no return: it writes a new event type that an older build cannot read (ADR-0042), so it is not taken here.

Two facts stood in the way:
- **One committee per mission.** `CommitteeHall` kept a `Map<MissionId, Committee>`: a second `CommitteeConvened` for the same mission would have replaced the first on replay, history included. A workflow with two committee steps, or a committee relaunched after a cancellation (decision 5), needs several committees per mission, each addressable.
- **The workflow existed only as code paths.** Plan, approval, committee, tickets, production, review, acceptance and retrospective were decided in `TaskSelection` (what runs next), `EventReactions` (what follows a ruling), `MissionFactory` (how a mission opens) and `Retrospectives` (what follows the end). A written workflow can only replace a workflow that is itself a value.

## Options
| Option | Cost measured | Verdict |
|---|---|---|
| A. Numbers in a new event type (`CommitteeRenumbered`) | A new type is unreadable by 1.7.0 (`NEWER_VERSION` quarantine) for every journal that convenes a committee; and every existing committee event still has to be addressed somehow | Rejected |
| B. Key committees by their decision ticket | No journal change. But a committee relaunched on the same ticket (decision 5) collides again, and the ticket is not in every committee event (`CommitteeAdvanced`, `CommitteeClarified`...): each would need a lookup from the mission's current state, the very ambiguity being removed | Rejected |
| C. An additive `committeeNo` on every `Committee*` event, read as 1 when absent | One nullable component per event (ADR-0009 amendment); rows written before decode as committee 1, which is the rule of that time. Measured on a copy of the director's journal: 4,140 rows, 0 left out, snapshot identical once the committee lines are put back in their former shape | **Taken** |
| D. Interpreter in the server, over the Quarkus beans | Keeps the domain smaller, but the decisions stay untestable without a daemon, and step 3 would pin a workflow the domain cannot read | Rejected |
| E. Interpreter in the domain, pure, over the `Company` read API and a `Workflow` value | Same decisions, testable on any journal; the server only acts on them | **Taken** |

## Decision
- **Numbered committees.**
  - Every `Committee*` event carries `Integer committeeNo`, the last component. The record's compact constructor reads null as 1 (`CommitteeEvent.numbered`); the sealed `CommitteeEvent` interface exposes `mission()` and `committeeNo()`. No new event type.
  - `CommitteeHall` keeps `Map<MissionId, List<Committee>>`, oldest first; `Committee.number()` and `Committee.ref()`.
  - A `CommitteeConvened` whose number is already held replaces that committee, as a second convening did before numbers existed. A number past the next one, or an event naming a committee never convened, stops the replay (ADR-0042: the code disagrees with the journal).
  - Every query and command names one committee as a `CommitteeRef(mission, number)`: `committees().find(ref)`, `of(mission)` (the history), `latest(mission)`; `contribute`, `contributeAsDirector`, `clarify` and `sendBack` take the ref. A question of the desk carries no number: it belongs to the latest committee on its ticket.
  - A mission convenes its next committee only once its latest one is ruled on. While one is open, the refusal is unchanged (`DUPLICATE_COMMITTEE`).
  - **Step 1 prepares the storage only: no command can convene a second committee yet.** Convening needs the mission in `PLANNING`, a committee is ruled on only once the mission is `IN_PROGRESS`, and a mission never goes back to `PLANNING`. A second committee exists only in a journal that holds one (the replay tests write it by hand). Convening during `IN_PROGRESS` (an escalation, a step of a written workflow) comes with the step that needs it.
  - REST: `POST /api/missions/{id}/committees/{no}/contribute` and `/send-back`. `MissionView.committee` (the latest) carries its `number`; `MissionView.committees` lists the history. The meeting room shows "Committee n of N" and the earlier ones.
- **The interpreter boundary.**
  - `dev.floor42.domain.workflow.Workflow` is a value: a name and an ordered list of sealed `Step`s (`Plan`, `Approval`, `Committee`, `Produce`, `Review`, `Accept`, `Retro`). Its constructor refuses steps out of Floor42's order (plan, approval, committee, produce, review, accept, retro) or listed twice, a workflow without the director's `Approval` or `Accept` (no workflow runs unapproved or closes on its own), and a workflow that produces without `Review` (decision 1). The director's acceptance standing in for the review is decided but not built: until the interpreter drives it, it is refused rather than accepted and left with tickets in review that nobody takes. `Approval` and `Accept` are required, not interpreted: the plan's approval and the mission's closing stay the director's commands.
  - `Workflow.BUILT_IN` is today's behaviour written down. It is a constant of the code, never journaled.
  - `WorkflowInterpreter` (pure, holds only its workflow) decides. It answers `opening(...)` (how a mission opens), `position(company, mission)` (`Drafting`, `AwaitingApproval`, `Deliberating(ref)`, `AwaitingRuling(ref, committee tickets)`, `WorkingTickets(committee tickets to leave out)`, `Suspended`, `Ended`), `step(company, ticket, assigneeRole)` (`PLAN`, `PRODUCE`, `REVIEW`, `NONE`), `afterRuling(event)` (`Implement(ref)` or `Decided(ref)`) and `afterEnd(mission)` (`HoldRetro` or `NoRetro`).
  - The server acts: `TaskSelection` switches over the position and picks agents, `EventReactions` and `Retrospectives` switch over what follows, `MissionFactory` switches over the opening. Agent choice, holds, budgets and git stay where they were: they are not workflow.
  - One `WorkflowInterpreter` bean (`Workflows`, `@Singleton`) serves the built-in workflow to every mission.
  - The committee the interpreter names is the one acted on: a committee task carries its `CommitteeRef` to `CommitteeRunner`, `AfterRuling.Implement(ref)` reaches `Implementation.implement(ref)`, and `StallDiagnosis` reads `AwaitingRuling(ref)`. None of them looks up "the latest" again.
- **A permission for one ticket (D15).** The review found that adopting a decision for implementation set Write and Edit to "ask" on the whole decision mission. The workflow will run steps with their own permissions, so the fix is a rule's scope, not a special case:
  - `PermissionRule` gains a nullable `ticket`, the last component (absent: the whole mission, as every rule journaled before).
  - A ticket's rule always names its tool (refused otherwise, decoded rows included).
  - A call is evaluated with the ticket it works on (`evaluate(agent, ticket, ...)`). The most specific matching rule decides, compared on what it names in this order: agent, tool, command, ticket (weights 8, 4, 2, 1); between equals, the most restrictive. So a ticket's rule outranks only the mission's line for its tool. A mission rule naming a command of that tool (`DENY Bash 'rm'`) and any rule naming the agent (a reviewer's denial) outrank it. `PermissionPrecedenceTest` checks the twelve possible rule shapes pairwise with every decision (2,304 cases).
  - `Implementation` records the implementation ticket and its two rules (`PermissionRule.forTicket`) in one command, so the ticket never exists without them. Sessions are offered the tools of their ticket (`Briefs.offeredTools(mission, ticket)`); their brief states the table of their ticket.
- **New fields are left out when they hold the old meaning** (`WireDefaults`, codec mix-ins). `committeeNo` is written from a mission's second committee on, and `ticket` only for a ticket's own rule. A row 1.8.0 writes for a first committee, or a mission-wide rule, is byte for byte what 1.7.0 wrote, so a rollback reads it. A row that needs the new field is quarantined by 1.7.0 as `NEWER_VERSION` (it refuses unknown fields), never misread.

## Consequences
- Step 1 changes the journal's format additively, as above: a rollback to 1.7.0 loses only the rows of a second committee or of a ticket's rule, and says so.
- The golden master was recorded again once, in its own commit. The whole diff is `committeeNo=1` on committee events, the committee list shape (`number=1`) and the new `committees M-n` query lines. Removing those rewrites gives the former files byte for byte.
- The real-journal copy replays with 0 rows left out. Its snapshot SHA-256 changes from `5fe454ea…74909` to `4862edb6…c51b5`; undoing the three committee rewrites gives back `5fe454ea…74909`. Step 2 leaves it at `4862edb6…c51b5`, and leaves the goldens, `briefs.txt` and `event-views.txt` untouched.
- D15 changes the snapshot once more, by `ticket=null` on every rule. The real-journal SHA-256 becomes `095fb90b…fe97c7`, and removing that text gives back `4862edb6…c51b5`.
- **The equivalence bench** (`WorkflowEquivalence`, server tests) replaces the first one, which a counter-check found weak: it read roles without the first staff's fallback, wrote the former rule with the new `latest()` API, compared only when the interpreter's output changed, and never compared the opening. The bench now runs `LegacyWorkflowRules`, the decisions copied from `git show c62612d` (TaskSelection.next and isPlanning, EventReactions, Retrospectives.react, MissionFactory.create, StallDiagnosis, Staff.roleOf with FIRST_STAFF, MissionHolds), against a frozen copy of the committee store of that commit (one committee per mission, replaced by each convening, stages moved by the old handlers). After every event it compares every decision:
  - per mission, what the dispatcher starts, the tickets it leaves out, and whether a ruling is awaited;
  - per ticket, what it is given;
  - per event, whether an implementation or a retrospective follows.
  The opening is compared over all 144 combinations of members, panel, repository and planning.
- **Results.** Every golden journal of the domain: 2,554 events, 13,498 decisions, 0 differ. The real-journal copy: 4,140 events, 178,591 decisions, 0 differ. The bench catches what the first one missed:
  - reading roles without the fallback (`Agent::workRole`) makes the real journal disagree (PLAN at c62612d, PRODUCE now) on the planning tickets of an agent with no recorded work role (the first staff, before ADR-0019);
  - treating `AWAITING_ANSWERS` as deliberating is caught at events where the interpreter's output does not change (`#322 QuestionAnswered`...).
- `WorkflowDecisions` (domain test-jar) only records the interpreter's decisions as golden masters (`golden/workflow/*.txt`); it proves nothing. Adding `AwaitingRuling` changed that golden on the five lines where a committee waits for its ruling, and nowhere else. The real-journal snapshot SHA-256 stays `095fb90b…fe97c7`.

## What step 3 needs (not done here)
- **`WorkflowPinned(mission, workflow, schemaVersion)`**, recorded with the mission's creation, before its first ticket. The mission then follows the pinned workflow for good, even after the director edits the workflow (decision 5: a running step is relaunched, never modified).
- **The workflow is opaque to the journal.** The event stores the workflow as written (a JSON document) with its `schemaVersion`; `EventCodec` must not map steps to classes. A build that does not know the schema version keeps the mission readable and refuses to drive it, instead of quarantining the row and losing the mission.
- **A `schemaVersion` reader per version** in the domain, turning the document into a `Workflow` value, or refusing it with a reason shown to the director. `Workflow`'s invariants (review or acceptance for code; each step once) are checked when it is written and again when it is read.
- **Missions without a pinned workflow** (every mission journaled before step 3) keep `Workflow.BUILT_IN`, as the rule of their time.
- **A point of no return.** Once 1.8.x writes `WorkflowPinned`, rolling back to 1.8.0 quarantines it. Ship the reader one release before the writer, or accept and announce it.
- **Auto-acceptance (decision 3)** is a step setting: off by default, a rule with a cost cap measured in `Usd.micros`, and it never replaces the review.

Amended on 2026-11-01: step 3 is built by [ADR-0050](0050-workflow-pinned-to-a-mission.md). Auto-acceptance moves to the next step: its behaviour is a new path to the workshop's commit, and it comes with workflow schema version 2.

## Reopen if
- A workflow needs a step the interpreter cannot express as a position over the journal: for instance a timer, or a step whose state lives outside the journal.
- Two committees of one mission must deliberate at the same time: today the latest one holds the mission.

## Amendment 2026-11-03 (1.14.0): the director's click applies, and the screen says why a call asks

Seen on the director's journal: after a committee's adoption, Floor42 added `ASK Write` and `ASK Edit` for the
implementation ticket. By the precedence above, a ticket's rule outranks the mission's line for its tool, so the
director's mission-wide `ALLOW Write` (set from the Permissions panel) did not apply to that ticket, and the panel, which
reads the mission-wide line only, never showed the ticket's rules. Q-73 and Q-74 were asked 12 s after the click. The
same rules were journaled with the director as actor although Floor42 wrote them.

Decided with the director:
- **The implementation ticket gets the director's defaults.** At the adoption, `Implementation` reads the company's
  default table as it stands then (the director's line where set, Floor42's built-in one otherwise; the snapshot
  semantics of a new work mission) and records one ticket rule per tool whose default differs from the committee's
  line. With the built-in defaults that is `ASK Write` and `ASK Edit`, as before; a director who set Bash to Auto in the
  defaults gets `ALLOW Bash` on that ticket. The committee phase keeps its read-only table, independent of the defaults.
  A line the director set for the mission before the adoption is the director's explicit choice: it decides on the
  ticket too, and gets no ticket rule (`DefaultPermissions.forImplementation`); only Floor42's lines (the committee
  table) give way to the defaults. Otherwise a click made before the ruling would be overridden by the snapshot and
  Q-73 would come back. Once the mission has a plan, the director makes a line Floor42 set their own by clicking its current value: the same
  `PermissionRuleRemoved` and `PermissionRuleAdded` are recorded again with the director as actor, and the panel says
  so on such rows ("Floor42's setting: click it to make it yours").
- **Who wrote a rule is the journal's actor.** Every rule Floor42 writes (a mission's starting table, a committee's
  table, a ticket's lines, a reviewer's or a helper's denial) is recorded with the system actor; the domain accepts the
  director or Floor42 and still refuses an agent (`DIRECTOR_ONLY`). `PermissionPolicy` keeps the author of each rule
  from its `PermissionRuleAdded` envelope. Journals written before keep their actor: Floor42's old lines read as the
  director's in the journal. The lines recorded before a mission's first plan are always Floor42's (it creates the
  mission and its table in one go): `MissionBook` reads them as Floor42's whatever actor an older journal recorded, so a
  committee table journaled before 1.14.0 does not pass for the director's choice.
- **The director's line replaces the tickets' lines of that tool.** `setToolPermission` by the director removes, in the
  same command, the rules of a ticket for that tool (no agent, no command); the line then decides on every ticket.
  Floor42's own `setToolPermission` leaves them. Rules naming an agent (a reviewer's denial, a helper's temporary
  denial) and rules naming a command stay: they were made for a reason, the panel lists them, and only an explicit
  removal takes them away. Precedence is unchanged (`PermissionPrecedenceTest`). In the panel, choosing the setting
  already selected applies it again when a ticket's line outranks it (the row says so), so the click is never a no-op
  while a ticket ignores it.
- **The ruling says what writing will be.** The implementing option of a new ruling question names what Write and Edit
  will be on the implementation ticket, read from the table and the defaults when it is asked: "writes ask you first"
  (the former label, still used for ask), "writes allowed, per your permissions", "writes refused, per your
  permissions", or "writes per your permissions, tool by tool" when the two differ. Every implementing label starts
  with "Adopt and implement (", which is what the ruling reads, so journaled questions keep their meaning. The label is
  fixed when the question is asked. The question view adds `writesAsked` (what the label says) and `writesNow` (what
  the same tables and defaults give when the screen reads it, `ImplementOption.writesNow`); when they differ, the
  question card says what writing would be now before the director answers. The adoption plans with the tables of the
  moment it runs, never with the label.
- **The panel shows what applies.** Each row of `GET /api/missions/{id}/tools` adds `lineBy` (who recorded the line:
  `DIRECTOR`, `FLOOR42`, or `COMMITTEE_TABLE` for Floor42's line on a committee mission) and `rules`, every ticket,
  agent and command rule of that tool (an agent's rule for the whole category included), each with its author. The
  panel, and the new-mission form when an architecture committee is chosen, say that the committee table is read only,
  independent of the defaults, and that the implementation ticket gets the defaults.
- **An approval question names its cause.** The question view adds `causeCode`, `causeParams` and `causeBy`: the rule
  that makes the call ask, read from the mission's table when the screen reads the question, while it is open. It is
  not journaled (no event, no new field in `QuestionAsked`): the shown cause is evaluated again on the current table each
  time the screen reads the question, so it is the rule that keeps the question asked now, which may differ from the
  one that asked it if the table changed meanwhile. An answered question claims no cause. A question asked because the
  director edited the file names no rule (its context says why).

No new event type and no new field in any journaled form. The domain snapshot gains the authors of the rules, so the
golden masters were recorded again in their own commits; removing `authors=[...]` and `planSubmitted=...` gives the
former files back, plus the new `TicketPermissionTest` cases. The real-journal snapshot SHA-256 changes for the same reason (not measured
here: no local copy of the real journal).

## Amendment 2026-11-05 (pilot, node P1a): a reviewer may execute, never write
A real review said "Bash is denied to me, so I only read the files": a reservation that says nothing about whether the
code works. The director decided that a reviewer may run the producer's approved test commands, and still never
writes. What changed:
- **Bash only, on one ticket, while the sandbox holds.** `ReviewRunner` offers a reviewer `Bash` and records one rule,
  `ALLOW Bash` naming the reviewer and the reviewed ticket (`MissionPermissions.allowReviewShell`), only when the shell
  sandbox is on and works (`Sandbox.enabled() && available()`, ADR-0063) and the review has a worktree (a
  researcher's report folder has nothing to rerun). The rule names an agent, a tool and a ticket, so it outranks the
  reviewer's blanket denial of EXECUTE for Bash alone (`PermissionPrecedenceTest`); PowerShell, Write, Edit,
  NotebookEdit and the network tools stay denied by the same blanket rules. The rule is removed when the session ends.
  The blanket denials at mission creation, in `denyAll` and for helpers are unchanged: a mission journaled before keeps
  them and gains the shell the same way.
- **The work is mounted read-only for that session.** `SessionSpec.readOnlyShell` makes `SessionTally` rewrite the
  command with the mode `ro` (`Sandbox.wrapForShell(.., true)`): `run.sh` then remounts the work and the worktree's git
  directory read-only (the repository's `.git` and the files tying the worktree to it were read-only already). `/tmp`
  is still a tmpfs, so a runner works in a copy (`cp -r . /tmp/work`). The kernel refuses a write, a remount to
  read-write, and a write through a second bind of the work (`SandboxReadOnlyTest`, in the real WSL).
- **No sandbox, no shell, in code.** `SessionTally.decide` refuses Bash for a reviewer's session whenever the sandbox
  is off or does not work, whatever the table says, and `updatedInput` throws rather than hand a reviewer's command
  back unwrapped. The brief says so: it may rerun the approved tests, in a copy, and must not try to change a file.
- **Always offline (director, same day).** A reviewer's shell has no network whatever the sandbox network setting is
  (`Sandbox.tail`: read-only implies `off`); the brief says the network is cut and the worktree's installed
  dependencies are there, read-only.
- **The director's own Bash rules keep priority.** If the director recorded a rule that applies to this reviewer or
  ticket (mission-wide, on the agent, or on the ticket; a command's rule too) and it is DENY, ASK or ASK_ALWAYS on
  Bash, the reviewer gets no shell: no rule is added, no `Bash` offered, no shell paragraph
  (`MissionPermissions.directorRefusesShell`, read from the journal's author of each rule; Floor42's own default Bash
  line is not a refusal).
- **Only our rule is removed, and none outlives a restart.** `allowReviewShell` returns the rule only when it added
  it; an identical rule of the director's stays. At daemon start every ALLOW Bash rule that names an agent and a
  ticket and was recorded by Floor42 is removed (`dropLeftoverReviewShells`).
- **A reviewer's call that cannot be wrapped is refused** (input not an object, empty command), not run as it came.
- Still true: `ShellGuard` and `Confinement` apply to every call. Measured 2026-11-11 on Claude Code 2.1.322
  (ADR-0028): a subagent's Bash call reaches the hook and honours `updatedInput`; a skill's `!command` line never reaches
  it, so with the sandbox on no skill holding one is given to a session.
