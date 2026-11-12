# ADR-0016: Plan usage windows and the hold before a session they would cut

Date: 2026-10-20 · Status: Accepted

## Context
The director's Claude plan has two usage windows: five hours and seven days. When one is exhausted, Claude Code stops the session, and the overage setting decides whether it can go on (on the director's account, `overageStatus: rejected`). A session cut midway loses its work in progress. The mission budget (ADR-0014) counts estimated dollars, not these windows. A committee of three members takes seven sessions, so it can drain a window without the budget ever noticing.

## Decision
- **Source.** Claude Code emits `rate_limit_event` in its stream-json output. Each event carries `unifiedWindows.<window>.utilization` (0 to 1) and `resetsAt`, plus a `status` for the window the event is about. This was verified in real transcripts (Claude Code 2.1.304). Office used to ignore these events. It now records each new reading as `PlanUsageObserved`, attached to the session that reported it, and labels it measured by Claude Code. Replayed recordings are ignored, because they describe another day's plan.
- **What a session adds is an estimate, and it is labelled so.** A reading arrives as a session starts, so it shows the usage before that session. What session A used is the next session's first reading minus A's first reading, counted only when both fall in the same window period. The estimate is the median over this model's sessions, or over any model when this one has no history yet. Use outside Office between two readings counts too: this is said wherever the estimate is shown. Readings come in whole percents, so a small session shows as "less than 1%".
- **The hold.** Before every agent session, after the budget check, Office looks for a current window whose reading plus the expected increase reaches the threshold (`office.plan-limit.threshold`, 0.9 by default), or that the runtime already reports as refused. If it finds one, the step is held and the director is asked once: **Wait for the reset** (recommended), **Run anyway**, or **Stop here**.
  - Waiting resumes the step a margin after `resetsAt`. The margin is `office.plan-limit.resume-margin-seconds`, 60 by default.
  - Running anyway skips this check once.
  - Stopping drops the step.
- **Unknown is never zero.** Once a window has reset since the last reading, that reading says nothing about the new window: it is shown as "unknown" and does not count as a risk. With no history, only the reading itself counts.
- **Visible.** The top bar shows both windows (green, amber, red, or grey when unknown). The usage panel (key U) shows the windows, the estimate, the hold threshold, the estimated cost and provided tokens per day and per mission, and a link to the account's own usage page on claude.ai.

## Consequences
- Verified with a real read-only mission (two sessions, about 0.018 USD): two readings per session, 5-hour window at 26% and weekly at 30%, shown in the gauges.
- Verified end to end with the fake Claude Code: a 97% reading holds the review session, the director is asked, and waiting resumes the session after the window resets.
- The scheduled resume survives a restart (D9, paid on 2026-10-20): on startup, Office finds the "Wait for the reset" answer in the journal and waits again.
- Office only sees its own sessions. Usage elsewhere, such as an interactive Claude Code session, appears at the next reading. The threshold leaves room for that.

## Reopen if
Claude Code changes the event's shape (the parser test holds a real sample), the plan exposes usage through an API Office may call, or the per-model estimate proves too noisy to be useful.
