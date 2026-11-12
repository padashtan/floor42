# ADR-0049: Turns of speech at the director's meeting, and the meeting room in the scene

Date: 2026-11-01 · Status: Accepted · Amends ADR-0047 ("Not in this increment")

## Context
Field report on 1.8.0 (a meeting with eight attendees, its agenda asking them to review the mission's documents and say
what they think): the hold worked, but (1) nothing showed in the conference room, where the director had
right-clicked: `IN_MEETING` sends attendees to the meeting table's six seats in the director's room (eight attendees:
two stayed at their desks), and the emblem lights over that table only; `MeetingCalled` does not record which table
was chosen. (2) The meeting "does not start": by ADR-0047 attendees never speak. (3) Reopened, the panel showed no open
meeting and no close button. A replay of a copy of the director's journal (4,403 rows) gives `GET /api/meetings`
MT-1 open with its eight attendees `IN_MEETING`: the daemon answers right. What the screen does with it: the meeting room
panel (M, a click on either table) knows only committees and retrospectives, so it said "No committee"; the meeting
panel shows the call form whenever its list is empty, a failed read included (the error went to the top banner only),
and it reads the list again only while it already shows a meeting.

What a turn of speech needs, read from the code: a live session is recorded on a ticket (`AgentSessionStarted.ticket`,
`SessionLog.start` checks `WorkEligibility.worksOn`); every tool call becomes an action on that ticket (`ActionLog`);
the mission's spend is the sum of its sessions' estimates (`Verification.spentMicroUsd`); `--resume` (ADR-0038) is
looked for per agent *and ticket* (`SessionLog.latest`, `continuationRequested`, `SessionContinuity.production`,
`DirectorMeetings.latestSession`); `MeetingHold` refuses every call of an attendee; `--max-budget-usd` is Claude Code's
own cap, checked between model turns (ADR-0025: a turn may pass it a little).

## Options for the turns
| Option | What it costs | Verdict |
|---|---|---|
| (a) A ticket of a kind of its own, `TicketKind.MEETING`, one per meeting, created at the first "Ask for their view"; each turn a short session on it | One enum value; the board's listings (`TicketBoard.tickets`, `ticketsView`) leave the kind out, so the 26 readers of ticket lists (board, dispatcher, closure, reports, retrospectives, progress) never see it, while lookups by id (sessions, actions, costs) still find it; ticket commands refuse it (`MEETING_TICKET`); the Resume panel, the recovery's "Retry" and the status rule skip it. Sessions, actions and costs flow as for any session: the reads are journaled, the cost counts against the mission's cap | **Taken** |
| (b) No ticket: turns as meeting events only, run under a governor that journals nothing | No ticket rules to touch; but the turns' tool calls leave no action in the journal (against "every tool call becomes a domain action"), and the mission's spend, the cost overview, usage and the scorecard would each need the meetings added | Rejected: loses the audit trail, four cost readers to change |
| (c) A session with a null ticket | `AgentSessionStarted.ticket` is non-null everywhere (`AgentStatusRule.reviews`, `SessionContinuity`, `ResumeResource`, `Retrospectives`): each would throw | Rejected |
| (d) A turn on the attendee's own work ticket | It would become its latest session there and break `--resume` of the held session (ADR-0047) | Rejected |

## Decision
- **The place (A).** `MeetingPlaced(meeting, CONFERENCE)` follows `MeetingCalled` when the director called it at the
  conference table; none means the meeting table, what every meeting meant before. `MeetingCalled` is unchanged, so
  1.8.0 reads every meeting's call. `MeetingView.place`. The scene (no Vue) gets `setMeetings` with the open meetings'
  place and attendees: an attendee walks to that table's seats (conference: twelve, the attendee limit; meeting table:
  six, then standing places around it), the meeting's emblem lights over the chosen table, and they walk back when it
  closes.
- **Ask for their view (B).** `POST /api/meetings/{id}/views {speakers (ordered), capUsd, question}`. Paid, so opt-in per
  round; the director sets the cap before it starts and sees it. Domain: `MeetingViewsAsked(meeting, ticket, round,
  speakers, capUsd, question)`; the first ask also opens the meeting's own ticket, kind `MEETING`, as `RetroOpened` opens
  the retrospective's (the ticket board applies it first, no `TicketCreated` row). Its id is the meeting's plus
  `.turns` (`MT-1.turns`), outside the `T-n` sequence, and `T-n` counts the work tickets only: a daemon that set the row
  aside never hands that id to another ticket. Refused: closed meeting, mission not in progress (`MISSION_NOT_ACTIVE`),
  simulated mission, a round under way (`ROUND_UNDER_WAY`), a speaker who is not an attendee (`NOT_AN_ATTENDEE`), a cap
  not above what the meeting was charged or over 20 USD (`INVALID_ARGUMENT`).
