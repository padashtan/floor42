# ADR-0001: Local web application served by a daemon

Date: 2026-10-20 · Status: Accepted

## Context
Office combines a game scene with dense engineering tools (code editor, diffs, terminals, reports). Options compared: local web app, Electron, Tauri, a Java game engine (libGDX/jMonkeyEngine), JavaFX. The backend is Java (ADR-0002), so Electron and Tauri would ship two runtimes.

## Decision
`office` starts a local daemon and opens the UI in the browser. Missions can be submitted from the terminal (`office "..."`) or from inside the game.

## Consequences
- One runtime to ship (JVM). Mature web tooling for the editor, diff and terminal views (CodeMirror, xterm.js).
- The daemon is a local server and must be protected (ADR-0007).
- Wrapping the UI in a native window (Tauri) later remains cheap.
- A Java game engine would render the scene better but makes the workshop and panels very costly; rejected.

## Reopen if
Users consistently lose the browser tab, or need OS integration a browser cannot provide.
