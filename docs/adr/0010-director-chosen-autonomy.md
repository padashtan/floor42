# ADR-0010: Autonomy is chosen by the director, tool by tool

Date: 2026-10-20 · Status: Accepted

## Context
Asking the director before every sensitive call made agents walk to the director's desk again and again for the same tool. The director wants to decide how autonomous the team is, not to be a permanent validator (spec section 5).

## Decision
- A permission table per mission, one line per governed tool (Read, Glob, Grep, Write, Edit, Bash, WebFetch, WebSearch), four choices:
  - **Auto** (`ALLOW`): the agent acts without asking;
  - **Ask once** (`ASK`): the first call asks; the answer is remembered for the rest of the mission for that agent and tool;
  - **Ask every time** (`ASK_ALWAYS`): one question per call, each answer settles only its call;
  - **Forbidden** (`DENY`).
- For the shell (`Bash`), "ask once" remembers **the exact command**: approving one command never approves the shell. Choosing Auto for Bash shows a full-shell-access warning.
- A request arriving while the same question is open waits for that question instead of opening another.
- Remembered answers are derived from the journal (answered approval questions), not stored separately, so they survive restarts and replays.
- Precedence, most specific first: agent-specific rules, then the table setting of the tool, then command rules, then category rules, then default deny. Only the director can change any of them; each change is a traced event.

## Consequences
- One trip to the director per tool and agent in "ask once" mode.
- Forgetting a remembered answer means switching the tool to another setting; an explicit "forget" action does not exist yet.
- Settings are per mission; company-wide defaults for new missions are not built yet.

## Reopen if
Directors need per-agent tables, or defaults across missions become the common case.
