# ADR-0032: Built-in characters have invented names

Status: Accepted, 2026-10-30 (release 1.0)

## Context
Office's built-in characters were named after well-known people of computing (Ada, Linus, Grace, Margaret, Radia,
Frances, Hedy, Whitfield…), several of them alive, and an idea committee seat was described as a visionary "in the style
of" a living public figure. Before the repository goes public, the director wants no character that evokes a real
person, and none that evokes a political or public figure.

## Decision
- The three characters Office hires on first need: **Galen** plans (was Ada), **Chris** develops (was Linus),
  **Darius** reviews and tests (was Grace). Their agent ids follow (`galen`, `chris`, `darius`).
- The other committee members and the researcher get invented first names: Nora (security), Basile (operations), Ines
  (integration), Lucie (moderator), Clara (lawyer), Malik (altruist), Iris (visionary disruptor), Sofia (sales), Marcel
  (knows nothing about IT), Noe (researcher). The visionary is described by what the seat does, never by a person.
- The hiring form's example name is "Sam".
- **What is not renamed:** a company's journal keeps the agents it hired under their names (an existing company keeps
  its Ada or Linus; Office hires Galen, Chris or Darius when it next needs one), and the recordings of real sessions
  used as test fixtures (`office-server/src/test/resources/transcripts/`) stay as recorded. Past mission reports in
  `docs/PROGRESS.md` keep the names of the agents that did the work.

## Consequences
- In an existing company the old planner stays as an agent with no planning work; the director may dismiss them.
- The online demo's tape was recorded again with the new names (ADR-0031).
