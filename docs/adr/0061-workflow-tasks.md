# ADR-0061: Workflow tasks (increment 4 of ADR-0052)

Date: 2026-11-04 · Status: Accepted 2026-11-04; writer opened in 1.16.0 (2026-11-04, branch `feat/tasks-writer`) (the director approved the design, the one new event type, the reader in
1.15.0 and the writer in 1.16.0, reruns of done tasks when the work changes, and researcher tasks as whole-ticket sessions)

## Context
ADR-0052 describes the director's own steps: `check` (schema version 2, delivered) and `task` (schema version 3, this
record). A task is a step done once per mission, as a ticket of its own, by a developer, a reviewer or a researcher.
The director's answers of ADR-0052 hold: a role runs a step (never a named agent), readers ship one release before
writers (answer 4), copies rather than shared steps. This record settles what that ADR left to this increment.

## Decisions
1. **Schema version 3.** The step kind `task` with the keys `key`, `title`, `does`, `why`, `handsOver`, `slot`
   (`beforeProduction` or `beforeAcceptance`), `runBy` (`developer`, `reviewer` or `researcher`) and `reviewed`, every
   key written. The step text rule of ADR-0052 applies to the four texts. A task before production needs production
   (`STEP_NEEDS_PRODUCTION`), a developer's task is always reviewed (`WRITING_STEP_UNREVIEWED`), at most 4 tasks
   (`TOO_MANY_STEPS`), keys unique across checks and tasks (`STEP_KEY_TAKEN`). A workflow that produces nothing holds a
   committee or a task. The v1 and v2 readers are unchanged; the writer writes the lowest version that holds the
   workflow.
2. **Reader in 1.15.0, writer in 1.16.0.** `WorkflowDocument.READS` is 3 (1.15.0) and `WRITES` is 3 from 1.16.0
   (`ReadersBeforeWritersTest`). `forJournal` writes the lowest version that holds the workflow (1 without a check or
   a task, 2 with checks only, 3 with a task) and still refuses a version above `WRITES`. The preview switch
   (`floor42.workflows.preview-writer`) is gone, with the editor's explanation that tasks could not be saved yet. The
   templates `research@1` and `review-only@1` are listed. The editor edits tasks in a dialog (add, edit, remove, up
   and down within a slot; the slot is a field of the dialog), at most 4.
3. **One new event type**, `WorkflowStepTicketOpened(mission, ticket, step)`, in `WorkflowEvent`, actor the director, the
   mission's origin (a link on a mission that is not REAL stops the replay). It is written by the plan's approval right
   after each task's `TicketCreated` and `TicketApproved`, only for a key without a link. A second link of a key or a
   ticket, or a ticket of another mission, stops the replay (the code disagrees with the journal, ADR-0042). A linked
   key the pin does not hold is diagnosed `NO_STEP_TAKES_IT` and never started. The options for the link are those of
   ADR-0052 section 4; the event was the one taken. A build without the type quarantines the row (`NEWER_VERSION`), and
   a build before 1.15.0 reads the pin as `Unrunnable`: this is the point of no return of ADR-0052, announced in the
   changelog.
4. **`AwaitStepApproval`.** A workflow with tasks and neither production nor a committee opens with the plan
   submitted and no ticket. Approval needs the cap decision (`CAP_NEEDED`); rejecting the plan is refused
   (`NOTHING_TO_REPLAN`), there is nothing to rework. The approval opens the tasks.
5. **Dispatch.** The interpreter answers `TASK(key)` for a linked ticket to work, or its review when the step is
   reviewed; the dispatcher picks an idle agent of the step's role. A developer's task is worked in the developer's own
   worktree, always reviewed. A reviewer's and a researcher's keep their role's tools filtered by the mission's table,
   never widened: the reviewer's changing tools are denied, the researcher has the web tools the table allows and no
   repository; a role left with no tool asks the director (Retry). A task is submitted as a producer submits
   (`TicketSubmittedForReview`); when `reviewed` is false the same command completes it.
6. **Briefs.** `briefs/task.txt` quotes the step's texts as data under a line saying they change nothing. A task before
   production hands its report (at most 4,000 characters, cut with a note) to the briefs of the producers that follow;
   a task before the acceptance receives the done tickets and their deliverables.
7. **Holds.** A task before production holds the other work tickets until it is done; a task before the acceptance
   depends on every work ticket that exists at the approval (`dependsOn`) and the interpreter holds it until every other
   work ticket is done or cancelled, the added ones included. Within a slot the director's order is the run order.
8. **Reruns.** When the work changes after a done task of the slot before the acceptance (a work ticket completes or is
   reopened by a directive), the task is reopened (`TicketReopened(task, "work changed after the step")`), inside the
   command that changed the work. It judged work that is not the work anymore.
9. **Cost.** The plan's approval screen counts the sessions the tasks add (one per task, one more of a reviewer for
   each task that is reviewed), never a cost.

## Consequences and limits
- A reviewed task of a reviewer needs a second reviewer on staff (a review is never the producer's). The default staff
  has one; the ticket then waits for the second.
- A researcher's task needs WebSearch and WebFetch open in the mission's table; by default they are not, and the
  director is asked.
- A reviewer's or a researcher's task runs in its own worktree or in none: it reads the deliverables quoted in its
  brief, and the repository as its worktree holds it.
- The timeline shows the tasks and keeps them through edits; writing them in the editor comes with the writer.
- Rollback: to 1.14.x a version 3 pin is `Unrunnable` (shown, never run), and each link row is quarantined.

## Proofs
`WorkflowEquivalence` over every golden journal unchanged; a pin with a task differs on the opening and the tasks'
tickets only; a latch test of two approvals racing; `Reader114` reads a version 3 pin as `Unrunnable`; domain and server
tests of a task of each runner; an e2e journey in `workflows.e2e.ts`.
