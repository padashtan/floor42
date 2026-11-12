# ADR-0039: The end of a mission: a report, the next steps in one place, a small ceremony, never a silent end

Date: 2026-10-31 · Status: Accepted

## Context
The director, after a document mission whose two deliverables were PDFs: "a mission is finished but I don't see my PDFs, why?". Read from the
journal (read only) and the disk, what happened:

1. 13:54:59, T-49 (bobi) is reviewed `VALIDATED_WITH_RESERVATIONS` by margaret-2; finding 1 is "main deliverable
   missing: the two PDFs do not exist". bobi's own report ends `VERDICT: PARTIAL`. Every ticket is DONE.
2. The board shows the "delivery" block: tickets done, elapsed time, work time, an estimated cost, a note field and
   "Accept the delivery". The progress panel shows `CLOSE_MISSION` ("Every ticket is done") with the button
   "Open the desk", which leads nowhere near the delivery. Nothing on either says the producer called its work
   partial, or that the reviewer says the main deliverable is missing.
3. 13:58, the director turns the WSL sandbox off and gives a directive ("generate the documents on the machine") on T-48,
   the committee's decision ticket. 1.3 reopened it; nobody works on a decided committee's ticket, so nothing ran.
   Two more directives (14:08, 14:10: "can you deliver? generate the PDFs?") changed nothing. Since 1.4.0 the
   directive goes to the implementation and replay keeps the decision done, so today the mission reads as "every
   ticket done, close it".
4. The PDFs were made at 16:26 outside Floor42 (no event between 14:10 and 17:36) in
   `~/.office/workspaces/M-1-bobi/docs/out/`. That folder is in the worktree's `.gitignore` (generated output, never
   pushed): the workshop lists `git status` without ignored files, the archive wall counts commits, the pull request
   pushes commits. No screen of Floor42 can show them. Nothing in the worktree is committed either
   (`.gitignore`, a notes file and the `docs/` folder wait in the workshop).
5. The mission is still IN_PROGRESS: never closed, no retrospective, no lesson. Had the director accepted the
   delivery, the board would have shown "Mission closed" and the summary line, nothing else; the retrospective at
   the meeting table, the workshop, the pull request and the archive wall are four other panels, each found alone.

The gaps: no report of what was done, ticket by ticket, with how certain each figure is; the next steps scattered or
missing (merging into the project, a follow-up mission); the producer's and reviewer's reservations hidden behind
clicks; outputs that exist only in a worktree (uncommitted or ignored) never mentioned; the end of a mission is not
visible in the office.

## Options
| Option | What it costs | Verdict |
|---|---|---|
| A. A report composed by the server from the journal and git, read on demand, nothing stored; next steps computed by the domain's own checks | One read-only route, two domain check methods extracted from existing commands, one panel | **Taken** |
| B. A report written by a model (the planner summarises) | A paid session per mission end; a summary that may state as done what was not | Rejected: the facts are in the journal; opinions already come from the retrospective |
| C. A new `MissionReported` event recording the report at close | A new event type to replay forever; git state frozen at one moment while worktrees still change | Rejected: the report is a reading, like the archive wall (ADR-0030) |
| D. "Merge into the project" as a Floor42 action (`git merge` in the director's checkout) | Floor42 moving the director's branch, with work of the director's possibly uncommitted there, and a new journaled action; an undo Floor42 cannot guarantee | Rejected for now: the report gives the exact commands to copy, like the pull request preview shows its (ADR-0036, option C) |
| E. Next steps decided by the screen from the mission view | Rules written twice (TypeScript and Java), refusals that drift | Rejected: the server asks the domain |

## Decision
- **The report.** `GET /api/missions/{id}/report` (tag Missions), composed by `MissionReports` from the journal and
  from git on each call; nothing is recorded. It holds:
  - per ticket: status, producer, reviewers, each verdict, rejections, the first line of the last findings and the
    producer's `VERDICT:` line (both quoted as the agents' words), directives, sessions and failed sessions, time,
    estimated cost, the commit accepted in the workshop;
  - totals: tickets done, dropped and open, sessions, failed sessions, elapsed and work time, cost, budget;
  - review quality: reviews by verdict; checks Floor42 ran (command, exit code, duration, when, who asked);
  - the retrospective: its state, conclusions and lessons with their status;
  - the outputs of each worktree: branch, commits beyond the project's branch, files with uncommitted changes and
    files git ignores (50 named, all counted), with the worktree's absolute path.
