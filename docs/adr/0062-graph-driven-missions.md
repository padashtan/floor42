# ADR-0062: Graph-driven missions

Date: 2026-11-04 · Status: Accepted 2026-11-04 (the director approved the design and both increments; increment 1 ships in
1.16.0 on `feat/graph-mode`; increment 2 is built on `feat/graph-mode-2`, with its one new event type approved)

## Context
Floor42 already has dependency-aware dispatch, per-node validation (mission checks after delivery, review, workflow
checks), local repair by `TicketReopened`, and proof and objections as integration nodes. What a graph-driven mission
lacks: a mode, risk per node, a planner output with a test per node, a graph view, and caps that stop rework early.
Today the planner writes tickets (title, why, criteria, dependencies); the review cap is 2 rejections, the check cap 3;
progress is a list; two tickets run at a time.

## Decisions
1. **Mode, chosen at creation.** "How the team works": Loop (default, today) or Graph (requires the plan step; graph
   without the plan is refused with a code). The mode is a property of the pinned workflow: `"mode": "graph"` in
   workflow schema version 4. Loop workflows are still written as versions 1 to 3, byte for byte. The pin is written
   once: the mode never changes during a mission.
2. **Planner output in graph mode**, per node: `TICKET`, `WHY`, `DONE WHEN`, `DEPENDS ON`, `TEST` (a command and its
   expected result, appended to the node's criteria), `RISK` (`low` or `high`, with a reason), `TIER` (`light` or
   `standard`). At most 8 nodes. In increment 1, `RISK` and `TIER` are shown at the approval and not stored.
3. **Approval.** The existing plan approval (approve a selection, edit, drop), drawn as a graph: nodes, edges from
   `dependsOn`, criteria, tests, risk and tier.
4. **One rework per node.** In graph mode a node gets exactly one rework, counted across the review and every check
   together: the first rejection, of any kind, reopens the ticket as in the loop; the second rejection of that node,
   whatever its kind, asks the director (the existing escalation path). Loop missions keep their caps (2 for the
   review, 3 for one check, each counted apart).
5. **Progress.** A graph view on the board, derived from `TicketCreated.dependsOn`, the status, `ChecksRan` and the
   reviews. No journal change.
6. **Increment 2.** `NodePlanned(ticket, risk, tier, tests)` (a new event type), tier routing
   (light to a cheaper-model developer when free), workflow checks on high-risk nodes only, node tests run in
   the WSL sandbox offline on a throwaway copy (`ChecksRan` trigger "node test"), rerun of only the rejecting check,
   "Extend the graph" (one read-only planner session on the director's click, a delta of at most 8 proposed nodes,
   nothing done reopened), and "to recheck" marks on the done dependents of a reopened node (derived). Built as
   described in "Increment 2: what was built" below.

## Increment 2: what was built
- **`NodePlanned(ticket, risk, tier, tests)`**, the one new event type, in `WorkflowEvent`, with the mission's Origin.
  Written **at the approval**, right after the node's `TicketApproved`, not at the proposal: the director may change a
  node's risk when approving, and a second event for the same ticket stops the replay, so the value is final only at the
  approval. This is also where `WorkflowStepTicketOpened` is written. Until the approval the planner's risk and tier stay
  as the two lines of the proposed ticket's description (the only place the journal can hold them before a
  `NodePlanned`); the approval strips them with a `TicketEdited` and `NodePlanned` is their record. A node approved by a
  development build of increment 1, which has the lines and no `NodePlanned`, is read from them. Replay rules: an unknown
  ticket, a second `NodePlanned` for a ticket, or a mission that is not real stops the replay. A build that does not know
  the type quarantines this row only (`NEWER_VERSION`); `RollbackEventTypesTest` pins exactly one added type against the
  1.15.0 list (`event-types-1.15.0.txt`).
- **Tests.** The planner writes `TEST: <command> => <result>`; the commands are the approved criteria of the form
  `Test: <command> => <result>` at the approval, so an edit of the criterion is the director's edit of the test. A test
  without a command is only a criterion.
- **Tier routing.** A light node goes to the cheapest developer who is free when one costs less than the dearest on the
  team (`ModelTiers`: haiku, sonnet, opus), after the chain rule (a node that builds on another stays with that
  developer). Its review goes to the cheapest free reviewer. The review is never removed, the reviewer is never the
  producer, and no permission changes: only the model differs. No agent is hired by the routing; a light-tier
  developer is one the director hired.
