# ADR-0060: Objections at acceptance become work in the same mission

Date: 2026-11-04 · Status: Accepted (the director approved the design and recorded the decisions below, 2026-11-04)

## Context
At the acceptance of a delivery the director sometimes finds something wrong or missing. Today the board says "send a
ticket back from the workshop with a directive": a trip to another panel, one ticket at a time, and nothing for work
that belongs to no existing ticket. ADR-0058 already sends a failed proof back to the producer's own ticket as a
directive (`DirectiveGiven`, then `TicketReopened`), and the workshop does the same for any done ticket. What is
missing is one place, at the moment of acceptance, to say everything that is wrong, and a way to ask for work no ticket
covers, without leaving the mission.

## Decision

### 1. "Object", beside "Accept the delivery"
The board's delivery block offers "Object" next to "Accept the delivery". The director writes 1 to 10 objections, each
1 to 4 000 characters. Each objection targets a done work ticket (by default each worktree's latest done ticket) or is
"new work". A dialog shows how many sessions the round will start before the director confirms (EN and FR).

### 2. One command, one journal transaction: `Missions.object`
`Missions.object(ctx, mission, objections)` checks every objection before the first event is recorded, then records
them all in one command. A refusal records nothing.
- An objection with a target is the existing `Workbench.giveDirective`: `DirectiveGiven` (numbered per ticket) and, as
  the ticket is done, `TicketReopened`. The producer resumes the same ticket in its own worktree. `HeldWork` is not
  involved (it keeps a developer with pending changes off new tickets, a directive resumes the same ticket): this is the
  ADR-0058 "send back" generalised from a failed proof to any objection.
- New work is a work ticket created like `Notebook.turnNoteIntoTicket` does, through `TicketsFacade.create`: kind
  `WORK`, created by the director so approved (no `PROPOSED` even when ticket validation is on), one criterion
  `Done as the director's objection asks: <text>`, no dependency. If the only developer still holds pending changes in
  the workshop, the new ticket waits (`HELD_PATCH`, as any ticket does).
- The new ticket gets the mission's permission table and nothing else. It never inherits the rules of the ticket the
  objection is about (D15 ticket rules stay on their ticket).
- **One cause per round.** Every event of one call carries the causation id `objections-<n>`, `n` being the next round
  of the mission, derived while applying (the highest number seen, plus one). The end-of-mission report groups rounds
  by it (`objections`: number, tickets reopened, tickets created). No event stores the round number.

### 3. Refusals
All 409, none records anything:
- `NOT_AT_ACCEPTANCE`: the mission is not `IN_PROGRESS`, a ticket is still open, or no ticket is done.
- `PROOF_RUNNING` and `CLOSING`: the route runs under the mission's close lock (`DeliverablesHome.closing`), as
  acceptance and cancellation do.
- `AGENT_AT_WORK`: an agent session is running on the mission (the workshop's rule for directives).
- `NO_PRODUCTION_IN_WORKFLOW`: new work on a workflow that produces nothing, or that this build cannot run.
- `INVALID_TRANSITION`: a target that is not a done work ticket. `DECIDED_TICKET`: a committee's ruled decision with no implementation (the directive's own check runs before any event). `UNKNOWN_TICKET` for another mission's ticket.
- 400 `INVALID_ARGUMENT` for no objection, more than 10 or a text over 4 000 characters; `MISSING_TEXT` for a blank one.

### 4. What follows is what already exists
- A reopened ticket is submitted again: its independent review and the mission's checks rerun.
- **The proof goes stale on its own.** A run, a report and a verdict are bound to the recipe and the code they proved
  (ADR-0058, section 4). When the producer's fix changes the code, or new work adds a worktree, the run is stale, the
  verdict counts for nothing, and the acceptance asks again (`UNPROVEN_DELIVERY`, `NOT_RUN` or `NO_VERDICT`). When a
  fix leaves the code and the recipe as they were, the proof stays valid: it proved that code.
- The budget hold is unchanged: under 0.05 USD left, the mission asks for a new cap (`BudgetSet`) before any session.
- Archive (ADR-0054) is unchanged: only a closed mission is archived, and an objection needs an open one.
- **Zero new event types, no new mission or ticket status.** `DirectiveGiven`, `TicketReopened` and `TicketCreated` are
  the events of a round; the cause is the only mark.

### 5. The route
`POST /api/missions/{id}/objections` with `{ "objections": [ { "ticket": "T-1" | null, "text": "..." } ] }`, answering
the mission. Director's credentials and the usual header and origin protections (ADR-0044); the scope is `all`, as for
`/complete`: a `missions:write` token is refused (403), since the objection starts paid sessions on a delivery.

### 6. The screen
The board hint (`board.deliveryHow`) no longer sends the director to the workshop: it points to "Object". The workshop
path stays for a single directive. All texts are `objections.*` codes in `en.ts` and `fr.ts`.

## Options not taken
- A fix ticket per objection: a new ticket for an agent whose earlier work waits in the workshop never starts
  (`HeldWork`); a directive resumes at once (measured for ADR-0058).
- A new event `ObjectionsRaised`: the round is derived from the cause, and the events that matter already exist.
- A mission status "objected": acceptance stays a state derived from the tickets, as ADR-0039 has it.
- A new ticket copying the rules of the ticket objected to: rules are per ticket on purpose (D15); new work asks the
  mission's table.

## Rollback
No event type to keep. A rollback build replays `DirectiveGiven`, `TicketReopened` and `TicketCreated` as before; the
causation ids `objections-<n>` are ignored. The route and the screen may go.

## Reopen when
Directors object in rounds of more than a few items, or want the objection to reach a reviewer first: the dialog then
needs a draft saved between visits. A workflow of a decision-only kind needs objections: `NO_PRODUCTION_IN_WORKFLOW`
would then open to a committee round.
