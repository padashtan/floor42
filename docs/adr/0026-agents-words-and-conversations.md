# ADR-0026: Agents' words are recorded; a thinking agent talks over a coffee

Date: 2026-10-29 · Status: Accepted

## Context
The director wanted agents who think, not code, to talk with a colleague over a coffee, "like the Sims", with bubbles above them. Office's rule is that the scene shows only what the journal says (ADR-0019). The text an agent writes between two tool calls reached Office's stream, but it was dropped, so it was in no journal and on no screen.

## Decision
- **Words are recorded.** Each text an agent writes in a session is journaled as `AgentRemarked`, cut at 280 characters. The event log shows it too.
- **Thinking is measured.** An agent counts as thinking when its status is WORKING (a session open, no tool running) and the live stream has carried nothing about it for 6 seconds: its model is writing.
- **A conversation.**
  - A thinking agent walks to a chat spot (the kitchen's high table, then the lounge's coffee machine), and an idle colleague joins it.
  - The thinker's bubble carries its last recorded words, or an ellipsis before it has written any.
  - The colleague answers from a fixed local list ("And the tests?"), shown like the jokes, in another style: decoration, never recorded.
  - On the agent's next event, both go back where their status sends them.
- **Not a dialogue between models.** The two agents do not exchange anything: only the thinker's words are real. Real exchanges between agents stay in committees.

## Consequences
- The journal grows by one event per agent message, a few to a few dozen per session.
- With many agents thinking at once, at most two conversations take place; the others think at their desks.

## Reopen if
The director wants real exchanges between two agents outside committees (that would be sessions, so paid), or the remarks make the journal too heavy.