- **What a turn is charged.** Its session's estimate by Claude Code; when there is none (the session failed, was stopped
  or interrupted), the `--max-budget-usd` it was given (`MeetingTurnTaken.budgetUsd`). The meeting's charge is never
  below what may have been spent, so the cap holds with failed turns too.
- **A turn** (`MeetingVoices`, on the scheduler): speakers in the order the director gave, one at a time. Before each:
  the meeting still open and its mission in progress (else every waiting speaker is recorded not heard, with that
  reason); the mission not waiting for the director (budget reached, plan limit: read from the journal, no question
  asked, else "not heard: the mission is held"); the speaker seated, that is held by the meeting, waited for at most
  `floor42.reservation.patience` (else "not heard: still finishing its work"); at least one cent left under the cap
  (else "not heard: the meeting's cost cap is reached"). Then one `claude -p` session on the meeting's ticket,
  `--max-budget-usd` the smaller of what is left (floored to the cent) and the session cap, tools `Read, Glob, Grep`,
  in the attendee's own worktree of the mission when it has one, else the worktree of the mission's first producer that
  has one, else the mission's repository. Its brief (`briefs/meeting-turn.txt`) holds the mission, the agenda, the
  round's question, the notes and the answers heard so far at this meeting, in order, the oldest left out past 12,000
  characters (said so). Its last text is the answer, kept whole up to 4,000 characters and cut beyond, with a mark: a
  paid answer is never refused. Any failure (the runtime, a domain refusal mid-turn) is recorded as the turn's reason;
  a turn is always recorded, so a round always ends.
- **Guards, by code.** Read-only: `MissionGovernor` built for a turn records any tool other than Read, Glob, Grep as
  refused with the reason "a meeting turn only reads" (the mission's table is not asked), on top of `--tools`.
  Eligibility: `WorkEligibility` lets an agent work on a `MEETING` ticket only while its meeting is open and the agent is
  the round's next speaker: a session or an action outside that is refused by the domain; a turn names a session of
  that speaker on that ticket or none. The hold: a turn runs outside `MeetingHold` (`SessionGate.runTurn`). Resume: the
  turn's session is on the meeting's ticket, never the latest on the work ticket. Status: an action or a session on a
  `MEETING` ticket does not make an attendee `EXECUTING`, `WAITING_FOR_AUTHORIZATION` or `REVIEWING`. Ticket commands,
  directives, committees and dependencies refuse a `MEETING` ticket (`MEETING_TICKET`, `UNKNOWN_DEPENDENCY`); the
  board, the dispatcher, the closure, reports and retrospectives never list it.
- **The rest of the meeting.** A reply is a note (`MeetingNoteGiven`, to one or all); another round is another ask
  (round n + 1, which hears the earlier answers). Closing during a round: the turn's process is stopped and the round's
  thread is waited for (the turn is recorded) before the meeting's reservations go, so the dispatcher can never start the
  attendee's work while its turn runs, and `RunningSessions` (one process per agent) only ever holds the turn then. The
  panel's "finishing" is a work session running, never a turn. After a restart, every unfinished round of any meeting,
  whatever its mission's state, has its waiting speakers recorded not heard ("the daemon stopped during the round"), and
  a turn's session left open is ended as interrupted, without "Retry"; the recovery's "last session" ignores turns.
- **Recheck of 2026-11-02.** The order "decided, then registered" is pinned by a test: the seam a test closes the
  meeting at sits right after the locked decision, so a registration moved out of the lock comes too late for that
  close and the test fails. A stopped process tree is waited for (at most 10 s) before its agent can be let go. A
  turn charged its budget feeds the mission's figures as an upper bound, apart from the estimates: the budget holds
  against both, never added to the estimate nor called measured. A cap must leave at least 0.05 USD above what the
  meeting was charged, or it is refused with that reason.
- **Merged with the session bounds of `fix/interrupted-session-cost-bounds` (2026-11-02).** The mission's spend has
  one upper bound, in `Verification` alone (`spentUsdBound`, `sessionsBounded`): the cap of each session that ended
  without a reported cost, and the budget of each charged turn whose session adds nothing itself (none on record,
  still open, or ended before caps were journaled). A turn's session is a session of the mission, so its cap counts
  once, through the session. The report's `costBound` carries it with the basis `UPPER_BOUND`. At start-up the
  launches left without a session are recorded before the meetings close their unfinished rounds, so a turn's launch
  is that turn's session and not a second amount.
- **Counter-check of 2026-11-01, closed in code.** Close and turn start are one decision under the office's lock: a
  close that comes first leaves no turn to start, and one that comes after finds the speaker registered; the speaker is
  then barred in `RunningSessions` until the round's thread has ended, so a process that registers after the close
  looked is stopped before its brief is written, and the close returns (and the meeting lets its attendees go) only
  once that thread has ended: a timeout proves nothing, so there is none. At start-up the round's next speaker is
  charged what it would have been allowed whether or not its session was journaled (its start cannot be ruled out; the
  figure is an estimate, `BUDGET_CHARGED`); the speakers after it were not reached. A turn is allowed at most what is
  left of the cap, and none starts with less than 0.05 USD left (`MeetingVoices.MIN_TURN_USD`). The mission's report
  (session count, failed sessions, time worked), the scorecard's agent time and an agent's profile (sessions, failures,
  time worked) leave the turns out and keep their cost and tokens; usage per day keeps them (it is spend).
- **Second refutation, closed in code.** A mission that ends during a turn stops the turn and waits for its round
  before the meeting's holds go (`DirectorMeetings.releaseEnded`, as the close does), so no retrospective or other
  work of the speaker starts under it and `RunningSessions` never holds two of its processes. A turn names only a
  session started since its round was asked and named by no other turn (checked by the domain too): a restart in round
  two never charges round one's sessions again, and an interrupted turn is charged what it was given (what was left
  under the cap, or the session cap). The round's thread runs its own round only, and any failure around a turn still
  records the turn. `GET /api/meetings?limit=10` (the panel and the scene) returns the open meetings and the latest
  closed only, built under the office's lock every few seconds.
