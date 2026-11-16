# ADR-0022: Reinforcing the team: the planner advises, the director answers yes or no

Date: 2026-10-22 · Status: Accepted. The director asked for "a button that hires specialised agents when there are many tickets", which only asks yes or no, after "the boss" has judged with a model whether the plan needs several agents and whether the cost is worth it.

## Context
Hiring was manual (ADR-0019): the director chose a role, a name and a model. With many independent tickets, more developers finish sooner, but the token cost of the work stays about the same and is spent faster (the plan's usage windows fill sooner), each new developer starts cold, and the reviewers must keep up. Whether that is worth it depends on facts Office measures (tickets left, ready, how long a ticket takes, what it costs), and on judgement.

## Decision
- **The button** is on the board ("Reinforce?"), on a real mission in progress. It is put forward when more tickets are ready than there are developers free.
- **The planner judges.** A click asks Geoffrey for a staffing review: one session on the light model (the retrospective's, `RETRO_MODEL`), effort low, **no tool**, from a facts sheet Office composes from the journal: tickets left by status with their dependencies, developers and reviewers, the parallel limit, and per done ticket the time from start to done and the agents' time (measured by Office) and the cost (estimated by Claude Code). She ends with `VERDICT: HIRE|NO`, up to three `HIRE: <name> | <job title>` lines, and optionally `PARALLEL: <1..4>`.
- **The review has its own ticket** ("Staffing: should the team be reinforced?"), the director's, assigned to Geoffrey and started at once; the dispatcher never takes it (`Company.isStaffingTicket`). Her answer is its deliverable and closes it (`StaffingAdvised`), so the board shows what she said.
- **The director decides.** When he names developers, Office asks a `STAFFING` question that blocks nothing: "Hire them" / "Not now" when she advises it, "Not now" / "Hire anyway" when she advises against. The question carries Office's own estimate from the mission's measured averages, labelled as such, or says there is no average yet.
- **Only a yes hires.** Office then hires the developers he named (the first developer's model and effort) and raises `PARALLEL_SESSIONS` to her value if it is higher; both are journaled as the director's decisions. Nothing is hired, and no setting changes, before the yes.

## Consequences
- A staffing review costs one light session (a few cents, estimated). If the session fails, Office closes the review itself and says why; nothing is asked.
- Office's estimate is arithmetic on measured averages (tickets left × average time ÷ sessions at once); it ignores dependencies, which the planner weighs. It is shown as an estimate, never as a promise.
- One review per mission at a time; another may be asked once the previous one is advised.
