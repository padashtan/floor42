# ADR-0020: An end-of-mission retrospective, with lessons the director validates

Date: 2026-10-21 · Status: Accepted. The director chose:
- those who worked take part;
- a light model and one short session each;
- the lessons stay in the journal and the director validates them;
- the retrospective runs at the end of every mission, completed or cancelled.

## Context
The director wants the team to learn from each mission. When a mission ends, the agents go back to the meeting table, say what went well and what did not, and draw conclusions so they do better next time. Office's rules still apply:
- nothing estimated is shown as measured;
- an agent never gives itself instructions without the director's approval;
- nothing waits in memory that the journal cannot rebuild.

## Decision
- **When.** A retrospective opens when a real mission ends, whether completed or cancelled, and someone had a session on it. Simulated missions and missions nobody worked on have none.
  - Amended 2026-11-02, after a real run: a cancelled mission's retrospective keeps its facts sheet and runs no session. Every turn is recorded as not heard ("the mission was cancelled"), no lesson is proposed, nothing is spent on work the director abandoned. A retrospective of a cancelled mission still open in an older journal closes the same way on its next start. Cancelling also stops every Claude Code session still running on the mission, and the domain refuses a delivery, a review or a consultation on a closed mission, so a session that outlives the cancellation changes nothing.
- **Who.** The participants are the agents with a session on the mission (`openRetro` checks it), in the order they first worked, without those who have left.
  - The planner chairs if he took part. Otherwise the first reviewer chairs, else whoever worked first.
  - A participant busy on another mission finishes that first: one session per agent at a time (ADR-0019).
- **Facts first.** Office composes the facts from the journal, and every participant gets the same sheet, recorded in `RetroOpened`:
  - the tickets with their verdicts;
  - the sessions, the failed ones, and the cost estimated by Claude Code, per agent;
  - the tool requests and the denials;
  - the questions to the director, by kind;
  - the checks.

  The facts carry no opinion: the opinions come from the participants.
- **Turns.**
  - Each participant speaks once, in a session of its own. It ends with `WENT WELL:` and `WENT BADLY:` lines (`RetroContributed`).
  - Then the chair concludes with `CONCLUSION:` lines and at most five `LESSON: <role or all>: <line>` lines (`RetroConcluded`, then one `LessonProposed` per lesson).
  - A participant whose session fails is recorded as silent, with the reason (`RetroSkipped`).
  - If the chair cannot conclude, Office closes the retrospective with the reason and draws no lesson itself.
- **Sessions.** Each turn is a real session on the light model (`office.retro.model`, `claude-haiku-5-5` by default), with no tool at all.
  - The brief carries everything the agent needs, and the hooks still govern the session. Sessions are recorded on the retrospective's own ticket, the one kind of session allowed on an ended mission.
  - A turn the plan's usage window would likely cut waits for the reset. The retrospective is never urgent, so the director is not asked.
  - A mission whose budget is spent gets no retrospective session, and the record says so.
- **Lessons.**
  - A proposed lesson binds nobody. The director accepts it (possibly reworded), rejects it, or later retires it from the desk's Lessons tab.
  - Only accepted lessons reach later briefs: the developer's, the reviewer's and the planner's, for the role they name or for everyone. A brief carries the latest eight.
  - Everything is journaled and replayable.
- **On screen.** Participants have the status `IN_RETRO` and sit at the meeting table. Work under way elsewhere comes first, so an agent busy elsewhere shows its status there.
  - The meeting room (M) shows who has spoken, what went well and badly, the conclusion, the proposed lessons and the facts sheet.
  - Proposed lessons count in the desk's "To decide".
- **Restart.** A retrospective that a restart cut short goes on from the journal: the next speaker is the first participant without a contribution, then the chair.

## Consequences
- A retrospective costs n + 1 short sessions on a light model, n being the number of participants. It goes through the same journal, hooks and usage readings as any session.
- Lessons accumulate only with the director's consent, and retiring one removes it from later briefs.
- Verified with the fake Claude Code:
  - `RetroApiTest` runs a mission to its end, closes it, and checks that Chris and Darius speak and Darius concludes, on three haiku sessions on the retrospective's ticket.
  - Two lessons are proposed. The accepted one, reworded, appears in the developer's brief and not in the reviewer's; the rejected one appears nowhere.
  - `RetroTest` covers the domain rules: who may take part, one turn each, the lesson cap, the director's validation, and replay.
- Checked on screen with the fake Claude Code: the participants sit at the table with the status "in the retrospective"; the meeting room shows what each one said, the conclusion and the proposed lessons; the desk lists the lessons to validate. Not yet verified with a real Claude Code session.
- Missions that ended before this change get no retrospective: only an ending recorded from now on opens one.

## Reopen if
Lessons pile up beyond what a brief can carry usefully; the director wants a retrospective after each rejected ticket as well; or the facts sheet misses something the participants keep asking for.