- **The panel (C).** The meeting panel shows the turns as a conversation (speaker, role, answer, cost estimated, state
  waiting, speaking, done or not heard, with the director's notes in time order), apart from the work log. It reads the
  list every 3 s whatever it shows; a failed read shows the error in the panel and never the call form; the meeting
  room panel and a click on either table, while a meeting is open, lead to it. `feat/sectioned-panel` has no commit on
  top of main: the panel stays self-contained.

## Point of no return
New in the journal, unknown to 1.8.0: the event types `MeetingPlaced`, `MeetingViewsAsked` and `MeetingTurnTaken`.
1.8.0 sets those rows aside as written by a newer version (ADR-0042, a red banner) and reads the rest: the meeting
itself (called, notes, closed) stays. It does not know the meeting's ticket, so the turns' sessions and actions, read by
1.8.0, name a ticket it lacks; no id is reused (`MT-n.turns` is outside its sequence). Going back to 1.8.0 after a
meeting with turns is not supported; a meeting without turns, at either table, stays readable. Downgrade note: a 1.8.0
daemon on such a journal starts, shows the red "written by a newer Floor42 version" banner and lists the rows it set
aside (`GET /api/journal/unreadable`); the director's meeting itself (called, notes, closed) is intact, its turns are
not shown. `MeetingRoomEventsCompatibilityTest` checks that a call keeps the four fields 1.8.0 reads and that an
event type a daemon does not know is set aside as `NEWER_VERSION`, never a failure to start (ADR-0042). No 1.8.0
daemon was run on a 1.9.0 journal for this note.

## Consequences
- A turn costs a short paid session per speaker; eight speakers make eight sessions. The cap bounds the meeting's
  charge; Claude Code checks a session's budget between model calls, so what it really spends can pass the cap by up to
  one model call (ADR-0025). Every amount is Claude Code's estimate, never a measure, and the panel says so.
- The attendees who spoke count as having had a session on the mission (retrospective participants, usage, cost
  overview): they did speak about it, on the mission's money.
- Seen on the way, not changed here: the mission's spend (`Verification.spentMicroUsd`) skips a failed session's cost,
  which Claude Code does not report; and a cancelled mission stops none of its live sessions (only a meeting does),
  so since ADR-0047 a work session cut for a meeting whose mission is then cancelled may overlap that agent's
  retrospective session. Both predate this ADR.
- Tests use the fake Claude Code only (`[fake:meeting-turn-write]`: a turn that tries to write, then reads and answers;
  any brief asking for a view plays `meeting-turn.txt`). No real model was called for this ADR: how long a real turn
  takes, and what it costs, are not measured.
