# ADR-0025: A cost cap on every session

Date: 2026-10-29 · Status: Accepted

## Context
The mission budget holds the next session, not a running one (ADR-0023). Since agents may start subagents, a single session can spend well past what is left. The director asked for a cap, with a panel to manage it.

Claude Code 2.1.312 has `--max-budget-usd` (print mode only). It was measured with a real Haiku session capped at 0.001 USD (recorded, redacted: `transcripts/claude-2.1.312-budget-cap.jsonl`). The session stopped with a `result` of subtype `error_max_budget_usd`, `is_error` true, after its second turn, at 0.0076 USD. The cap is checked after each turn, so a session can go over by one turn.

## Decision
- Every live session runs with `--max-budget-usd`. Its value is the lower of the `SESSION_BUDGET_USD` setting (0.05 to 100 USD, default 2.0) and what is left of the mission's budget, never below 0.05. A mission with nothing left gets no session at all: the budget hold is unchanged.
- The cap is Claude Code's, enforced in its own process: Office does not estimate a running cost itself.
- A session stopped at its cap is recorded as failed with the outcome "stopped at its cost cap", with a note giving the cap and the estimated cost. It then follows the usual path of a failed session: the director is asked what next.
- The Caps panel shows the session cap, and the budget, spending, remainder and resulting cap of the mission on screen. Each amount is labelled as Claude Code's estimate.
- The fake Claude Code refuses to start without `--max-budget-usd`, so a session started without a cap fails every server test.
- Amended 2026-10-31 (1.6.0, the director's rule "paid calls are opt-in and capped"): a real mission's plan is approved only once its cap is decided, a budget or the explicit choice of none, both journaled as `BudgetSet` (the domain refuses `CAP_NEEDED` otherwise). The approval proposes the `MISSION_BUDGET_USD` setting (default 5.0 USD, or `none`). Simulated missions spend nothing and need no cap.

## Consequences
- A session can still spend one turn past its cap. A long turn on Opus can be a large part of a dollar.
- On a subscription plan, the amounts measure use of the plan, not a bill.

## Reopen if
Claude Code changes how it checks the cap (before a turn rather than after), renames the flag or the result subtype, or one turn past the cap turns out to cost too much.