- **Checks on high-risk nodes only.** In graph mode the workflow's checks apply to a node whose risk is high (`Workflow
  .forNode`); a low-risk node completes on its review. Loop missions are unchanged.
- **Node tests.** After the developer delivers and before the node goes to review, Floor42 (not an agent) copies the
  worktree (`ProofSnapshot`), runs each approved command through `ProofShell` (WSL sandbox, offline, same timeout and
  caps as a proof step), and deletes the copy. Each run is a `ChecksRan` with the trigger `node test T-n` (the ticket is
  in the trigger because `ChecksRan` has no ticket field and no second new type is allowed). The sandbox off or not
  working: not run, recorded as a `ChecksRan` with no exit code and the reason, and the node goes to review. A failed
  test (non-zero exit or timeout) is a rejection counted with the review's and the checks' in the single rework: the node
  stays in progress and its developer sees the failure in the next brief; the second rejection of any kind asks the
  director. A node that fails its test is not submitted for review.
- **Rerun only the rejecting check.** In graph mode a resubmission starts at the check that rejected, not at the review
  (`CheckProgress`, the one walk shared by the guard and the brief). The loop still restarts at the review.
- **Extend the graph.** `POST /api/missions/{id}/graph/extend` (director only: scope `all`, a narrower token gets 403
  `SCOPE`): a planning ticket "Extend the graph" assigned to the planner in the same command, one read-only session
  given the graph as it stands (`planner-extend` brief), at most 8 proposed nodes whose `DEPENDS ON` may cite existing
  T-ids. They arrive as proposals to approve. Nothing done is reopened; a citation of a node that is not there refuses the
  whole extension and asks the director instead of looping. The session is paid: the panel says so before the click.
- **To recheck.** A done node one of whose dependencies is open again, or was done again after it, is "to recheck":
  derived (`Rechecks`), shown on the graph and on the ticket, never reopened automatically.
- **Graph view.** Each node shows its test runs (`tests 1/2`) and the mark; the ticket lists its tests and each run.
- A fix on the way: in graph mode the cap counted the rejection being recorded twice for a blocking check, so the first
  rejection of a check asked the director instead of reopening the node. It now counts the earlier ones.

## Options for recording the mode
- **A. A property of the v4 pin** (taken): 0 event types, the mission is bound to its workflow for life (ADR-0050).
- B. A field on `MissionCreated`: a rollback build would refuse to start. Rejected.
- C. A new event `MissionModeChosen`: one more type for a value that never changes. Rejected.
- D. Markers in the criteria: text the code would have to trust. Rejected.

## Readers before writers: an exception
ADR-0052 answer 4 ships a reader one release before its writer. For version 4 the reader and the writer ship together
in 1.16.0, by the director's explicit choice (2026-11-04): increment 1 is meant for launch and a second release for the
reader would delay it. The consequence is accepted: a rollback to 1.15 reads a graph mission's pin as `Unrunnable`
(the mission stays readable, shown, never run). `ReadersBeforeWritersTest` documents this exception for version 4 and
keeps its check for every other version.

## Consequences and limits
- `WorkflowDocument.READS` and `WRITES` are 4. `forJournal` writes the lowest version that holds the workflow.
- A graph mission holds to the planner: the plan step cannot be switched off for it.
- Increment 1 shows risk and tier but does not act on them; increment 2 acts on them.
- One rework per node, review and checks together, is stricter than the loop: the director is asked sooner.

## Proofs
Domain and server tests of the v4 read, write and round trip, the creation refusal, the planner parsing (graph and
loop), the one shared rework against the loop caps, and the approval; golden journals and `WorkflowEquivalenceTest` unchanged for loop
missions; Vitest for the graph layout; one e2e journey (create a graph mission, see the graph at the approval and on the
board).

## Amendment 2026-11-05: dependency folders in the proof copy
The copy a node test or a proof step runs on lacks the files git ignores, so `node_modules` was absent and `npx vitest` tried the network (EAI_AGAIN) while the same tests passed in the developer's session. Every `node_modules` of the source worktree (not inside another one, not a link leading outside it) is now mounted read-only into the copy by the sandbox: nothing is copied, the run stays offline, and a write in it is refused. A node test that fails only because a dependency could not be fetched (EAI_AGAIN, ENOTFOUND, getaddrinfo, "Could not resolve", npm E404, Maven "Could not transfer artifact") is recorded as not run and is not counted as a rejection; the director is asked "dependencies missing (first such line)", as for any test that could not run.
