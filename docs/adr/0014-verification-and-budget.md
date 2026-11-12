# ADR-0014: Checks measured by Office, budgets held between sessions

Date: 2026-10-20 · Status: Accepted

## Context
The spec asks that independent verification rest on reproducible evidence (section 12), that every figure say how it was obtained, and that budgets be configurable with a visible consequence (sections 15 and 16). Until now the reviewer only read files, and costs were displayed without any limit.

## Decision
- **Checks are the director's command, run by Office.** The director sets a command per mission (for example `mvn -q test`). Office runs it in the agent's worktree, from the workshop or, if the director opts in, after every delivery and before the review. Office measures the exit code, the duration and the end of the output, and records them (`ChecksRan`). An agent never reports these values, and an agent never chooses the command.
- **Checks are evidence for the review.** The reviewer's brief includes the last run, labelled as measured by Office.
- **Checks never change the work under review.** Untracked files that a run creates, such as build output, are removed afterwards, inside the worktree only, and the output says so. Without this, they would show as pending changes and could be committed when the director accepts.
- **Hard limits.** A configurable timeout (`office.checks.timeout-seconds`, default 900) stops the whole process tree. The environment is stripped of `CLAUDE*` and `OFFICE*` variables. Checks are refused while an agent session of the mission is open.
- **The budget is checked before every session.** The budget is set in USD and compared with the runtime's own cost estimates. When it is reached, Office holds the next step (produce or review) and asks the director (`BUDGET_REACHED`): "Raise the budget by 50%", computed from what was actually spent, or "Stop here". Raising the budget releases the held step. The cost of a session is only known when it ends, so a running session is never cut short; the desk says so.
- **Scorecard.** Each mission shows its results with their basis: measured by Office, provided or estimated by Claude Code, reported by the reviewer agent, or unavailable. The scorecard rewards outcomes, not tokens.

## Consequences
- Verified on 2026-10-20: `mvn -q compile` run from the workshop on the sandbox mission failed with exit 1. Maven 3.6.3's default compiler plugin ignores `maven.compiler.release`. In I4 the reviewer had only *suspected* this risk; it is now measured.
- Checks still run code the agent may have written, on the director's machine and outside any sandbox. The desk warns about it, and automatic runs are opt-in. I6b closes this gap.

## Reopen if
Budgets need to stop a running session (that would require per-turn cost data from the runtime), or checks need several commands with separate verdicts.
