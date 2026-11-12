# ADR-0015: The architecture committee

Date: 2026-10-20 · Status: Accepted

## Context
The spec makes the architecture committee one of Office's main differentiators (section 10). It must confront real technical analyses rather than stage conversations between characters. Disagreements must stay visible, agents must be able to change their minds, a consensus is not a proof, and a critical security objection cannot be removed by a majority vote.

## Decision
- **A decision mission.** When proposing a mission, the director may convene a committee and choose its specialists: architect (always present, chairs), security, senior developer, operations, integration. Each specialist is an agent profile with its own lens, model and effort. The plan has a single "Decide" ticket, assigned to the chair, and a read-only table: reading is free, the shell asks, writing and the network are forbidden.
- **Real sessions, real independence.**
  - Round 1: each member analyses the question alone, in its own Claude Code session. Its brief contains none of the other analyses.
  - Debate round: each member reads every member's latest contribution, objects, answers objections, and keeps, revises or adopts a position.
  - The chair then writes the decision record with the headings of section 10.4. Its brief requires that disagreements nobody settled are not settled by the chair either.
- **Closing lines, read back.** Every contribution ends with `PROPOSAL`, `POSITION` and `CRITICAL OBJECTION`, and the decision record ends with `RECOMMENDATION`. Office stores the full text and the parsed lines. A line the member did not write stays null; nothing is inferred.
- **A critical objection cannot be outvoted.** A critical objection still present in a member's latest contribution is listed in the director's question. The adopt options then read "… despite the critical objection", and the event records that the director chose this explicitly (`overCriticalObjection`).
- **The director rules.** The director has four choices:
  - **Adopt**: the decision ticket is done.
  - **Adopt and implement**: Office plans an implementation ticket for the producer, whose brief carries the decision record. Write and Edit go back to "ask once".
  - **Send back with a constraint**: one more debate round in which every member sees the constraint, then a new record.
  - **Reject**: the decision ticket is cancelled.
- **The scene follows the facts.** Members of a committee in session have the `IN_COMMITTEE` status and sit at the meeting table. The meeting room (key M, or a click on the table) shows the rounds, positions, objections and the decision record, all from the journal.

## Consequences
- Verified on 2026-10-20 with a real committee (architect, security, senior developer) on the sandbox repository. The question was how to make the build reliable, after the failure measured in I8. Seven sessions, about 0.27 USD estimated.
  - The three analyses were independent and converged: pin the compiler plugin, add a wrapper.
  - In the debate, the architect revised her proposal into two steps, and the other two adopted it.
  - The security specialist added refused risks: a wrapper without a checksum, a committed binary jar.
  - The record listed what nobody had verified (no member ran Maven) and kept the one minor disagreement visible (the enforcer).
  - No disagreement was staged.
- The cost grows with the committee: (members × rounds) + 1 sessions. The mission budget (ADR-0014) holds the next session when the limit is reached.

## Reopen if
Committees need more than one debate round without a constraint from the director, members need to run checks themselves, or a committee needs to decide while another mission is producing on the same repository.
