# Changelog

All notable changes to Floor42, one line each. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
The reasons behind a change are in the linked decision records ([`docs/adr/`](docs/adr/README.md)); the working
details in [`docs/PROGRESS.md`](docs/PROGRESS.md). Floor42 was called Office until 1.5.0.

## [Unreleased]

### Added

- Day / night mode: a third button in the help bar draws the scene by the clock (default), by day (as at noon) or by night (lit city, a truly dark office). Decor only: no event, no API call, agents and missions unchanged; the choice is kept in this browser and glides over 1.5 s (at once under reduced motion). The office's night light is darker than before, whatever the choice.
- Before the start (ADR-0070): the new mission form names the folder's build tool, proposes a checks command (the last mission's on the project, else the build tool's default; never run) and lists blockers and warnings from `GET /api/preflight`. Create is disabled on a blocker. A required sandbox that does not work is now refused when the mission is created (`SANDBOX_REQUIRED`), not at its first session. No new event.
- Mission proof dossier (ADR-0071): a Work menu panel and `GET /api/missions/{id}/proof-dossier` give what a mission did (agent identities, refused actions, approvals, costs, checks, deliverables, a summary of changes), every line with its source (journal with event numbers, git, Floor42 state or derived) and every figure with its basis; always masked like the replay export, downloadable as Markdown or JSON. No new event. Token claims (sub, act, scopes) are not in the journal and are listed as a limit.
- Mission title optional in `POST /api/missions` (and in `floor42 "..."`): a blank title is the objective's first line cut on a word, one rule in `MissionTitles`.
- Workshop: "Accept the ticked, discard the rest" accepts the ticked files and discards the others after one confirmation that names both counts.
- Ends shown with their day when not today ("tomorrow 09:10", "12 Nov 14:32"): the top bar's session end and the delegation chip, which is also amber 30 minutes before its end.
- IAM mode: a screen warning 30 minutes before a delegation ends and 10 minutes before an agent token ends (no journal event, nothing renewed).
- OIDC conformance: `make oidc-conformance` runs the director's Keycloak sign-in against the OpenID Foundation conformance suite (relying party tests, prebuilt images pinned by digest; `tools/conformance/`, a manual `OIDC conformance` workflow, [docs/oidc-conformance.md](docs/oidc-conformance.md)).
- Remote today: about one agent in four per local day is drawn working from home (a stable draw per agent and day, decor only: no event, nothing changes in the work). Its chair stays empty under a "Remote" sign, it does not walk to meetings, and a small video-call strip shows its home desk like a webcam (head and shoulders, typing only while a tool executes, a sheet in hand during a review). A click on a tile selects the agent. A switch next to the pet sounds turns the decor off.
- Agent identity (ADR-0064), opt-in IAM mode: each agent is a confidential client in a Keycloak realm (`iam/`: compose file, realm export, mapper extension, README with the flows). The director signs in through Keycloak (Authorization Code with PKCE); at each plan approval the director authenticates again (step-up), which delegates that mission and nothing else, and the consent shown before the click names what is delegated and until when. Each session gets a Keycloak token (Token Exchange, `act` and `floor42.{launch,mission,ticket}` claims, audience `floor42-api`, 8 hours at most) that Floor42 checks on every hook and MCP call: signature, issuer, audience, expiry, agent, mission, ticket, live session, and the calling process (`WRONG_PROCESS` for any other). A background check every 60 seconds asks Keycloak whether the agent and the delegation still stand. Off by default: nothing changes without it.
- One new event type, `AgentTokenIssued(session, agent, mission, ticket, issuer, expiresAt, tokenId)`: the token id and a summary, never the token. A rollback to 1.16 sets those rows aside (`NEWER_VERSION`).
- One more event type, `SessionRiskRaised(session, agent, mission, ticket, level, reasons, windowStart, actions)` (ADR-0066): codes and ids only, written when a real session's risk goes up. A rollback to 1.16 sets those rows aside (`NEWER_VERSION`).
- One more event type, `MissionQuarantined(session, agent, mission, ticket, level, reasons, revoked, suspended, stopped)` (ADR-0066): written once per accepted quarantine, whatever the mission's state and whatever step failed; codes and flags only. A rollback to 1.16 sets those rows aside (`NEWER_VERSION`).
- Identity screens: an Identities panel (clients, decoded claims of the last token, refusals, rotate a secret, revoke an agent or a mission's delegation), a trust chain view per session (director, agent, session, process, resources), and a badge reader at the elevator that lights when a token is issued or refused.
- Identity audit export: the identity events of a mission as JSON lines (who, on whose behalf, token id, decision, reason).
- Token broker: the MCP tool `call_resource` lets an agent call an external HTTP resource without holding a credential. The director declares the resources (base URL, audience, scopes, methods, path prefixes, size cap) and grants them per agent and per mission; each call is one Token Exchange for one audience, 300 seconds at most, and Floor42 makes the request and returns the status, filtered headers and a capped body as untrusted data. The daemon's JVM needs `-Djdk.httpclient.allowRestrictedHeaders=host`.
- Unsafe mode (ADR-0063): the director's red-flagged, journaled exception to the mandatory sandbox; `PUT /api/unsafe-mode` with a reason. One new event type, `UnsafeModeChanged(on, reason)`.

### Changed

- A REAL mission's shell runs only in the WSL sandbox (ADR-0063, pays D16). With no working sandbox the session fails with `SANDBOX_REQUIRED` and nothing is launched, until the director switches unsafe mode on. Simulated missions are unaffected. `floor42.sandbox.mandatory` is false in the `test` and `dev` profiles and in the e2e daemon.
- IAM mode requires the sandbox: with the sandbox off it refuses to start (`IAM_NEEDS_SANDBOX`).
- A REAL mission is always created with its workflow pinned (D24): `missions().create(ctx, title, objective)` makes the simulated mission only.
- The web language catalogues and API types are split by area (D27), so no file nears its size ceiling.
- In IAM mode, a mission approved by a script token, a committee or auto-acceptance has no director to delegate: it waits for a Keycloak approval.
- Dependencies (Docker): node base image 24.22.2-bookworm-slim, digest pinned.
- Dependencies (Maven plugins): spotless-maven-plugin 3.11.0.
- Dependencies (GitHub Actions): setup-java 6.2.0, docker/login-action 4.8.0, softprops/action-gh-release 3.0.4, SHA pinned.
- Dependencies (web): vue 3.5.45, vite 8.3.6, vitest 5.1.0, three and @types/three 0.187.0.

### Fixed

- The shells and the WebSockets of a session that is revoked, signed out or expired (browser or script token) are closed, and so are the sockets of the director's own token when it is replaced; a browser shell therefore ends at the 24 h absolute session limit. A socket is bound to its credential at the upgrade and refused when that credential is no longer live.
- An ID token without `sub` or without `iat`, with several audiences and no `azp`, or with an `aud` that is not a string or an array of strings, is now refused at sign-in and at plan approval (found by the conformance suite: it was accepted).
- A late load in the director's terminal erased the "Prepare" hint (D26).
- The scene's heap is bounded over 600 changes of decade (D23, measured: the slow growth is V8's compiled code, not script objects).
- Tests: SocketSessionsTest no longer fails when a refused socket closes before its close handler is set (the client checks the socket first; a deterministic test forces that order, TerminalApiTest likewise); the seam fields of `SocketSessions` are volatile.

### Security

- The shell of an agent running as the daemon's own user could open the journal for writing and read credentials; a forged row took effect at the next start. REAL missions now refuse to run without the sandbox, and the weakened state is a journaled choice (ADR-0063).
- An agent can no longer replay its session token: in IAM mode a call is accepted only from the process Floor42 launched, and a `curl` from the agent's own shell is refused.
- An agent never receives a credential for an external resource: the broker pins the checked IP address against DNS rebinding, checks SNI and the host name, follows no redirect to another host, and never returns, logs or journals the exchanged token.
- A shell call is decided on its full command, not on its one-line summary: a command-prefix rule never allows a second line the summary flattened, and an approval covers that exact command only (compared on summaries for calls replayed from the journal).
- The replay export masks e-mail addresses as `<email>` (a new kind, `EMAIL`, in its preview); documentation-domain and no-reply addresses stay.
- Masking (D29) also hides query signatures (`sig=`, `X-Amz-Signature=`, `X-Goog-Signature=`), the rest of a `Cookie:` or `Set-Cookie:` line, and secret names written URL-encoded (`access%5Ftoken%3D`, `%22api%5Fkey%22%3A`, a URL encoded inside another).
- The sandbox no longer shows the repository's git config (a remote URL with a token, credential helpers): a stand-in holding only the repository format covers it in every layout, and git gets only allow-listed settings read on the host (`core.filemode`, `core.autocrlf`, `core.ignorecase`, `core.symlinks`, `core.repositoryformatversion`, `core.bare=false`, `extensions.*`) as `GIT_CONFIG_*`.

### Removed

- The `office` launchers (`bin/office`, `bin/office.ps1`, `bin/office-docker`), announced as deprecated in 1.5.0, are gone: use `floor42`, `floor42.ps1` and `floor42-docker`.

## [1.16.0] - 2026-11-04

## [1.15.1] - 2026-11-04

### Fixed

- The "Object" form opens as a framed Objections panel (cards that scroll inside it, counts and Send always in view) instead of spreading inline over the delivery block.

## [1.15.0] - 2026-11-04

### Changed

- A livelier office: the agents who worked on a mission still in progress stay at their desks between two sessions, idle (hands off the keys, eyes on the screen, a sip of coffee now and then), instead of leaving; the mission list now says who worked on each mission (`agents`, read-only). Staff with no mission in progress go to the lounge instead of leaving the office (not when suspended); they leave only when dismissed or at night. The speaker of a committee contribution or a heard meeting turn is marked for a few seconds (lit name tag, a nod). Meeting and committee attendees already walked to their table and back; the canvas now also exposes where each agent is and who speaks (test data). No new event, no server change.
- Workflow checks are saved (ADR-0052): a workflow with a check is written as schema version 2 (version 1 when it has none, byte for byte as before). 1.14.x reads that version, so a rollback to 1.14 keeps those missions running; 1.13 does not run them. The save no longer refuses checks, and the preview switch (`floor42.workflows.preview-writer`) is gone.
- `GET /api/workflows` carries `writesSchema` again: 3 in this release, so the editor knows what the daemon saves.

### Added

