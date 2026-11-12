# ADR-0045: A guided first mission, simulated on Floor42's own sample repository

Date: 2026-11-01 · Status: Accepted

## Context
A first-time director signs in, then faces the 3D office with no guide. The demo (`DemoScenario`, D then *Start a demo
mission*) plays a whole mission by itself: it approves its own plan, asks nothing before the session and has no
repository, so it shows neither the cap, nor an agent's question, nor the workshop's diff. Teaching on a real mission
would call a paid model and touch one of the director's repositories.

## Options
| Question | Option | What it costs | Verdict |
|---|---|---|---|
| What the tutorial drives | Fake UI states drawn over the screen | A second, untested UI that drifts from the real one | Rejected |
| | A simulated mission the server plays, waiting for the director at each decision | One scenario class and its test; the real panels do the rest | **Chosen** |
| Where the mission works | No repository (like the demo) | No diff in the workshop, no deliverable file | Rejected |
| | A repository of the director's | Writes into their work, even simulated | Rejected |
| | A throwaway sample Floor42 creates in its data folder | A git init and one commit, once | **Chosen** |
| The cap of a simulated mission | None, as for the demo | The step "approve with a cap" cannot be taught | Rejected |
| | The rule of a mission on a repository: a cap decided first | `CAP_NEEDED` now depends on the workspace, not only the origin | **Chosen** |
| Where progress lives | The journal | A screen habit recorded as a company fact | Rejected |
| | localStorage, per browser | Lost with the browser's data; resumable otherwise | **Chosen** |

## Decision
- **A rehearsal** is a simulated mission on the sample repository: `missions().createRehearsal(ctx, title, objective,
  sample)`, director only, refused for a real context (`ORIGIN_MISMATCH`). The demo's rule stays: a simulated mission
  created with `create` names no repository (`SIMULATED_WORKSPACE`).
- **The cap rule**: a plan is approved only with its cap decided when the mission works on a repository, real or a
  rehearsal (`CAP_NEEDED`). The demo, on no repository, approves without one.
- **The sample** (`SampleRepository`): `<data>/tutorial/sample-repository`, one README (the one the recorded session
  read) and one commit, created on the director's click (`POST /api/demo/sample`). Each rehearsal works in its own
  worktree; the sample itself never changes.
- **The scenario** (`PracticeScenario`, `POST /api/demo/practice`): Galen's plan (two tickets, the permission table
  of a read-and-note mission) awaits approval on return; then it waits for the director's approval with its cap, asks a
  blocking question through the agents' ask_director path (`MissionGovernor.askDirector`), replays the recorded session
  `inventory-with-notes` under the mission's governance, and lets reviews close both tickets. `SampleWrites` applies a
  recorded `Write` the director allowed to the rehearsal's worktree, through the workshop's confinement: the workshop
  then shows a real diff on the sample, nothing else. Every event is `SIMULATED`; a rejected plan or a cancelled mission
  stops it.
- **Never dispatched**: Floor42 starts no live session for a simulated mission, whoever asks. The dispatcher picks
  nothing on one, the session gate refuses one (retrospectives and staffing reviews go through it too), a nudge answers
  `SIMULATED_MISSION`. Its spend is 0 at the source, so no cap is ever reached by a recording.
- **Not resumed**: `GET /api/demo/practice/{id}` says PLAYING while the script runs in this daemon, DONE once it reached
  its end, STOPPED otherwise (a restart, a refused plan, patience). The guide reads it and the workshop's decision from
  the daemon; a stopped rehearsal is abandoned on the director's click and a new one starts.
- **The sample is never taken over**: a link, a folder holding files without its own `.git`, a gitfile or a repository
  without a commit is refused (`SAMPLE_UNUSABLE`), never rewritten.
- **Costs of a simulated mission are 0**: the costs panel and the end-of-mission report say "simulated: no model was
  called"; a replayed session records no cost (the recording's estimate belongs to the day it was made), a session
  of a simulated mission shows none, its end line says "simulated: no model was called", and a replay file exports none.
- **The overlay** (`ui/tutorial.ts`, a typed state machine; `components/TutorialOverlay.vue`): thirteen steps, each
  pointing at the real element and waiting for the director's own action (a read step: Next once its element is on
  screen; a state step: the state reached; a visit step: panels opened). It never clicks for the director, is outside
  the 3D scene, keyboard reachable, still under reduced motion. Offered once per browser after sign-in; in the Work menu
  (action `tutorial`), the palette (restart, skip) and the settings.

## Consequences
- A director without Claude Code can still learn the whole flow; the last step lists what going real needs, as the
  daemon reports it now (Claude Code, sandbox, default cap).
- The recorded session is the demo's: its texts are the recording's and the README of the sample matches it on purpose.
- Reopen if the tutorial must teach a committee or a pull request (another recording and another scenario), or if a
  second browser should share the progress (then it becomes a director preference, journaled).
