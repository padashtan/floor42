# ADR-0031: The online demo replays a recording of the simulated mission

Status: Accepted, 2026-10-30 (release 1.0)

## Context
The director wants people to try Office from a link (a LinkedIn post) without installing Java, Docker or Claude Code,
and without any account. Office's rule stands: nothing simulated may pass for real, and no test or demo calls a paid
model.

Options weighed:
- **A hosted daemon** for visitors: one shared company everyone changes, a server to run and secure, and visitors could
  start real agents on it. Rejected.
- **A video only**: watched, not tried. Kept for LinkedIn (R5), not enough on its own.
- **The UI alone, replaying a recording** of a daemon playing the simulated demo mission: a static page, nothing to run
  or secure, the real UI and scene. Chosen.

## Decision
- **Recording.** `tools/demo/record.mjs` starts nothing itself: it is a proxy in front of a fresh daemon (an empty
  data directory, e.g. a new Docker volume, so nothing of the director's is recorded). It starts the live demo mission
  (`POST /api/demo/live`), and after each event of `/ws/events` it reads every GET route the UI uses and stores the
  answers on a tape, with the event, at its time from the start. It answers the demo's approval question as a visitor
  would, so the tape holds both sides of that wait. The tape is a JSON file committed with the site.
- **Replay.** Built with `VITE_DEMO_TAPE`, the UI installs a replay before it starts: `fetch` answers a GET with the
  tape's latest answer for that path at the current point of the replay, and `WebSocket` on `/ws/events` emits the
  tape's events at their recorded pace. The replay stops at the approval question until the visitor answers it in the
  UI; then it goes on. Any other change (hiring, settings, a mission) is refused with a message saying the online demo
  is a recording. A GET the tape does not hold answers 404, as an unknown route would.
- **Labelling.** Every event of the simulated mission is already `SIMULATED` and shown so; the replay adds a ribbon on
  top of the page saying it is a recording of a simulated mission, that no model is called and nothing runs. Times
  shown are the recording's.
- **Hosting.** A GitHub Actions workflow builds the replay UI and publishes it to GitHub Pages once the repository is
  public. The normal build never contains the replay: it is a separate build mode.

## Consequences
- The online demo shows the real UI and scene: a change to the UI is visible there after the next build, but a change
  to the daemon's answers needs a new recording (`make demo-tape`).
- What a visitor sees is exactly what a fresh daemon showed during the recording, labelled as simulated.
- The visitor cannot hire, change settings or start missions online; the README points to Docker for that.