- Objections at acceptance: Object beside Accept the delivery, 1 to 10 objections, each sends a done ticket back to its producer in its own worktree or asks for new work in the same mission, in one command and one cause per round (ADR-0060, no new event type; POST /api/missions/{id}/objections, scope `all`).
- Desk: ask an agent back instead of answering its question (at most 3 per thread, one more paid turn of its session, 409 `SESSION_GONE` when it no longer waits), counted in the end-of-mission report ([ADR-0059](docs/adr/0059-ask-back.md)).
- Graph missions, increment 2 (ADR-0062): one new event type, `NodePlanned(ticket, risk, tier, tests)`, written with a node's approval (the director can change a risk there; it is final). A light-tier node goes to a cheaper-model developer and reviewer when one is free; the workflow's checks run on high-risk nodes only; after a node is delivered Floor42 runs its approved `TEST` commands in the WSL sandbox, offline, on a throwaway copy (`ChecksRan`, trigger "node test"), and a failure is a rejection of the node's single rework (sandbox off or not working: not run, said so); a resubmission reruns only the check that rejected; "Extend the graph" (director only, paid, shown before the click) has one read-only planner session propose up to 8 more nodes that may depend on existing ones; a done node whose dependency was reopened is marked "to recheck" (derived, never reopened by itself); the graph and the ticket show the tests of each node.
- Graph missions, increment 1 (ADR-0062): the new mission form offers "How the team works", Loop (default) or Graph (needs the planner; `GRAPH_NEEDS_PLAN` otherwise). The choice is `"mode":"graph"` in the mission's pinned workflow, workflow schema version 4, read and written together in 1.16.0 (the director's exception to readers before writers: a rollback to 1.15 shows a graph mission as not runnable but readable); loop workflows are still written as versions 1 to 3, byte for byte. In graph mode the planner writes TEST, RISK and TIER for each node (the test becomes an acceptance criterion; risk and tier are shown at the approval), the plan's approval and the board draw the task graph, and a node gets one rework in all, review and checks together: its second rejection of any kind asks the director (the loop keeps its caps, 2 and 3 apart). `POST /api/missions` takes `mode`; `WorkflowView` carries `mode`.
- Workflow tasks are saved (ADR-0061): a workflow with a task is written as schema version 3 (version 2 with checks only, version 1 otherwise, byte for byte as before), and the workflow editor has a dialog to add, edit, remove and move up to 4 tasks in the slots before production and before the acceptance. The templates Research and Review only are listed. The preview switch (`floor42.workflows.preview-writer`) is gone. **Point of no return:** a rollback to 1.14.x reads a version 3 pin as not runnable.
- Workflow tasks, read (ADR-0061, increment 4 of ADR-0052): this release reads workflow schema 3 and runs it, and does not write it yet (the writer opens in 1.16.0; the director's own preview switch, `floor42.workflows.preview-writer`, opens it earlier). A task is a step done once per mission as a ticket of its own, before production or before the acceptance, by a developer, a reviewer or a researcher; its texts are data for the session's brief, never a permission. The plan's approval opens each task's ticket and links it to its step with one new event type, `WorkflowStepTicketOpened`. A workflow of tasks only opens on the approval with no ticket. A done task before the acceptance runs again when the work changes. The templates Research and Review only wait for the writer. **Point of no return:** a rollback to 1.14.x reads a version 3 pin as not runnable (shown, never run) and sets each link row aside (`NEWER_VERSION`).
- The findings of an advisory check now reach the producer: they are quoted, as data, in the brief of its later tickets of the mission (ADR-0052, the director's answer 3).

## [1.14.1] - 2026-11-04

### Fixed

- Proof: a step the sandbox cannot run is never run; "passed" is reported only when something passed; leftover proof copies are cleaned at startup and refused at shutdown.
- Sandbox: the wall timer equals the caller's timeout, the WSL probe is bounded, and a Docker daemon that is reachable raises a warning.
- Web: stale loads in the file viewer and in Prove are dropped, Prove follows its report, zoom out starts from the fit, To do re-reads uncommitted work, and a folder can no longer be created twice.
- Three flaky tests fixed at their cause.

### Changed

- About 170 lines of duplicate or dead code removed.

## [1.14.0] - 2026-11-03

### Added

- Workflow checks (ADR-0052, increment 3): the director defines a check in a dialog over the timeline (what it does, why, what it hands over, blocking or advisory), run by a reviewer on each ticket after the review, at most 4, moved up and down within their slot; the template "Document production" ends with an advisory proofreading. A check's session has a reviewer's permissions whatever its text says; a stale verdict is refused; a blocking check that rejects a ticket 3 times asks the director. A step's texts hold only characters the screen shows (no variation selectors, invisible marks or separators). The plan's approval says how many reviews and checks the pin adds. Workflow schema 2 is read by this release and saved from the next one (Save says why); a rollback to 1.13.x shows such a mission without running it. No new event type.
- Workflow library (ADR-0052, increment 2): Floor42's templates (Code change, Light change, Decision) with "Start from it"; each company workflow lists its versions (the saves since its latest removal) with "Restore", which saves an earlier version again as a new one; a mission's workflow line says which saved version its pin copied, or that it follows a copy; "Export to a file" writes `<name>.floor42-workflow.json`; "Import a file" checks the file in full on the daemon (64 KiB, bounded JSON, the document's own rules) and shows every problem by code, or opens the workflow in the editor to read before saving. No journal change: versions are counted from the saves, nothing new is written, and a rollback loses nothing.
- The To do panel, always docked under the event log (foldable, open by default): approvals, questions, alerts and committee rulings that wait for the director, each with a count, its mission, agent and age, and one button to the place that settles it; it follows the event stream. Work left uncommitted shows as an alert for a mission waiting for acceptance, or "cannot tell" when the check fails.
- A pin button next to the close cross of every panel: a pinned panel stays open and live while others open, Escape leaves it alone, several can be pinned at once.
- The file viewer previews media (ADR-0051, amended): an image fits the panel, with actual size and zoom steps; a PDF opens in the browser's own viewer; any other file, one past the caps, or a `.pdf` whose bytes are no PDF gets a "No preview" card with Download. The director's own pending files on the desk (Mission tab) open in the same viewer: `GET /api/missions/{id}/files/entry` and `GET .../files/file`, pending paths only, scope `all` only.
- Prove the delivery (ADR-0058): the producer leaves a `PROVE.md` recipe of steps; Floor42 runs the steps the sandbox can on a throwaway copy of the worktree, network cut, and the director reports the others; each step and the verdict are journaled, a send-back reopens the producer's ticket with the evidence, and an acceptance asks first when the delivery is unproven. Four new event types (`ProofRan`, `ProofStepWitnessed`, `ProofVerdictGiven`, `UnprovenDeliveryAccepted`): a journal holding them is not fully readable by 1.13.0 or older.
- Docker as it is in the sandbox: the tools probe measures whether the sandbox reaches the Docker daemon, and a plan whose project needs Docker asks the director with that measure (the director does the step, or the mission goes on without Docker); opening the sandbox to Docker is only proposed (ADR-0056). The folder browser has a "New folder" button.

### Changed

- A workflow import may now weigh six times the file's 64 KiB cap on the wire, so a file of control characters at the cap reaches Floor42's own checks.
- A viewed file's type comes from its bytes for every route (an HTML page named `.png` is refused, never sent as a page nor an image); images, SVG included, get their own narrow Content-Security-Policy. A checked file is opened once and sent from that open file, cut at the size checked.
- Markdown files keep `text/markdown; charset=UTF-8`, shown or downloaded; every query parameter of the API carries an OpenAPI description; why backups are off is a code the screen translates; a mission held on a patch in the workshop reads as the director's turn in the progress panel, with a button to the workshop.
- Dead code removed (unused helpers, a CSS rule, i18n keys and test-only web helpers); the server tests share their API and question helpers.

### Fixed

- After "I install what is missing", a restart no longer holds and measures again a mission whose new measure was already taken; a measure that cannot be recorded asks the question again, and the progress panel names the wait for the measure (debt D25).
- A permission the director sets for a whole mission now applies to every ticket of it. After a committee's adoption, the implementation ticket kept Floor42's "ask" for Write and Edit, which outranked the director's later "Auto" and stayed invisible in the Permissions panel: the director was still asked. The director's line for a tool now replaces the tickets' lines of that tool; rules naming an agent (a reviewer's denial) stay.
- The implementation ticket of an adopted decision gets the director's default permissions, as they stand at the adoption, instead of a fixed table; the committee itself keeps its read-only table, and the screen says so where the committee is chosen and in the mission's permissions.
- The Permissions panel lists, per tool, every rule beyond the mission's line (a ticket's, an agent's, a command's) with who made it, and an approval question names the rule that made it ask.
- Rules Floor42 writes are journaled as Floor42's, not the director's.
- A permission the director sets on a committee mission before the ruling is kept for the implementation ticket; the default table no longer overrides it. In the Permissions panel, clicking the setting already chosen applies it again when a ticket's own line outranks it.
- The ruling's "Adopt and implement" option says what writing will be (asked, allowed or refused, per your permissions) instead of always "writes ask you first"; if the permissions change before the answer, the question says what writing would be now.
- Clicking the current value of a permission Floor42 set (a committee's read-only line, for instance) makes it the director's own choice, which the implementation ticket then keeps.
- A HEAD on a file route no longer leaves the file open: both routes answer it with the headers only.
- A viewed or downloaded file no longer goes out with both `Content-Length` and a chunked body, which strict HTTP clients refused.
- Flaky tests fixed at their cause (a workshop test committing while a review still read the worktree, the checklist and library journeys, the timeout of the scene tests); one journey failure whose cause is not found is recorded as debt D26.

### Security

- The WSL shell sandbox could be left: a command could unmount its own masks over `/mnt` and `/run` and start a Windows program on the director's machine through WSL's bridge. Commands now run one user namespace deeper, so the kernel locks the masks; the sandbox gets its own empty binfmt_misc; and each command is bounded in wall time (its whole process tree is killed), processes and file size ([ADR-0028](docs/adr/0028-shell-sandbox-wsl.md), amended).
- A sandboxed command could reach the Windows host's file server over AF_VSOCK and read or write `C:`: a seccomp filter now refuses vsock sockets and io_uring, and the sandbox needs `python3` in the distribution to install it ([ADR-0028](docs/adr/0028-shell-sandbox-wsl.md)).
- A sandboxed command could repoint its worktree's git links at a repository it wrote, so the next git on Windows ran a program: those files are now read-only in the sandbox, and Floor42's own git turns off fsmonitor, the ext protocol and submodule recursion, and refuses a worktree whose links changed ([ADR-0028](docs/adr/0028-shell-sandbox-wsl.md)).
- A sandboxed command inherited from `wsl.exe` the master side of the director's login terminal and could type into it: the sandbox now starts with no file descriptor but stdin, stdout and stderr ([ADR-0028](docs/adr/0028-shell-sandbox-wsl.md)).
- A sandboxed command could freeze the sandbox's first process with ptrace and so escape the wall time: ptrace and the calls that read another process are refused ([ADR-0028](docs/adr/0028-shell-sandbox-wsl.md)).
- A planted nested repository with a `.gitmodules` asking `ignore = none` made Floor42's own `git status` run that repository's filter: Floor42 passes `--ignore-submodules=all` to status and diff, and refuses `git add` when a submodule holds a repository it did not make ([ADR-0028](docs/adr/0028-shell-sandbox-wsl.md)).
- A sandboxed command could open the VM's devices by path, the director's login terminals among them: the sandbox now has its own `/dev` (a few harmless devices, its own pseudo-terminals) and no controlling terminal ([ADR-0028](docs/adr/0028-shell-sandbox-wsl.md)).
- A committed `.lfsconfig` made Floor42's `git worktree add` and `git reset --hard` call the server it named, and could make them fail: Floor42's git no longer downloads LFS files; worktrees hold the pointers ([ADR-0028](docs/adr/0028-shell-sandbox-wsl.md)).
- Floor42 no longer stages an untracked folder that holds a repository, and refuses to commit while a cherry-pick, revert, merge or rebase state left in the worktree's git directory would lend the commit another author ([ADR-0028](docs/adr/0028-shell-sandbox-wsl.md)).
- A sandboxed command could write the VM's cgroups, and so freeze or kill the director's WSL sessions: the sandbox now has its own cgroup namespace and an empty read-only `/sys` ([ADR-0028](docs/adr/0028-shell-sandbox-wsl.md)).
- Variables forwarded by wsl.exe (an API key listed in WSLENV, the desktop's DISPLAY and the like) reached sandboxed commands: the sandbox now keeps an allow-list (locale, terminal, and what it sets itself), and Floor42 starts wsl.exe without WSLENV or its secrets ([ADR-0028](docs/adr/0028-shell-sandbox-wsl.md)).
- The sandbox has its own IPC and UTS namespaces (a command could read or remove the director's SysV queues and shared memory), and `/var/tmp` is no longer written to the distribution's disk ([ADR-0028](docs/adr/0028-shell-sandbox-wsl.md)).
- Agent text with `` $` `` in a wrapped list item could add attributes to the rendered Markdown; every argument of the agent's sandboxed shell line is now quoted ([ADR-0028](docs/adr/0028-shell-sandbox-wsl.md)).
- A sandboxed command could move the worktree onto `main`, so an accepted change landed there: HEAD is read-only in the sandbox and the Workshop commits only on the mission's branch ([ADR-0028](docs/adr/0028-shell-sandbox-wsl.md)).

## [1.13.0] - 2026-11-03

### Added

- The Library: a gallery panel (bar, menu entry and key L) with one illustrated book per known project, its cover drawn from the project's name in the colours of the decade; a click starts the new-mission form on that project, and one card opens the folder browser. A bookcase in the open space of the office holds one spine per project in the same colours; clicking it opens the Library.
- Mission archives (ADR-0054): a completed or abandoned mission can be archived from the desk or the board, after a confirmation; it leaves the board, the Work menu, the palette and the mission lists, stays in the journal and keeps its worktrees. The desk's new Archives tab lists archived missions with Restore, and "Archive all closed missions" cleans the board in one go after a confirmation giving the count. Refused while a session of the mission runs or its retrospective is under way, with the reason in the screen's language. Restoring starts nothing: a closed mission without a retrospective offers "Convene the retrospective", whose paid sessions start only on that click (or says why it cannot be convened). Two new event types, `MissionArchived` and `MissionUnarchived`: a journal holding them is not fully readable by 1.12.0 or older.
- Uncommitted deliverables at acceptance (ADR-0055): before a mission is accepted, Floor42 checks its worktrees and lists the files that are not committed, or says it cannot tell (git failed, folder gone, not a mission worktree), never "clean" by default; accepting anyway needs the director's yes on exactly that list and is recorded. Workshop commits and edits are refused (409 `CLOSING`) while the mission is being accepted. The copy of the deliverables explains what "left out" means.

### Changed

- The Sandbox, Skills and Meeting room panels, and the end-of-mission report, follow the Settings model: a section list on the left, the content grouped in readable blocks on the right. Event lists read newest first (the committee debate, the report's checks and a ticket's reviews). The retrospective's close button waits for any other write of the meeting room again.
- Web sources split by responsibility (debt D22): App.vue, BoardPanel, MeetingRoom, TopBar, PluginInstall and ProjectPicker are each at most 300 lines, with no change of behaviour; a Vitest now fails when a source file passes 300 lines.
- `GET /api/missions` now leaves archived missions out; `GET /api/missions?archived=true` lists only them. Recent projects no longer name an archived mission (a project whose missions are all archived has no last mission); costs and usage keep archived missions, marked "archived".
- Reviewer calibration (ADR-0053): a reservation must cite one of the ticket's acceptance criteria (numbered C1, C2... in the reviewer's brief) and say what to change; anything else is recorded as a note that blocks nothing. The reviewer's verdict is never rewritten: the end-of-mission report counts "with reservations" only for reviews with a kept reservation (or reservations it could not read). The board and the deliverables panel show reservations and notes apart. Reviews recorded before keep their meaning.

## [1.12.0] - 2026-11-02

### Added

- "Prepare in my console": a skill or an agent of the catalogue is typed into the director's own Claude Code terminal (`/name ` or `Use the <name> agent to `), never with Enter, only after the director's click (ADR-0041, amended). Floor42 types only on Claude Code's empty input box, read from the terminal's screen (its dimmed suggestion counts as empty); on any question, dialog or unrecognised screen nothing is typed and the command goes to the clipboard. Names pass an allow-list; names that start with a digit or match a built-in command are refused; one preparation at a time per terminal.
- Workflows editor (ADR-0052, increment 1): the library on the left, the selected workflow as a timeline on the right, read first and edited in place; fixed steps show a lock and why; problems sit under their row; Save shows the first problem; leaving unsaved changes asks first; the panes stack on narrow screens.
- ADR-0052, Workflows v2 (accepted): steps the director defines, a library of templates, import and export, delivered in four increments.
- README: a real Hello World 42 mission, recorded on 1.11.0.

### Fixed

- The director's terminal no longer inherits Claude Code's child-session markers (`CLAUDE_CODE_CHILD_SESSION` and the like) from the daemon's environment, which turned its transcripts off.
- Seven compiled `.js` files committed by mistake in `floor42-web/src/i18n` (1.11.0) are removed and ignored: they shadowed the TypeScript catalogues, so a later text would not have reached the page. 1.11.0's screens were not affected (the files matched their sources).

## [1.11.0] - 2026-11-02

### Added

- A plan that needs a tool the agent sandbox lacks (a browser to render a PDF, pdftotext, pandoc, LibreOffice) raises one blocking question when the plan is recorded: install it yourself (the command to copy, then the sandbox is measured again), do that step yourself, drop the ticket, or go on anyway. The mission is held until the question is answered and, after "I install it", until the new measure. Floor42 never runs the install. Planner and developer briefs list the tools the sandbox has and lacks.
- "What is missing on this machine": a checklist of Claude Code, git, WSL 2, the sandbox distribution, its isolation and the essential tools inside it, each with what it is for and the command to copy. It opens once at the first start and stays in Settings (`GET /api/sandbox/checklist`). `floor42 doctor` (and `make doctor`) prints the same list from a terminal, even with the daemon stopped; exit code 0 when every required item is present, 1 otherwise.
- The office cat and dog sprint now and then, meow and bark in a bubble, keep idle agents company (the cat rubs or sits by one, the dog brings a ball) and sleep in a basket of their own. An optional synthesised sound, off by default, is switched on in the help strip and kept in the browser; it starts only after a click or a key press.
- README: requirements and known limitations, beta status.

### Changed

- The cat and dog are scaled to the agents (the cat at knee height, the dog a little taller), with detailed low-poly bodies, a walk cycle and tails; they walk a finer grid of their own, keep clear of desks, walls, agents, each other and the robot, and step aside from an agent's route.
- A review of a ticket whose environment step the director took on goes to the director instead of reopening the ticket.
- Faster test loop: four test JVMs, one data folder per boot, the scene's Vitest split, two e2e workers; `make quick` and `make gate`.

## [1.10.0] - 2026-11-02

### Added

- `POST /api/missions` accepts an `Idempotency-Key` header: the same request sent twice with one key creates one mission, the second answer is 200 with it; a key reused for another mission is 409 `IDEMPOTENCY_KEY_REUSED`. Keys are kept in memory for 10 minutes from the end of the creation. The new mission form and the follow-up form send one key per content: sent again, the same content reuses it; edited, it gets a new one.
- A small robot wanders the office; a click on it opens the director's terminal (the same action as the menu entry). A
  cat and a dog wander too: the cat sometimes naps on a desk, the dog follows an agent for a while and lies down; on
  hover or click they show their name. All three walk the nav grid (around desks, walls and agents), follow the decade's
  palette, are freed with the scene, and stand still under reduced motion. The terminal stays reachable by the menu, the
  palette and its key: the robot is only a mouse shortcut.
- Deliverables land in the project ([ADR-0051](docs/adr/0051-deliverables-destination-and-file-viewer.md)): the new mission form asks for a deliverables folder (`deliverables` inside the project by default, or an existing absolute folder; blank for none). When you accept the delivery, the mission's documents (Markdown, text, PDF, raster images, office files, each checked by its content; never SVG, HTML, code, tests, configuration, scripts, agent instructions or anything under an agents, commands, skills or hooks folder) are copied into `<folder>/<mission>/`. A file already there is never replaced: the same bytes are left as they are, other bytes are written beside them as `name (2).ext`, and the board, the deliverables panel and the event log say which. The folder is checked by code on its real path, links and junctions followed: never under a tool's folder (`.claude`, `.git`, `.github`, `.vscode`, `.idea`, `node_modules`) anywhere on disk, never a disk root, the home folder, a system folder, a network share or Floor42's data. Only your browser or a token of scope `all` may choose a folder other than the default. Missions created before keep the old behaviour.
- A file viewer panel ([ADR-0051](docs/adr/0051-deliverables-destination-and-file-viewer.md)): a delivered file, or a pending change of the workshop, opens in a main panel of its own. Text and Markdown are shown escaped, images in an `<img>`, PDFs in the browser's viewer, and Download stays. Closing it, or pressing Escape, brings back the list it came from, as it was, with the focus on the View button that opened it. When a file is too big, a link, deleted or of a kind Floor42 does not show, the panel says why and offers Download.
- SVG files are shown as images (`image/svg+xml`, only after their bytes are checked for `<svg`, under `sandbox; default-src 'none'`, served as an attachment so they never open as a page). Before this, they were shown as text.
- Blocked by environment: a developer's report may end with `BLOCKED BY ENVIRONMENT: <what is missing>; step: <the step>`. A rejected or unverifiable review of such a report keeps the ticket in review on one question to the director that names what is missing, with five answers: I do the step myself, I allow an exception, I install what is missing, Accept as is, Send back to work. No answer grants a permission. Ticket and report views carry `environmentBlocker`, and a directive on such a ticket asks for a confirmation first.
- Every developer and reviewer brief has an "Execution environment" section: the host system, the sandbox and its network, the system the sandbox probe measured, which path form the file tools and Bash each take, and where the working directory is in the sandbox.

### Changed

- The planner's brief names the team by role, and independent planned tickets go to the developers in turn; a chain of dependent tickets stays with one developer. A developer hired for a mission now gets work from its plan.
- A workshop action refused because a session is still open (`AGENT_AT_WORK`) names whose session and on which ticket.
- One in-app confirmation (`ConfirmDialog.vue`, `state/confirm.ts`) replaces the browser's `prompt()` and `confirm()`: themed, translated, keyboard reachable, a reason still required where a reason was asked (dismiss, suspend, drop a ticket). Stopping a terminal, ending or revoking a session and signing out of this browser's session now ask first.
- On a window of 760 px or less the top bar wraps instead of leaving the Team and System menus off screen, the crew follows its height, no panel is wider than the window, its close button sits inside the frame, the keyboard help is hidden and the event log moves to the bottom and gives way to an open panel. The settings sections wrap instead of scrolling sideways.
- The context menu follows the ARIA menu pattern (menuitem buttons, arrows, Home, End, Escape, focus returned); Dismiss is marked as destructive. A panel that opens takes the focus; Escape closes the away summary and the tutorial offer. Signed out, only the sign-in card (modal) is mounted.
- The workshop opens with its help folded, its footer is capped so the file list stays reachable, and its body is opaque. The empty meeting room is a small panel with a "Call a director's meeting" button.
- Muted text and the dim greys reach a 4.5:1 contrast, an agent's initial picks a dark or white ink, no text is under 12 px, buttons, selects and inputs are at least 24 px high, and every field has an accessible name (45 fields named only by a placeholder, the file picker, the directive ticket, the skills filters, the budget).
- English no longer shows on French screens: the journal keeps the approval scope sentence in English as before, the question view adds `contextCode` and `contextParams` read from it (`ApprovalScope`, the four scopes the server writes), and the screen writes it from them. The default notes and reasons the interface records (closing, abandoning, rejecting, dismissing, suspending, dropping) are always sent in English and shown in the screen's language. The bar's mission badge shows the translated origin, "démon" everywhere. The plugin panel keeps the operating system's error in a tooltip. The costs tables have column headers.
- The default director profile is a neutral sample ("A technical lead who prefers small, reviewed changes and decisions backed by measured facts."), and so is the one in the online demo recording.
- A panel opened from the keyboard shows a focus ring on its frame. A panel resized on a wide screen keeps within a narrower window.

### Fixed

- A file tool's path written in another case than the worktree on Windows (`c:/users/...`) kept its absolute form in the call's summary, so a permission rule naming the relative path did not apply to it. The summary now drops the worktree whatever the case on Windows (still case-sensitive elsewhere).
- A `/media/work/...` or `/mnt/c/...` path given to a file tool is refused with the path form the file tools take, instead of a bare "outside the worktree".

- Accepting a workshop's work no longer commits caches the agent did not mean to deliver (`__pycache__`, `*.pyc`, `node_modules`, `.pytest_cache` and the like): they stay in the worktree and the workshop lists them apart (`leftOut`). A kind the repository already tracks is committed as any other file; what `.gitignore` ignores never shows. Kinds that may be deliverables (`.venv`, `*.class`) are left to `.gitignore`.
- A cost bound is null when nothing is bounded, never 0.0: `/api/costs` (`usdBound`) and the mission view (`spentUsdBound`) now agree with the report (`costBound`), and the API contract says `number` or `null`.
- After a denied call, the director answering the agent's own question may tick "Ask me again for <tool>: <call>": the answer then carries `askAgain`, the id of that exact remembered refusal (`QuestionView.askAgain` lists the ones that may be lifted), and only that refusal is lifted. The next such call asks the director again; nothing is allowed by it, and free text never lifts anything. Lifting needs the director's own scope, as `/forget` does: a `missions:write` token gets 403 `SCOPE`. The journal says "refusal lifted by the director" (`ev.ApprovalForgotten.askedAgain`).
- The hook's reason says plainly what the director decided: "director denied it (Q-4)", "director already allowed every Write request from chris (Q-1)".
- An approval question in the event stream reads the stage it was asked in (REQUESTED), not the action's later stage; an event that moves no action has no stage: `IncidentRaised` now serves `stage` null instead of its action's live stage (EXECUTED, for instance), which that event never set. Nothing journaled changes.
- A ticket held by an open question of the director's (rejected twice, for instance) says so on the board, "waiting for your answer (Q-5)" (`TicketView.waitingFor`); the answer alone sends the developer back to work.
- `GET /api/missions/{id}/workshop/{agent}/diff` without `path` returns every pending change as one diff instead of "path is required", bounded (500 files, 2,000,000 characters, no file above 512 KiB); `omitted` counts the changes left out.
- A name already given to an agent at work is refused at hiring, compared after NFKC, zero-width characters removed, spaces collapsed and case folded (409 `NAME_TAKEN`); the hire form says so before sending. A former agent's name may be given again, with a numbered identifier.
- A model outside the catalogue is refused at hiring and at reconfiguring (400 `UNKNOWN_MODEL`); an agent keeps a model it already had. The panels read one list (`ui/models.ts`), held equal to the daemon's by a test. The banner says the new refusal codes in English and French.
- A mission title is at most 200 characters and its objective 20,000 (400 `TITLE_TOO_LONG`, `OBJECTIVE_TOO_LONG`); the forms hold the same limits.
- Cancelling a mission stops every Claude Code session still running on it, and one launched in the same instant. A delivery, a review or a consultation arriving after the cancellation is refused (`MISSION_CLOSED`) and changes nothing.
- The retrospective of a cancelled mission keeps its facts sheet and runs no session: every turn is recorded as not heard, no lesson is proposed ([ADR-0020](docs/adr/0020-end-of-mission-retrospective.md), amended).
- A workshop accept never commits while anyone works on the mission: under the dispatch lock, where every task is chosen, it refuses while a task of the mission runs, then holds the workspace's agent and the mission until the commit is recorded. Git runs outside the office's lock. A commit that fails unstages; a record that fails moves the branch back, the director's files kept in the worktree.
- Why Floor42 allowed or denied a call ("director already allowed this exact command from chris (Q-2)", a rule, default deny...; the "director answered" sentences of older journals too), the denial's reason, a permission rule, and the reasons and incident descriptions Floor42 writes itself are shown in the screen's language, in the event log, the execution card and the desk. The journal is unchanged; event views gain `<field>Code` and `<field>.<name>`, the action view `decisionBasisCode` and `decisionBasisParams`, the incident view `descriptionCode` and `descriptionParams`.
- The focus no longer leaves an open panel for its opener when the panel's subject changes (an agent's profile from one agent to another).
- The panel that opens takes the focus, found by its id: the agent settings and the API console now do, and the away summary no longer takes it in their place.
- Escape on an open confirmation cancels it only; it no longer also closes the panel opened from the settings, nor answers the tutorial offer. A reason cannot be confirmed empty, nor a new file's path.
- A mission id is never given twice: a new mission's number follows the highest one ever created, rows set aside included, and a journal creating one id twice stops the start instead of merging two missions.

### Point of no return

- Two new event types, `DeliverablesDestinationSet` and `DeliverablesCopied`. Rolling back to 1.9.0 sets these rows aside as written by a newer version: the missions stay, with their ids and their work, but without their deliverables folder and copy record until 1.10.0 runs again.


## [1.9.0] - 2026-11-02

### Added

- One main panel at a time (`ui/panelLayout.ts`, tested): opening a panel closes the main panel already open, so two panels never sit on top of each other nor hide each other's close button. The event log, the selection and the away summary stay docked; the tutorial, the palette, the context menu, the toasts and the confirmations stay above everything. Closing the last panel gives the focus back to the control that opened it. A browser journey opens every panel in a row and checks that one remains with its close button reachable.

- What the director typed survives a panel being replaced: the new mission form, the hire form and the meeting's agenda and note live in `state/drafts.ts`. A mission's end no longer takes the place of a panel holding such a draft: a toast offers the deliverables instead. Cancel in the hire form throws its draft away.

- Caps opened from the settings, and an agent's settings opened from its profile, lead back to their parent on close (Escape included); the focus lands on the reopened panel, or on the bar when the opener is gone. Of two panels opened in the same tick, the one asked for last stays. The terminal panel already only detached its screen when another panel replaced it, so the shell keeps running (test added). Signing a browser out now stops the shells that browser opened, and signing out everywhere stops them all (new); a script token cannot sign out (403 `SCOPE`). A submitted mission form, a called meeting, a hire and a sign-out all clear their drafts; an expired session keeps them.

- The context menu also closes on a press outside it. The new mission form has a "Clear the form" button. A Caps panel whose settings cannot be read says so and offers "Try again" instead of showing a half empty form. Disabled buttons of the terminal, the checks, the budget and the reopen directive now say why.

- "Ask for their view" at the director's meeting: the attendees the director names answer the agenda or a question once each, in the director's order, in a short read-only session (Read, Glob, Grep) on the meeting's own ticket, within a cost cap for the whole meeting set before the round; the panel shows the turns as a conversation with each answer's cost as Claude Code estimates it; the director replies with notes, asks another round, closes ([ADR-0049](docs/adr/0049-meeting-voices.md)).

- A meeting sits at the table the director chose: called from the conference table, its attendees walk to the conference room's twelve seats under a meeting sign; at the meeting table, the chairs first, then standing places around it ([ADR-0049](docs/adr/0049-meeting-voices.md)).

- Events `MeetingPlaced`, `MeetingViewsAsked`, `MeetingTurnTaken`; ticket kind `MEETING` (never on the board); route `POST /api/meetings/{id}/views`; `place` on `POST /api/meetings` and on the meeting view ([ADR-0049](docs/adr/0049-meeting-voices.md)).

### Changed

- A workflow is refused, with its reason, when it reviews without producing, or neither produces nor holds a committee; a decision workflow's ruling no longer offers "Adopt and implement"; a mission refuses committee members or the planner when its workflow has no such step ([ADR-0050](docs/adr/0050-workflow-pinned-to-a-mission.md)).

- A mission whose workflow this version cannot read (a newer schema) stays readable and is never run: its approval is refused (`WORKFLOW_UNREADABLE`), nothing starts, and the progress panel says why ([ADR-0050](docs/adr/0050-workflow-pinned-to-a-mission.md)).

- `DomainEvent.java` is split into one file per area (`MissionEvent`, `TicketEvent`...); the names the journal stores are unchanged (D19).

- The release chain is hardened (docs/releases.md). The browser tests now gate the release: the publish job needs them, so nothing is published when a journey fails. The zip, the CycloneDX SBOMs (Java and web) and the image carry a signed GitHub build provenance (`gh attestation verify`). The Dockerfile's base images are pinned by digest and Dependabot watches them. Archives are reproducible (`project.build.outputTimestamp`, `bin/package-release`: sorted entries, `zip -X`), `:latest` moves only for a final version, and a pull request is checked by a dependency review. Every action of the browser tests is now pinned by commit SHA.

- The end-of-mission report is rebuilt on a new `SectionedPanel` component (the frame the Settings panel already had): a short summary on top (outcome, cost, duration and time at work, each with its origin tag: measured, estimated, reported or simulated; what no commit carries; the next step), then one light section at a time (Deliverables, Tickets, Reviews, Cost, Retrospective, Next steps). Every capability stays (deliverables, close, reopen with a directive, pull request, merge command, follow-up, archive).

- The Deliverables panel moves onto `SectionedPanel` too (By ticket, By agent, Project). Settings keeps its look and behaviour; on a narrow screen the section list becomes a row of tabs.

### Fixed

- Workflows: the Workflows panel (Work menu) lists Floor42's own workflow and the company's; the director writes one (a name, its steps in Floor42's order, the plan step's limit of tickets) and the mission form offers them ([ADR-0050](docs/adr/0050-workflow-pinned-to-a-mission.md)).

- A new mission pins a copy of its workflow (`WorkflowPinned`, an opaque JSON document and its `schemaVersion`) with its creation, before its first ticket, and follows it for good: editing or removing a workflow never changes a mission already created; missions created before keep Floor42's own ([ADR-0050](docs/adr/0050-workflow-pinned-to-a-mission.md)).

- The planner may propose a workflow on one `WORKFLOW:` line of its plan; Floor42 checks it, writes its document itself, and the director edits and saves it or dismisses it. It never changes the mission being planned ([ADR-0050](docs/adr/0050-workflow-pinned-to-a-mission.md)).

- Events `WorkflowPinned`, `CompanyWorkflowSaved`, `CompanyWorkflowRemoved`, `WorkflowProposed`, `WorkflowProposalClosed`; routes `GET /api/workflows`, `POST /api/workflows`, `POST /api/workflows/remove`, `POST /api/workflows/proposals/{number}/dismiss`; `POST /api/missions` takes `workflow`; `MissionView.workflow`.

- The glass elevator now wears the palette of the decade shown (it was built before the palette was put in force and kept the previous decade's steel and chrome). D17 paid.

- A session that ended without reporting a cost (killed, stalled, held for the director's meeting, or left open by a daemon that stopped) now counts in its mission's budget: the budget takes the cap the session ran under as an upper bound (`costUsdBound` on the session event and view, never shown as an estimate or a measure); a reported cost always wins. Before, such a session counted for nothing and the cap could be passed through interrupted sessions.

- Those bounds are never labelled as an estimate: the mission view (`spentUsdBound`, `sessionsBounded`), the results, the end-of-mission report (`costBound`, basis `UPPER_BOUND`), the retrospective brief, the budget question, the spend indicator and the caps panel say "estimated X USD + up to Y USD for N session(s) with no reported cost"; `spentUsdEstimated` is the estimates only. The scorecard says "no cost reported" instead of "no session yet" when sessions ran without any.

- A restart bounds an interrupted session by the cap it ran under, now journaled with its start (`capUsd` on `AgentSessionStarted`); today's cap stands in only for a session started before this version, and the log says so.

- A Claude Code process that dies before naming its session is recorded as a session of its own (`<mission>:launch-…`) and counts for its cap, even on a mission cancelled meanwhile: a missing record is no proof that nothing was spent. The same holds when the daemon itself stops between the launch and the session's first line: the next start finds the launch in its session folder (which now keeps the cap) and counts it.

- The costs view shows the caps of the sessions with no reported cost apart from the estimate ("≈ x + ≤ y").

- Session rows leave the cap and the bound out when they have none, so a journal stays readable by 1.8.0.

- Closing a meeting during a turn could let a paid turn start after the close; a turn interrupted by a restart before its session was journaled was charged nothing; turns of speech counted in a mission's sessions and time worked ([ADR-0049](docs/adr/0049-meeting-voices.md)).

- The meeting panel showed the call form when its read of the meetings failed, and read again only while it already showed a meeting: an open meeting now always shows with its close button, a failed read shows its error in the panel, and the meeting room and a click on either table lead to the open meeting ([ADR-0049](docs/adr/0049-meeting-voices.md)).

- One upper bound in a mission's spend: the caps of the sessions with no reported cost and the meeting turns charged their budget for want of an estimate add up in `Verification` alone, a turn counted once through its session (`spentUsdBound`, `sessionsBounded`, the report's `costBound`, basis `UPPER_BOUND`). A launch left without a session is recorded before the meetings close their unfinished rounds, so it is that turn's session.

- A launch journaled live is named after its session folder (`<mission>:launch-<folder>`), the id a restart gives it: a daemon that stops before the folder is cleared no longer counts its cap twice. A launch record naming a session the journal never got counts for its cap at the restart instead of being lost. The costs panel's mission total and the retrospective's facts take the bound from `Verification`, charged meeting turns included; the costs panel's periods and its rows per agent and per ticket count a charged turn too, in its turn's period, its speaker's row and the meeting ticket's row, apart from the estimate. A result line before any init keeps the session's cap and folder, so the launch still counts. The meeting view's figure is `spentUsdCharged` (estimates and charged budgets), no longer called estimated.

### Upgrade

- Point of no return: 1.8.0 does not know the new event types. After a rollback to 1.8.0, every row of a pin, a company workflow or a proposal is left out and listed in the red banner (`NEWER_VERSION`); the missions themselves stay readable and follow 1.8.0's own workflow ([ADR-0050](docs/adr/0050-workflow-pinned-to-a-mission.md)).

## [1.8.0] - 2026-11-01

### Added

- The director's meeting: "Call a meeting" on the meeting table, the conference table and the Work menu; choose a mission and the agents (ticked: those working on it), tell them things, each or all, then close ([ADR-0047](docs/adr/0047-director-meeting.md)).

- An attendee's next tool call is refused for the meeting (the call under way finishes, its worktree stays as it is), its session ends without a report or a "Retry", and nothing new starts for it until the close; a session still running after `floor42.meeting.wind-down` (PT2M) is stopped between two calls, never a retrospective's turn or a staffing review, which no reservation can start for an attendee; after a restart the open meetings hold their attendees again before any work resumes; refused for a rehearsal (`SIMULATED_MISSION`) for an agent at an open retrospective (`AGENT_IN_RETRO`) and for one writing a staffing review (`AGENT_IN_STAFFING`); a mission that ends frees its meeting's attendees ([ADR-0047](docs/adr/0047-director-meeting.md)).

- What the director said reaches each attendee's next brief as a "Director's meeting" section, and optionally becomes a directive on their ticket under way ([ADR-0047](docs/adr/0047-director-meeting.md)).

- Events `MeetingCalled`, `MeetingNoteGiven`, `MeetingClosed`; status `IN_MEETING` (attendees sit at the meeting table under a meeting emblem); routes `GET /api/meetings`, `GET /api/meetings/invitation`, `POST /api/meetings`, `POST /api/meetings/{id}/notes`, `POST /api/meetings/{id}/close`, director only ([ADR-0047](docs/adr/0047-director-meeting.md)).

- A guided first mission (Work menu, palette, offered once after the first sign-in): thirteen steps over the real screen, from the sign-in link to going real, on a simulated mission over Floor42's own sample repository; no model is called and its costs read 0 ([ADR-0045](docs/adr/0045-guided-first-mission.md)).

- A mission keeps all its committees, numbered from 1: every committee event carries `committeeNo` (absent in older rows, read as 1), the meeting room shows "Committee n of N" and the earlier ones ([ADR-0046](docs/adr/0046-numbered-committees-and-workflow-interpreter.md)).

- A permission rule may cover the work of one ticket only (`PermissionRule.ticket`), outranking the mission's table for its tool but never a rule naming the agent ([ADR-0046](docs/adr/0046-numbered-committees-and-workflow-interpreter.md)).

### Security

- The API's documentation (`/q/openapi`, `/q/swagger-ui`) now needs the director, like the API it describes: the signed-in browser's cookie (the top bar's Docs link keeps working) or a script token; only dev mode leaves `/q/` open ([ADR-0044](docs/adr/0044-sessions-tokens-and-exact-origin.md)).

- A `missions:write` script token is narrowed to creating, approving and directing missions and answering their questions; permission questions, the director's files, workshop acceptance, pull requests, budgets, permissions and remembered approvals now need `all` ([ADR-0044](docs/adr/0044-sessions-tokens-and-exact-origin.md)).

- A path parameter (`;`, raw or encoded) no longer slips past the access guard: `/api;x/agents` reached the API without a token in 1.7.0; such paths, and encoded slashes, are now refused with `PATH_REFUSED`.

- Server tests no longer sleep to prove that nothing starts: they wait until the `MissionScheduler` runs no task (a package-private test hook), assert on their own mission and agents instead of counts over the shared journal, and `DispatcherApiTest` is split by behaviour into four classes over a shared `DispatchFixture`.

- **Upgrading to 1.8.0 shows an amber notice once.** The journal continuity check begins at the first start of 1.8.0, so that start has nothing to compare with: it shows an amber "first check" notice (to acknowledge), not the red rollback alarm. If you did not just upgrade, a missing record is still the red alarm ([ADR-0044](docs/adr/0044-sessions-tokens-and-exact-origin.md), amendment 3).

- A check that cannot be made (the read-only connection on the journal did not open) now raises a banner (`ANCHOR_FAILED`) instead of silently detecting nothing.

- A copy larger than a quarter of the daemon's heap (at most 1 GiB; `floor42.backup.restore-memory-bytes`) can no longer be prepared for a restore, and a restore that fails at the stop for any reason, `OutOfMemoryError` included, always leaves its "not applied" outcome for the next start.

- An agent can no longer roll the journal back by writing `restore-pending.properties` and restarting the daemon (shown in a 1.7.0 container): a restore is prepared only from Settings, kept in memory and applied during the daemon's own orderly stop; a start never restores, and a planted file is refused and shown in a red banner ([ADR-0044](docs/adr/0044-sessions-tokens-and-exact-origin.md), [docs/backups.md](docs/backups.md)).

- The Docker image runs the daemon from the root-owned `/opt/floor42`, so a `.env` or `config/application.properties` an agent plants in `/home/office` is no longer read; anywhere, such a file in the working directory refuses the start, as do a changed journal URL, added JDBC driver properties or SQLite extension loading ([docs/docker.md](docs/docker.md)).

- In the image, the daemon's jar and libraries belong to root (0755/0644): agents (uid 1000) can no longer replace them; `make docker-login` runs `claude` as uid 1000 in `/home/office`.

- `/api/status` reports `process.nonDumpable` as the kernel answers it; a red banner says when the daemon is dumpable.

- Another program holding the journal now gives `JOURNAL_LOCKED` and a red banner instead of a bare "could not be written"; journal transactions begin `IMMEDIATE`. It is documented, not prevented.

- SECURITY.md now says plainly that without Docker or the WSL sandbox the installed daemon (`~/.floor42/app`) and its launchers are the user's own files, which an unconfined agent can replace.

- The JVM's attach mechanism is off (`-XX:+DisableAttachMechanism` in the image, `bin/floor42`, `bin/floor42.ps1` and the dev launchers): a same-user process can no longer load code into the daemon through `/tmp/.java_pid1`; `/api/status` reports `process.attachDisabled`. On Windows a same-user process can still read the daemon's memory (SECURITY.md).

- An older journal moved over `floor42.db`, then a restart, is now detected: the stop keeps the journal it still held as `displaced-*`, and the next start shows a red banner until the director acknowledges it. Detection, not prevention (D16) ([docs/backups.md](docs/backups.md)).

- The restore outcome the next start shows is labelled as reported by a file, never as verified; a copy prepared for a restore is refused at the stop if it changed since; the migration record is read once at start; configuration files read from outside Floor42's own files (`QUARKUS_CONFIG_LOCATIONS`) are logged and shown.

- The journal's continuity record is measured through a read-only connection held on the file the daemon opened, so a swapped `floor42.db` is still caught after the pool, idle, reopens its connections on the forged file; the record is never rewritten from a journal that does not continue it, and a rewrite in place shows the banner while the daemon runs ([ADR-0044](docs/adr/0044-sessions-tokens-and-exact-origin.md) amendment 3).

- A missing (beside a journal holding events) or unreadable `journal-continuity.properties` now shows the banner (`MISSING`, `UNREADABLE`) instead of being taken for a fresh data folder; the first start of 1.8.0 shows the amber `FIRST_CHECK` notice once, and a red `MISSING` is never expected ([docs/backups.md](docs/backups.md)).

- A prepared restore no longer goes through the shared temporary folder: the stop reads the copy once into memory and restores the very bytes it hashed (copies above 1 GiB: by hand).

- The Docker image carries the commit it was built from (`/api/status` `build.commit`): `make docker-build`, `bin/floor42-docker` and the release workflow pass it as build arguments ([docs/docker.md](docs/docker.md)).

### Changed

- Director's meeting, fourth counter-check: a rehearsal's unadvised staffing review no longer bars the planner from a real meeting (`Meetings.staffingOf` counts real missions only); `Staffing` takes its scheduler by constructor; a planner session left open on a review ticket is tested to end as interrupted with no "Retry".

- Director's meeting, third counter-check: a staffing review the daemon left unadvised is closed at start-up (the planner is no longer barred from meetings for good); a subagent or MCP tool silent past the short bound still counts as running (`floor42.meeting.longest-silent-long-call`, PT30M); a participant held past the patience by the meeting says so; `Staffing` uses constructor injection ([ADR-0047](docs/adr/0047-director-meeting.md)).

- Settings redesigned: six sections chosen on the left (General, Missions, Permissions, Agents & models, Safety, Advanced), a search box, each setting with one line, its source and changes highlighted; same calls, same journaled decisions; unsaved values survive a change of section and closing the panel, a refused value stays typed with the reason beside it, and the tutorial's restart and skip sit in General.

- Build gates: every `catch` in the main sources rethrows or says how it handles the failure (`// Handled:`), source files stay within 400 lines and methods within 30 (an explicit allow-list with ceilings that only go down), and the main GET bodies are snapshotted on the golden scenario served by a daemon.

- The ownership rules cover every mutating collection method (`removeIf`, `computeIfAbsent`, `merge`, `push`, `pop`…), and each architecture rule is proven on a planted violation.

- Settings redesigned: six sections chosen on the left (General, Missions, Permissions, Agents & models, Safety, Advanced), a search box, each setting with one line, its source and changes highlighted; same calls, same journaled decisions.

- Server tests no longer sleep to prove that nothing starts: they wait until the `MissionScheduler` runs no task (a package-private test hook), assert on their own mission and agents instead of counts over the shared journal, and `DispatcherApiTest` is split by behaviour into four classes over a shared `DispatchFixture`.

- The 3D scene is split into modules (the city one builder per element, the office room by room, props by family, OfficeScene's camera, input, picking, name tags and frame loop apart), checked by snapshots of the built scene of every decade; App.vue mounts its panels from one table and the workshop panel is split into parts, with nothing visible changed.

- Committee routes name the committee: `POST /api/missions/{id}/committees/{no}/contribute` and `/send-back` replace `/committee/contribute` and `/committee/send-back`; `MissionView` gains `committees` (the history) and `committee.number` ([ADR-0046](docs/adr/0046-numbered-committees-and-workflow-interpreter.md)).

- The workflow Floor42 follows is now a value (`Workflow.BUILT_IN`: plan, approval, committee, produce, review, accept, retro) read by a pure `WorkflowInterpreter` that decides how a mission opens, where it stands, what each ticket needs and what follows a ruling or the end; behaviour unchanged, checked against the former rules at every event of a real journal ([ADR-0046](docs/adr/0046-numbered-committees-and-workflow-interpreter.md)).

- The browser test of the decades gates the heap again: the objects scripts made may grow by less than 0.25 MB per change of decade on the second round (-0.02 to 0.1 MB measured, 6.8 MB with every replaced scene kept alive). The heap's used size still grows about 0.3 MB a change, with or without a scene kept, until about the 45th change, then falls back by 3.9 MB and stays flat (measured over 56 changes): V8 compiling and three.js's binding tables, not a scene left behind.

- The browser test of the decades gates the heap on what a replaced scene could leave: per change of decade, from the second round to the third, the objects scripts made may grow by less than 0.12 MB (0.096 to 0.101 measured) and the bytes of ArrayBuffers (vertices, pixels, outside the heap's used size) by less than 16 KB (under 1 KB measured), with no more canvases and one WebGL context; keeping one 18.5 KB geometry of each replaced scene, a tenth of its geometries, its largest one or its canvases fails it. Over 56 changes the used size climbs to 25.8 MB, then falls to 21.7 MB at the 47th, when V8 turns three.js's per-geometry binding tables into dictionaries; what still grows after (V8 code, DevTools' records) is D23.

- The browser tests are type-checked with the rest of the UI (`tsconfig.e2e.json`, part of `vue-tsc -b`, so of the build).

- No function of the 3D scene is longer than 30 lines any more (24 were, up to 106); a figure's body and what it holds move to `figureBody.ts`; the glass elevator, a figure's motion and the chat table have characterisation tests recorded on the code before the split.

### Fixed

- After a restart, a mission whose director answered "Wait for the reset" no longer holds up the recovery of the missions after it: recovery schedules one wake-up per mission in the `MissionScheduler` instead of sleeping until the window resets.

- Retrospectives, staffing reviews, hiring, the live demo and the practice mission now run through the `MissionScheduler`, so nothing new starts once the daemon stops; an architecture rule refuses a thread started anywhere else outside a justified list.

- Adopting a committee's decision for implementation no longer makes Write and Edit ask on the whole mission: they ask on the implementation ticket only, the rest keeps the committee's read-only table (debt D15) ([ADR-0046](docs/adr/0046-numbered-committees-and-workflow-interpreter.md)).

- A change of decade now frees the scene it replaces (geometries, materials, textures, instanced meshes, shadow maps, the emblems, and the lookup texture three.js binds to standard materials, which kept every replaced renderer and its scene alive): after eight changes one WebGL context is reachable instead of ten, and a replaced context keeps only the renderer's 4 empty textures instead of about 2,860 buffers and 171 textures (measured).

- A workflow whose steps are out of order, which lacks the director's approval or acceptance, or which produces without a review, is refused ([ADR-0046](docs/adr/0046-numbered-committees-and-workflow-interpreter.md)).

- A permission for one ticket must name its tool, and is outranked by a mission rule naming a command of that tool ([ADR-0046](docs/adr/0046-numbered-committees-and-workflow-interpreter.md)).

## [1.7.1] - 2026-11-01

### Security

- A path parameter (`;`, raw or encoded) no longer slips past the access guard: `/api;x/agents` reached the API without a token in 1.7.0; such paths, and encoded slashes, are now refused with `PATH_REFUSED`.

## [1.7.0] - 2026-11-01

### Security

- A page on another local port can no longer drive the daemon with the director's cookie: the Origin must be exactly the daemon's, browser changes and WebSockets must name it, and changes need `X-Floor42-Request: 1` ([ADR-0044](docs/adr/0044-sessions-tokens-and-exact-origin.md)).
- The application page now has a strict Content-Security-Policy (no inline script, no eval, `object-src 'none'`, `frame-ancestors 'none'`), plus `X-Frame-Options: DENY`, `nosniff` and `Referrer-Policy: no-referrer` ([ADR-0044](docs/adr/0044-sessions-tokens-and-exact-origin.md)).
- On Linux and in Docker the daemon makes itself non-dumpable, so agents of the same user can no longer read its output (Docker's sign-in link), environment or memory through `/proc`; they still can write the journal (D16) ([ADR-0044](docs/adr/0044-sessions-tokens-and-exact-origin.md)).
- Requests carrying `X-Forwarded-For`, `X-Forwarded-Host` or `Forwarded` are refused with `FORWARDED`: use an SSH tunnel ([docs/remote.md](docs/remote.md)).

### Added

- System > Sessions and tokens: list browser sessions and script tokens, end one, sign out, sign out everywhere (which also replaces the director's token) ([ADR-0044](docs/adr/0044-sessions-tokens-and-exact-origin.md)).
- Script tokens issued from the desk: shown once, scope `read`, `missions:write` or `all` enforced by the guard, at most 30 days, in memory only; the journal records `ApiTokenIssued` and `ApiTokenRevoked` with the SHA-256, never the value ([ADR-0044](docs/adr/0044-sessions-tokens-and-exact-origin.md)).
- Routes `GET /api/auth/sessions`, `DELETE /api/auth/sessions/{id}`, `POST /api/auth/tokens`, `POST /api/auth/sign-out`, `POST /api/auth/sign-out-everywhere` ([ADR-0044](docs/adr/0044-sessions-tokens-and-exact-origin.md)).
- docs/remote.md: using a daemon on another machine through an SSH tunnel, and what is not supported.

### Changed

- The live missions' dispatcher is split out of `LiveMissions` (now a facade) into one bean per job: one shared dispatch lock, a `MissionScheduler` that always forks onto a new virtual thread, an event-to-reactions table, one `TaskRunner` per kind of session; behaviour unchanged.
- The server's director API is split by area: one resource each for agents, missions, tickets, questions, permissions, committees, retrospectives, the event log, usage, the API console and the demo (constructor injection, at most 5 collaborators), and one view file per family (`AgentViews`, `MissionViews`, `TicketViews`, `QuestionViews`, `UsageViews`...) instead of `CompanyResource` and `Views`; routes and the OpenAPI contract are unchanged.
- Git, paths and JSON go through one adapter each: an injected `Git` whose failures are a `GitFailure` (command, exit code, stderr) answered once as 409 `GIT_FAILED`, one `ConfinedPath` (`Confinement.relative`) that refuses `.git` in the workshop, the deliverables and the director's files alike, and Quarkus's `ObjectMapper` injected everywhere; `Deliverables`, `Plugins` and `PullRequests` are split into a service and its adapters.
- The domain's `Company` is now a root over one facade per area (`company.missions().create(...)`, `company.tickets().submitForReview(...)`, `company.staff().hire(...)`, `company.queries()` for cross-area reads), by composition instead of an inheritance chain; ArchUnit forbids a domain class from extending another.
- The domain's `Company` is split into areas that each keep their own state and apply their own events (staff, missions, tickets, tool calls, the desk, sessions, workshop, committees...); ArchUnit checks that each area alone changes its entities, and the journal codec refuses to start when the event types differ from the committed `event-types.txt`.
- Event lines are written by one renderer per area with explicitly named values (no reflection); the event view drops its English `text` (UI and `/api/events` use `code` and `params`, every emitted code is checked against en.ts and fr.ts), and a replay file's lines now come from the English catalogue ([ADR-0021 amendment](docs/adr/0021-settings-and-screen-language.md)).
- Browser sessions end after 8 h unused or 24 h after sign-in (`floor42.sessions.idle-hours`, `floor42.sessions.absolute-hours`) ([ADR-0044](docs/adr/0044-sessions-tokens-and-exact-origin.md)).
- `floor42.director-token-file` is deprecated: it still works, with a warning; `floor42 "objective"` reads a script token from `FLOOR42_TOKEN` first ([ADR-0044](docs/adr/0044-sessions-tokens-and-exact-origin.md)).
- The deliverables' PDF viewer is an `<iframe>` instead of an `<object>`, which the page's policy refuses.
- Agents' briefs are built by one class per role from templates in `src/main/resources/briefs`, kept byte-identical by a golden master; the end-of-mission report asks the domain what closing or a directive would refuse as a value, and no longer fails on a ticket cancelled before it started.
- docs/docker.md, compose.yaml and ADR-0034 now say exactly what agents in the container can reach.

### Fixed

- A batch of ticket approvals or a plan refused half-way no longer leaves unjournaled events in memory; any command that fails after emitting is rolled back from the journal (fails loudly in tests).
- Resuming a session with CONTINUE checks the "Retry" answer before recording the continuation.
- The dispatcher, retrospectives, staffing and the workshop read committees, tickets, retros and workspaces under the service lock, never iterating a live entity outside it (ConcurrentModificationException when the director contributed meanwhile).
- Budgets are summed and compared in micro-USD: 0.70 + 0.10 now reaches a 0.80 cap instead of letting one more session start.
- The shared dispatch lock is no longer held while git reads the worktrees or the dependency question is journaled.
- Reservations outside the dispatcher are released only by their holder and waited for at most `floor42.reservation.patience` (2 h), then fail clearly.
- Every git runs with `LC_ALL=C` and `LANGUAGE=C` (the race retry reads English messages); the sandbox's `core.autocrlf` reading goes through the git runner and its timeout.
- Answers are matched by stable option codes (`QuestionAsked.optionCodes`), not English labels; a planning ticket is recognised by `TicketCreated.kind`, not its "Plan: " title; older journals still read ([ADR-0009 amendment](docs/adr/0009-event-sourced-company.md#amendment-2026-10-31-evolving-an-event-and-stable-meanings)).
- Switching the board's mission while the previous one loads no longer lets the late answer overwrite it; the independent reads of a reload go out together.
- `UNKNOWN_*` refusals answer 404 as the API docs say (they answered 409); `UNKNOWN_DEPENDENCY` is a 400.

## [1.6.0] - 2026-10-31

### Added

- A command palette (Ctrl+K, or ⌕): fuzzy search over every action and panel, the rooms, the agents and the missions.
- A Costs panel: today, this week and this month, each mission against its cap, per agent and per ticket, every figure with its basis (`GET /api/costs`, `GET /api/missions/{id}/costs`).
- The top bar shows the mission on the board as spent / cap (green, amber from 80 %, red once reached).
- Journal backups by themselves: at every start, once a day and on demand, with a restore applied at the next start ([ADR-0042](docs/adr/0042-journal-quarantine-and-backups.md), [`docs/backups.md`](docs/backups.md)).
- A banner and a list for journal rows Floor42 cannot read, a row written by a newer version told apart from a corrupt one (`GET /api/journal/unreadable`, [ADR-0042](docs/adr/0042-journal-quarantine-and-backups.md)).
- Suspend, Resume and Dismiss on an agent's profile, with the reason when dismissal is not allowed.
- A Deliverables panel: each ticket's text and reviews, each branch's files, files never committed, pull requests; view in a sandbox or download as a ZIP ([ADR-0037](docs/adr/0037-mission-deliverables.md)).
- Core members (a star on the profile) and "Keep the core", which dismisses everyone else after a preview (`AgentMarkedCore`, `PUT /api/agents/{id}/core`).
- Recent projects in the project picker, pinned or forgotten by the director ([ADR-0038](docs/adr/0038-resume-work-and-sessions.md)).
- A Resume panel listing the missions not closed and the sessions that ended before their report (`GET /api/resume`, [ADR-0038](docs/adr/0038-resume-work-and-sessions.md)).
- Continue an interrupted developer session with Claude Code's `--resume`, on the director's click and only when measured safe ([ADR-0038](docs/adr/0038-resume-work-and-sessions.md)).
- Switching the mission on the board from the Work menu's recent missions or the palette (kept in the browser, never journaled).
- Aircraft in the sky behind the office, with navigation lights at night; none with "reduce motion".
- An end-of-mission report, ticket by ticket, with the basis of each figure (`GET /api/missions/{id}/report`, [ADR-0039](docs/adr/0039-end-of-mission-report.md)).
- Never a silent end: files a worktree holds outside any commit are named first, and closing with such work asks for confirmation ([ADR-0039](docs/adr/0039-end-of-mission-report.md)).
- The next steps of a mission in one place, each with the refusal it would meet now ([ADR-0039](docs/adr/0039-end-of-mission-report.md)).
- A small ceremony at the meeting table when a mission is completed; nothing under reduced motion or at night.
- Plugins installed from a marketplace on the director's click, for Floor42 only or into the director's Claude Code (`PluginCommandRan`, [ADR-0040](docs/adr/0040-plugin-install-and-mission-assets.md)).
- The skills and agents each mission's sessions receive, chosen per mission (`MissionAssetsSelected`, [ADR-0040](docs/adr/0040-plugin-install-and-mission-assets.md)).
- A Terminal panel running the director's own interactive Claude Code, ungoverned and said so; only opened and closed are journaled ([ADR-0041](docs/adr/0041-director-terminal.md)).
- A release workflow: on a tag, the full gate, `floor42-<version>.zip` on GitHub Releases, and the image `ghcr.io/padashtan/floor42` without Claude Code.
- Browser tests (`npm run e2e`, `make e2e`): the installed Chrome or Edge, through Playwright, against a daemon played by the fake Claude Code; the gate before a release.
- `tools/demo/hero.mjs` records the README's animation from the online demo.
- A code of conduct (Contributor Covenant 2.1), issue and pull request templates, Dependabot and CODEOWNERS.

### Changed

- Floor42 is licensed under Apache-2.0 instead of AGPL-3.0, and contributions are signed off under the DCO instead of a CLA ([ADR-0043](docs/adr/0043-apache-2-license.md)).
- The top bar groups its buttons in three menus, Work, Team and System, with Desk, Board and Progress kept as buttons; every shortcut stays.
- A real mission's plan is approved only with a decided cap or an explicit "No cap"; `POST /api/missions/{id}/approve` otherwise answers 409 `CAP_NEEDED` (new setting `MISSION_BUDGET_USD`).
- Each plan quota window shows its reading time, and `unknown since <time>` once past its reset.
- New York water towers on the walk-ups: cedar tanks with steel hoops on braced stands, one draw call for all.
- Neon signs hold to their buildings: shop names above awnings, blade signs on brackets, rooftop signs on frames.
- `compose.yaml` runs the published image by default and builds it only when it cannot be pulled; `make docker-up` builds the image with Claude Code as `floor42:local`.
- Actions in the workflows are pinned by commit SHA.

### Deprecated

- The launchers `bin/office`, `bin/office.ps1` and `bin/office-docker`, kept from Office, will be removed in 2.0: use `bin/floor42`, `bin/floor42.ps1` and `bin/floor42-docker`.

### Removed

- Build leftovers of Office's web module (`office-web/dist-demo`, `office-web/.vitest`) from the repository.

### Fixed

- A closed mission's report no longer offers to close it or to reopen a ticket the domain would refuse, and refusals are phrased, never a raw `CODE: message`.
- "While you were away" tells news once and opens below the crew's cards.
- Measured numbers in the top bar (quota gauges, spend) are never cut at any width from 1280 px.
- A question's toast leaves as soon as it is answered; clock times follow the page's language, not the browser's locale.
- One journal event Floor42 cannot read no longer stops the daemon (D14, [ADR-0042](docs/adr/0042-journal-quarantine-and-backups.md)).
- The top bar no longer hides buttons under the event log at narrow widths (checked from 1280 to 2560 px).
- The plan quotas are on screen again, next to the brand, instead of behind the rooms' strip.
- Agents no longer walk through props: every piece of furniture is an obstacle, checked by a layout test.
- Neon signs no longer hang in the air in front of a stepped tower's setbacks, nor run wider than their wall.

### Security

- An agent's shell may not run `claude plugin` or `claude mcp`, nor set `CLAUDE_CONFIG_DIR` (`ShellGuard`, [ADR-0040](docs/adr/0040-plugin-install-and-mission-assets.md)).

## [1.5.1] - 2026-10-31

### Added

- A fast development loop: `bin/floor42-dev` (`make dev`) on port 7421 with live reload and the fake Claude Code; `make test-fast`.

### Changed

- Faster tests: the fake Claude Code and the fake gh are compiled once per run, and fixed sleeps wait for their condition (a full `verify` from 7 min 36 s to 5 min 52 s on the director's Windows machine).
- `verify` checks the format before compiling.

### Fixed

- Git commands that meet another git's index lock are run again, up to five times (D13).
- The commit shown in the top bar is the one checked out where the daemon was built, a linked worktree included.

## [1.5.0] - 2026-10-31

### Added

- The top bar shows the version and the commit the daemon was built from; `GET /api/status` gains `build`.
- A Skills panel listing the Claude Code skills, agents and plugins installed, and whether they reach Floor42's sessions (`GET /api/catalogue`).
- Sandbox readiness: the tools a project needs, the ones the WSL sandbox has (measured), the commands to install the rest ([ADR-0033](docs/adr/0033-sandbox-readiness.md)).
- Pull requests on GitHub on the director's click, with the director's own `gh` ([ADR-0036](docs/adr/0036-pull-requests.md)).
- Each agent's context in use, live, on its profile and crew card (W18).
- A stall alert that follows the dispatcher's own rule and names what rightly holds work back (setting `STALL_MINUTES`).
- Timers on everything that waits for the director, the count in the tab's title, and browser notifications on request.
- "While you were away" when the app opens (`GET /api/journal/summary`).
- A mission exported as one self-contained, offline replay file, cleaned by code before it is written ([ADR-0035](docs/adr/0035-shareable-mission-replay.md)).

### Changed

- Office is now Floor42: modules, packages, configuration, launchers and data folder renamed; the old names are still read ([migration guide](docs/migration-from-office.md)).
- API errors say what kind of refusal they are (400, 409, 413) and every error body has the `{code, message}` shape.

### Fixed

- The UI built again: the context gauge's merge had dropped closing braces in `style.css` and `types.ts`.
- Bringing the director's files to the agents can no longer leave a merge the journal does not know.
- A session left open in a suspended mission is closed as interrupted on restart.
- Git paths with accents, spaces, quotes or " -> " are read as they are (`-z`), and a revision is never read as an option.

### Security

- The director's token stays in the daemon's memory; the browser signs in with a single-use link, and agent credentials get 403 on director routes (D12, [ADR-0034](docs/adr/0034-director-credential-out-of-agents-reach.md)).
- Agents' shell commands may no longer `git push` nor call `gh` ([ADR-0036](docs/adr/0036-pull-requests.md)).
- Committing the director's files commits only the paths ticked, so a stray `.env` is never swept in.

## [1.4.0] - 2026-10-31

### Added

- A business analyst, Linnea, who turns the committee members' questions into business questions for the director before the ruling.
- A Progress panel (P): what runs, what waits for the director and what looks stuck, each with its button (`GET /api/progress`).

### Changed

- The consultant's brief lists the director's files.

### Fixed

- A directive given to a committee's decision ticket after the ruling goes to the implementation ticket (seen live).
- No more silent stalls: the dispatcher looks again after answers, withdrawals, cancellations, resumes and a restart.

### Security

- Windows spellings of protected folders (`.git.`, `GIT~1`, streams) are refused to file tools.
- Git runs with a one-minute limit and literal paths; the director's files are written all or nothing, never through a late link.
- Masking also covers PEM keys, JWTs, URL credentials and `password x`.

## [1.3.0] - 2026-10-31

### Added

- Every brief on a project lists the files the director gave the mission (seen on M-11).
- Claude Code's `tool_progress` heartbeat: a running action shows "still running after N s".
- A stream line of an unknown kind shows its shape, and the director can accept it from the desk (setting `ACCEPTED_STREAM_LINES`).

## [1.2.0] - 2026-10-31

### Added

- Files for a mission: the director gives files or commits what they copied, then brings them to the agents' worktrees (`DirectorFilesCommitted`, `ProjectMerged`).

## [1.1.1] - 2026-10-31

### Fixed

- On a folder without git, the mission button initialises git and proposes the mission instead of failing.

## [1.1.0] - 2026-10-31

### Added

- Committee seats of your own: a specialist defined on the mission form, kept in the journal ([ADR-0015](docs/adr/0015-architecture-committee.md)).
- "Initialise git here" in the project picker, refused for a drive root, the home folder, Floor42's data or more than 5000 files.

## [1.0.2] - 2026-10-30

### Added

- Agents put the choices they offer in buttons; an open answer can start from the agent's recommendation.

### Fixed

- An agent whose session ended before Floor42 saw a command's outcome no longer shows as executing forever.

### Security

- A shell command that names Floor42's data directory outside the agent's worktree, or stops the daemon, is denied whatever the permission table says.

## [1.0.1] - 2026-10-30

### Added

- An agent's open question can be answered from the desk.

### Changed

- The workshop opens on a workspace with changes to decide, and names the agents at work.

## [1.0.0] - 2026-10-30

The first release: a director and governed Claude Code agents doing real work on the director's git repositories, in
an isometric office.

### Added

- Agents hired by role (planner, developer, reviewer, specialists), each with a profile built from its real missions.
- Invented characters: Geoffrey plans, Chris develops, Dehren reviews ([ADR-0032](docs/adr/0032-invented-names.md)).
- Missions on any git repository: a planner proposes tickets and a permission table, and nothing runs before the director approves ([ADR-0017](docs/adr/0017-ticket-by-ticket-validation.md)).
- Tickets in parallel, one desk and one worktree per developer ([ADR-0019](docs/adr/0019-hiring-and-parallel-work.md)).
- Blocking questions to the director from inside a session (`ask_director`, [ADR-0027](docs/adr/0027-office-mcp-server.md)).
- An independent review of every delivery by another agent; the director consults, sends back, accepts or rejects ([ADR-0011](docs/adr/0011-live-missions.md), [ADR-0013](docs/adr/0013-engineering-workshop.md)).
- Accepted branches a ticket builds on are merged, and the director decides on a conflict.
- Architecture and idea committees; a critical objection is never outvoted ([ADR-0015](docs/adr/0015-architecture-committee.md), [ADR-0024](docs/adr/0024-idea-committee.md)).
- An end-of-mission retrospective whose validated lessons reach later briefs ([ADR-0020](docs/adr/0020-end-of-mission-retrospective.md)).
- Permissions per tool (auto, ask once, ask every time, forbidden) enforced by fail-closed hooks ([ADR-0010](docs/adr/0010-director-chosen-autonomy.md)).
- File tools confined to the agent's worktree ([ADR-0012](docs/adr/0012-workspace-confinement.md)).
- An optional shell sandbox in WSL, and a Docker image whose container confines agents' commands ([ADR-0028](docs/adr/0028-shell-sandbox-wsl.md)).
- Checks defined by the director, run and measured by Floor42 in the agent's worktree ([ADR-0014](docs/adr/0014-verification-and-budget.md)).
- Budgets per mission and per session, the plan's usage windows, and a night when the quota is spent ([ADR-0016](docs/adr/0016-plan-usage-limits.md), [ADR-0025](docs/adr/0025-session-cost-cap.md)).
- An origin on every event (real or simulated) and a basis on every figure ([ADR-0005](docs/adr/0005-provenance-and-action-lifecycle.md)).
- An append-only journal that rebuilds the company on restart ([ADR-0009](docs/adr/0009-event-sourced-company.md)).
- An isometric pixel-art office on the top floor of a Manhattan tower, four looks, one per decade ([ADR-0004](docs/adr/0004-pixelated-low-poly-rendering.md)).
- Agents walk and machines light up according to what really happens; a city below with traffic and weather.
- Panels: desk, mission board, workshop, meeting room, usage, API console and docs, settings, project picker, archive wall ([ADR-0030](docs/adr/0030-archive-wall.md)).
- English and French, chosen per browser ([ADR-0021](docs/adr/0021-settings-and-screen-language.md)).
- `bin/office` and `bin/office.ps1` to start the daemon and propose a mission on the current repository.
- Docker for WSL 2 and Linux (`make docker-up`, [`docs/docker.md`](docs/docker.md)).
- A simulated demo mission that calls no model and runs no command.
- An online demo replaying a recording of that mission as a static page ([ADR-0031](docs/adr/0031-online-demo-replay.md)).

[Unreleased]: https://github.com/padashtan/floor42/compare/v1.16.0...HEAD
[1.16.0]: https://github.com/padashtan/floor42/compare/v1.15.1...v1.16.0
[1.15.1]: https://github.com/padashtan/floor42/compare/v1.15.0...v1.15.1
[1.15.0]: https://github.com/padashtan/floor42/compare/v1.14.1...v1.15.0
[1.14.1]: https://github.com/padashtan/floor42/compare/v1.14.0...v1.14.1
[1.14.0]: https://github.com/padashtan/floor42/compare/v1.13.0...v1.14.0
[1.13.0]: https://github.com/padashtan/floor42/compare/v1.12.0...v1.13.0
[1.12.0]: https://github.com/padashtan/floor42/compare/v1.11.0...v1.12.0
[1.11.0]: https://github.com/padashtan/floor42/compare/v1.10.0...v1.11.0
[1.10.0]: https://github.com/padashtan/floor42/compare/v1.9.0...v1.10.0
[1.9.0]: https://github.com/padashtan/floor42/compare/v1.8.0...v1.9.0
[1.8.0]: https://github.com/padashtan/floor42/compare/v1.7.1...v1.8.0
[1.7.1]: https://github.com/padashtan/floor42/compare/v1.7.0...v1.7.1
[1.7.0]: https://github.com/padashtan/floor42/compare/v1.6.0...v1.7.0
[1.6.0]: https://github.com/padashtan/floor42/compare/v1.5.1...v1.6.0
[1.5.1]: https://github.com/padashtan/floor42/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/padashtan/floor42/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/padashtan/floor42/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/padashtan/floor42/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/padashtan/floor42/compare/v1.1.1...v1.2.0
[1.1.1]: https://github.com/padashtan/floor42/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/padashtan/floor42/compare/v1.0.2...v1.1.0
[1.0.2]: https://github.com/padashtan/floor42/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/padashtan/floor42/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/padashtan/floor42/releases/tag/v1.0.0