- **Every figure says its basis.** `MEASURED` (Floor42's clock, Floor42's check runs, git read now), `ESTIMATED`
  (cost: Claude Code's estimate from tokens, not a bill), `REPORTED` (an agent's words: verdicts, findings, the
  producer's own verdict). The screen shows the basis next to each figure; a missing estimate is "not reported",
  never zero.
- **Never a silent end.** Warnings, loud ones first: outputs not committed (path given), outputs ignored by git
  (path given: "in no commit, no pull request, no merge will carry them"), a producer calling its work partial or
  failed, reservations or an unverifiable review on a done ticket, a failed last check, no check at all. They show in
  the report and, shortened, in the board's delivery block.
- **The next steps, in one place,** each through an existing path, each with the refusal it would meet:
  - accept and close: `completeMission`; its refusals come from `Company.checkCompleteMission`, extracted from it;
  - reopen a ticket with a directive: `giveDirective`; refusals from `Company.checkDirective`, extracted from it
    (it also says which ticket a decided committee's directive goes to);
  - open a pull request: the existing block (ADR-0036), one per worktree with accepted work;
  - merge into the project: the commands to copy, per worktree with accepted commits; refused with
    `NOTHING_ACCEPTED` when the branch holds nothing beyond the project's;
  - start a follow-up mission: `POST /api/missions` with a title, an objective and the repository prefilled from
    this one (open tickets, reservations, unverified points, outputs at risk); the director edits before creating;
  - archive: the archive wall, which holds the replay export (ADR-0030, ADR-0035);
  - see the deliverables: a button that asks the App to open the Deliverables panel (built apart; the report never
    shows file contents). The contract kept minimal: the panel emits `deliverables`, the App sets `deliverablesOpen`.
- **A small ceremony.** When `MissionCompleted` arrives for the mission on screen, those who worked on it (producers,
  reviewers, anyone with a session) gather at the meeting table: those already out come up in the elevator. Confetti
  over the table, a cheer, then each goes where its status sends it: its desk when it still has work, the elevator
  otherwise (`presentInOffice`). At most the table's six seats gather. The sequence is a pure function
  (`scene/ceremony.ts`). Reduced motion: no gathering, no confetti, no gesture; at night nothing. It invents
  nothing: it follows a recorded event.
- **Minimal domain change:** two check methods extracted from `completeMission` and `giveDirective`, which call them;
  no new event, no new state.

## Consequences
- The report reads git in every worktree of the mission on each call (status and ignored files, capped): a moment on a
  large repository; the panel loads it on opening and on the director's refresh, never polls.
- Ignored files are listed by `git ls-files --others --ignored --exclude-standard`; a `node_modules` counts in the
  total. The warning says "ignored by git", not "lost".
- "Merge into the project" stays a command to copy. Floor42 still never moves the director's branch.
- Verified with the fake Claude Code (`MissionEndApiTest`): a mission whose producer writes an uncommitted file and
  leaves an ignored output; the report names both with the worktree's path, its basis fields, the close refusal
  before the ticket is done, the reopen and close refusals after closing, the follow-up prefill. Domain rules in
  `MissionEndTest`. Vitest: the report's formatting (`missionReport.test.ts`) and the ceremony's sequence
  (`ceremony.test.ts`).

## Reopen if
The director asks Floor42 to merge into the project itself; the report is too slow on a large repository; a figure is
read as more certain than its basis; or the Deliverables panel takes over the outputs list.
