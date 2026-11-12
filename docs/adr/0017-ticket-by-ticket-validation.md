# ADR-0017: A planner proposes tickets, the director validates them one by one

Date: 2026-10-20 · Status: Accepted; "one ticket at a time" superseded by [ADR-0019](0019-hiring-and-parallel-work.md) (choices made by the director: planner by default and optional, approve a selection, one ticket at a time)

## Context
A mission used to be planned by Office as a single ticket, approved as a whole. The director wants to see the work split on the board and decide ticket by ticket, and agents start only what was validated.

## Decision
- **Planning is a ticket of its own.** With "Let Geoffrey plan" checked (the default in the desk; `"plan": true` in `POST /api/missions`, false when absent), the plan Office proposes holds one ticket, "Plan: <title>", assigned to Geoffrey. Approving it starts the mission: it is the director's consent to spend one planning session, read only. Geoffrey's write, shell and network calls are denied by rule. Unchecked, the mission keeps the former behaviour: one "Deliver" ticket, no planning session.
- **Geoffrey proposes; nothing he proposes runs.** His report ends with blocks of `TICKET`, `WHY`, `DONE WHEN`, `DEPENDS ON` and `SIZE`, at most 8. Office parses them, creates each ticket as **PROPOSED** (a new ticket status), assigned to the producer, and closes the planning ticket. If nothing can be parsed, or more than 8 tickets come back, the director is asked: retry, or leave it.
- **Ticket validation, per mission.** A mission planned this way carries `TicketValidationEnabled`. From then on, any ticket that the director does not create starts as PROPOSED: the planner's, the implementation ticket after a committee, and later agents' proposals. A PROPOSED ticket can be **approved** (it becomes TODO), **edited** (title, description, done criteria) or **dropped** (cancelled). A ticket the director creates is approved by construction. Missions created before this ADR keep their behaviour: the flag is an event, so replays do not change.
- **Approve a selection.** On the board, every proposed ticket is preselected. "Approve selected" approves the checked ones. The others stay proposed and wait, without blocking the rest. The director can approve them later, during the mission. A mission cannot be closed while a ticket is still proposed.
- **One at a time, in order.** The producer takes the first approved ticket whose dependencies are done. Each ticket goes through production, independent review and checks, then the next one starts. One worktree, no merge between agents. Parallel work is left for later.

Mechanics: one thread at a time moves a mission on. An approval while a ticket is under way only adds to the queue; the next approved ticket starts when the current one finishes. A failed session waits for the director instead of being retried.

## Consequences
- Verified with a real planning session on a sandbox repository (Claude Code 2.1.304, about 0.025 USD estimated): four reads, no write, one well-formed ticket for a small objective. Execution of approved tickets is covered end to end with the fake Claude Code (`PlanningApiTest`).
- Every mission planned by Geoffrey costs one more session than before, which the budget and the plan-limit hold (ADR-0016) cover like any other.
- After a committee, "Adopt and implement" now leaves a proposed ticket on the board. The director validates it before Chris starts.

## Reopen if
Plans need re-planning mid-mission, several producers should work in parallel, or the director wants to reorder approved tickets.
