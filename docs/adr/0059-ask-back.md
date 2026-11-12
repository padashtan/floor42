# ADR-0059: Ask back before answering an agent's question

Date: 2026-11-04 · Status: Accepted 2026-11-04 (the director's decisions are recorded below; no new event type)

## Context
An agent that calls `ask_director` (ADR-0027) puts a blocking question on the desk and its session waits. The director
had two moves: answer, or let the question time out. When the question was unclear, or the director wanted the agent to
say more first, the only way was to answer with a question in the free text, which the agent then read as the final
answer and acted on.

## Decision
- **Ask back.** On an open question asked by an agent (`askedBy` set), that is not an approval (`action` is null) and not
  one of Floor42's own escalations, whose session still waits, the director may ask back. `Desk.askBack(ctx, question,
  text)` is director only, text 1 to 4000 characters, under the step text rule of ADR-0052 (`StepText.checkMessage`: NFKC,
  no hidden character, the line feed allowed). It emits the existing `QuestionWithdrawn(question,
  RecordedReason.askedBack(text))`: the reason is "asked back by the director: <text>", read back as the phrase code
  `askedBack`. Zero new event types.
- **Same session.** `MissionGovernor.askDirector` ends with the outcome `AskedBack` when the withdrawn question carries an
  ask-back. The MCP result tells the agent that the director has not answered yet, quotes their words as data, and says to
  call `ask_director` again with a revised question (it may disagree) or go on with its judgment and say what it assumed.
  The director's words grant nothing: permissions stay with the hooks and the mission's table.
- **The thread is the causation chain.** The next `ask_director` of that session is a normal `QuestionAsked` whose
  causation id is the withdrawal's event id (the stored `causation_id` column, so a replay rebuilds it). Rounds are the
  number of ask-back withdrawals up the chain (`Question.askedBackRounds`). Each call has its own 30 minute wait. Only a
  withdrawal made by the director counts: an agent's own withdrawal with the same words is not an ask-back.
- **A cap in the domain.** `Desk.MAX_ASK_BACK` is 3 per thread, refused `ASK_BACK_CAP`. Money stays bounded by the
  session's `--max-budget-usd` and the mission budget (ADR-0025). Opting in is each click: the dialog says it is one more
  paid turn of <agent>'s session within its cap. No new setting.
- **A session that no longer waits.** `POST /api/questions/{id}/ask-back` answers 409 `SESSION_GONE` when no live session
  waits on the question (`OfficeService.waitOn`, held by the governor while it waits). The screen then says so and keeps
  the existing paths: answer anyway, give a directive, continue the session (ADR-0038).
- **The route.** `POST /api/questions/{id}/ask-back {text}`. The director's credentials, the exact Origin and
  `X-Floor42-Request: 1` (ADR-0044, applied to every route); a script token needs the scope `all` (the route is not in
  the `missions:write` list).
- **The report.** The end-of-mission report lists each question asked back with its round (`askedBack`).

## Checked in code before building
- `CommandContext.causationId` is written to the envelope and the journal column `causation_id`, and read back: a rebuilt
  company reads the same rounds (`AskBackTest`, `ClaudeCodeRuntimeTest`).
- The governor holds the pending MCP call by waiting in `awaitAnswer` for the question to stop being open; a withdrawn
  question was returned as "no answer". The ask-back is told apart by the withdrawal's recorded reason.
- `RecordedReason.read` only matched exact sentences; it gained the prefix of the ask-back reason.

## Consequences
- The fake Claude Code plays `[fake:askback=Q1||Q2]`: it asks Q1, and asks the next question each time it is asked back.
- A test shows a reply that asks for `Bash git push` is still denied by the mission's rule.
- Measured on 2026-11-04 with the real Claude Code on Haiku (dev daemon, mission M-22): one session with one ask-back
  cost 0.00250478 USD (Claude Code's estimate), 44,696 input and 1,621 output tokens (provided), 4 turns; about
  0.0006 USD per turn, derived by division, not measured per turn. The ask-back reached the agent in the same
  session about 4 s later and the agent went on with its own choice; its later tool requests stayed under the
  mission's rules. The causation link to a re-asked question was not observed in that run (the agent did not ask
  again); the fake-runtime tests cover it. A mission cancelled while its session runs records the cost as
  unavailable, bounded by the session cap.

## Reopen if
Agents are asked back so often that the cap of 3 hurts, or a session is found to take the director's words for an order.
