# Architecture diagrams

What Floor42 is made of at 1.17, drawn from the code. Every box names the real class, route,
table or event type in small text under its title, checked against the sources. Planned work appears only on the page
"Planned (not built)" of `01-system-context.drawio`, as dashed boxes.

Each page shows one idea: a title, a one-line note saying what it shows, at most about twelve boxes, and a legend of
the colours and arrows it uses. Colours mean the same everywhere: blue the director, green Floor42 code, purple an
agent (Claude Code), orange Keycloak, pink a secret or credential, red a check that can refuse, yellow stored data,
teal the WSL sandbox, grey what is outside Floor42. A solid arrow is a call or a command, a dashed arrow data coming
back. In the state machines the arrow colour says who makes the move: blue the director, purple an agent, green
Floor42.

## How to open them

- The PNGs are not committed: `python tools/diagrams/build.py`, then `node tools/diagrams/render.mjs` (see "How they are made" below) writes one per page in `png/`, named `<file>-<page>.png`.
- Each `.drawio` file is uncompressed draw.io XML, one tab per page: draw.io desktop (File > Open), or
  https://app.diagrams.net (File > Open from > Device; the file stays on your machine).

## The diagrams

### 10-agent-security-iam: how an agent is kept in bounds (IAM mode)

Start here. The security view of one agent, end to end, from `iam/`, `security/`, `api/HookResource`,
`api/McpResource`, `runtime/HookRegistry`, [threat-model.md](../threat-model.md) and
[iam-revocation.md](../iam-revocation.md).

| Page | What it shows | How to read it |
|---|---|---|
| 1 Identities | Every identity in the realm (the director, `floor42-desk`, `floor42-api`, `floor42-daemon`, one `floor42-agent-<id>` per agent), where each secret lives (the vault, daemon memory) and the one token a Claude Code process holds. | Groups are places: the realm, the data folder, daemon memory. Dashed arrows say whose secret or token a box holds. |
| 2 Token chain | From the plan approval with its password step-up to one allowed or refused tool call: delegation, RFC 8693 exchange, the session token's claims, the process binding, the hook checks, the permission table. | Numbered steps, left to right on the top row, then right to left; every red box can stop the chain. |
| 3 Resource broker | `call_resource`: the hook authorises, `McpResource` spends that authorisation once, the broker checks both grants, exchanges a token for the resource audience and makes one pinned HTTPS call. | Arrows numbered 1 to 9 around the loop; the agent never holds the resource token. |
| 4 Trust boundaries | The five boundaries of the threat model and what guards each one. | Each arrow into or out of the daemon is a boundary, labelled (1) to (5) with its guard. |
| 5 Attacks | Prompt injection, a stolen token, a token used by another process, a compromised shell: what stops each and what remains. | One row per attack: attack, control, residual risk. |
| 6 Revocation | Every revocation path on a time line, with the bounds measured by `RevocationGuaranteesTest`. | Columns are the delay: next call, next pass, second failed pass, 300 s, token expiry. |

### 01-system-context

| Page | What it shows | How to read it |
|---|---|---|
| 1 Outside view | The daemon and everything it talks to: browser, data folder, Claude Code sessions, WSL sandbox, worktrees, the director's repository, Keycloak, external resources, GitHub. | Arrows start at the caller; labels say what crosses. |
| 2 Inside the daemon | The parts of the one process: entry points, orchestration, domain, runtime, identity, journal. | Top to bottom: who comes in, the single writer, what it drives. |
| 3 Planned (not built) | Container broker, OpenFGA, a separate security authority. | Dashed: none of it exists in the code. |

### 02-modules

| Page | What it shows | How to read it |
|---|---|---|
| 1 Maven modules | `floor42-domain` (JDK only), `floor42-server`, `floor42-web`. | The web app speaks HTTP and WebSocket only. |
| 2 Server imports | How many import lines each server package has towards the others and the domain. | A matrix counted from the sources at build time: the row imports the column. |
| 3 ArchitectureTest rules | The eight ArchUnit rules and the scene boundary test. | One box per rule, named as in the test. |
| 4 Web imports | The same matrix for the folders of `floor42-web/src`. | The row imports the column; tests left out. |

### 03-event-model

| Page | What it shows | How to read it |
|---|---|---|
| 1 Write path | A command from its sender to `OfficeService.execute`, the facades, the envelope, memory and `event_journal`. | Senders on the left, then the one writer and its two paths: emit and append. |
| 2 After the append | Who hears a stored event: the WebSocket, the reactions, a hook waiting for the director. | The browser reloads views through REST; it never reads the journal. |
| 3 Start and provenance | Replay at start, the continuity check, and the `Origin` every row carries. | |
| 4 to 7 Event catalogue | Every event type by family, read from `floor42-domain/.../event/*Event.java` at build time. | One card per family, with its count. |

### 04-authentication

| Page | What it shows | How to read it |
|---|---|---|
| 1 Access guard | The checks of `LocalAccessGuard` before every route. | Follow the request; red boxes can refuse. |
| 2 Director sign-in | The console link (break-glass in IAM mode) and Keycloak sign-in. | Two lanes ending in the same session registry. |
| 3 Script tokens | Script tokens and the director's token. | |
| 4 Plan approval step-up | The password step-up before a plan approval in IAM mode. | Clockwise from the approval request. |
| 5 Agent token minting | How `SessionIdentity.mint` obtains and checks a session token before the process starts. | Top row left to right, bottom row right to left. |
| 6 Every agent call | The four checks of every hook and MCP call, and their refusal codes. | Left to right; each check has its refusals under it. |

### 05-iam-delegation-and-broker

Five sequence diagrams: lifelines left to right, numbered messages top to bottom, a dashed arrow is an answer.

| Page | What it shows |
|---|---|
| 1 Plan approval | The step-up creates the mission's delegation (its own offline session). |
| 2 Session token | A session gets its token; any failure stops the start. |
| 3 Hook call | One hook call checked, then decided by the permission table. |
| 4 call_resource | One brokered call to an external resource, with the refusal codes. |
| 5 Revocation | Local revocation, Keycloak-side revocation and the background pass. |

### 06-mission-lifecycle

One state machine per page, with the event of each move: Mission, Ticket, Action, Graph node, Proof of delivery,
Retrospective. A black dot is the start, a ringed dot the end.

### 07-agent-session-runtime

| Page | What it shows | How to read it |
|---|---|---|
| 1 Starting a session | Dispatcher, runner, brief, gate, runtime, the `claude -p` process and its two configuration files. | Top row, then down. |
| 2 One tool call | The PreToolUse hook path and what an authorised call does. | Red boxes can refuse; the group at the bottom is what runs. |
| 3 What Floor42 records | stdout parsed into events and journaled by Floor42. | |
| 4 Fail-closed paths | Twelve ways a session is stopped or refused, none of which allows by default. | One box per path. |

### 08-ui-model

| Page | What it shows |
|---|---|
| 1 Screen | The top bar, the panels of the HUD and the 3D office under them. |
| 2 State and data flow | Stores, REST client, event stream, scene, and the compile-time contract. |

### 09-api-map

| Page | What it shows |
|---|---|
| 1 Who may call what | The director, an agent session and anyone, through `LocalAccessGuard`, to the route groups. |
| 2 to 4 Director routes | Every director route by OpenAPI tag, read from `floor42-web/src/api/openapi.json` at build time, with the scope it needs: [r] read, [w] missions:write, [a] all only (the rule of `LocalAccessGuard.allowedByScope`). |

## How they are made

The pages are declared in `tools/diagrams/pages.py` (boxes, groups, arrows, and their place on a grid of rows).
`tools/diagrams/build.py` computes every position (60 px between boxes, 40 px inside a group, a box as wide as its
label, a gap widened only where an arrow label needs room), routes the arrows around the boxes, and writes the
`.drawio` files and one HTML page per diagram page. Python 3, standard library only. It fails, and writes nothing,
when two boxes overlap, a label does not fit its box, an arrow crosses a box other than its two ends, a group does not
hold its children with its padding, or an arrow label finds no free place. A self test first checks that each of
these checks fails on a broken page.

Regenerate after a change:

```
python tools/diagrams/build.py
NODE_PATH=<a node_modules with playwright> node tools/diagrams/render.mjs
```

The second line screenshots each page with headless Chrome (`FLOOR42_CHROME` names the executable when Chrome is not
found by its channel) and writes `png/*.png` as palette PNGs. `floor42-web/node_modules` has Playwright after
`npm ci` there. `python tools/diagrams/build.py --check` runs the layout and the checks only.

## Patterns

Each pattern below is used in the code today. The file named shows it best.

**Event sourcing with one journal.** The company's state is never stored as such: every change is an event appended
to the table `event_journal`, and the state is what replaying those rows gives. The database refuses updates and
deletes by trigger. See `floor42-server/src/main/resources/db/migration/V1__event_journal.sql` and
`floor42-server/src/main/java/dev/floor42/server/journal/SqliteJournal.java`.

**One writer.** Every command goes through `OfficeService.execute`, which is synchronized: the domain checks the
command, emits its events, the journal stores them, then subscribers hear about them. If the append fails, the
company is rebuilt from what is stored. See `floor42-server/src/main/java/dev/floor42/server/app/OfficeService.java`.

**Provenance on every event.** `EventEnvelope` refuses an event without its `Origin` (REAL or SIMULATED) and its
actor (director, agent, system); the column `origin` has a CHECK constraint. A demo can never pass for real work.
See `floor42-domain/src/main/java/dev/floor42/domain/event/EventEnvelope.java`.

**Projections.** The area models of the domain (`MissionBook`, `TicketBoard`, `ActionLog`, `Desk`, ...) apply each
event in memory; the API's views (`MissionViews`, `TicketViews`, ...) read them. The UI never reads the journal: it
reloads views through REST on every WebSocket connection. See
`floor42-domain/src/main/java/dev/floor42/domain/TicketBoard.java` and `floor42-web/src/api/eventStream.ts`.

**Quarantine and continuity of the journal.** A row that cannot be read (corrupt, or written by a newer version) is
left out and listed, not fatal; a journal swapped or truncated between two runs raises an alarm at start. See
`floor42-server/src/main/java/dev/floor42/server/app/JournalReplay.java` and
`floor42-server/src/main/java/dev/floor42/server/journal/JournalContinuity.java` (ADR-0042, ADR-0044).

**Readers before writers.** A new event type ships in a release that can also read it, and the test pins the list of
types an older release knows, so rolling back stays possible. See
`floor42-server/src/test/java/dev/floor42/server/journal/RollbackEventTypesTest.java`.

**Fail-closed hooks.** Claude Code is configured with one HTTP PreToolUse hook on every tool, with `onFailure` set to
`block`: if Floor42 does not answer, the tool does not run. A call the director has not decided in time is denied. A
tool that ran without authorization raises an incident and suspends the mission. See `writeSettings` in
`floor42-server/src/main/java/dev/floor42/server/runtime/ClaudeCodeRuntime.java` and
`floor42-server/src/main/java/dev/floor42/server/api/HookResource.java`.

**Policy in code, not in prompts.** The permission table (ALLOW, ASK, ASK_ALWAYS, DENY) is evaluated by the domain on
every call; the brief only tells the agent what the code will enforce anyway. File tools are kept in the worktree by
`Confinement`, and shell commands that reach Floor42's data, `git push` or `gh` are refused by `ShellGuard`, whatever
the table says. See `floor42-domain/src/main/java/dev/floor42/domain/ActionLog.java` and
`floor42-server/src/main/java/dev/floor42/server/runtime/ShellGuard.java`.

**The sandbox.** Bash commands of a real mission run in a WSL distribution under `unshare` and `setpriv
--no-new-privs`, with the network cut unless the director opens it; the hook rewrites the command before Claude Code
runs it. Without the sandbox a real session does not start, unless the director switches unsafe mode on. See
`floor42-server/src/main/java/dev/floor42/server/runtime/Sandbox.java` and `SessionTally.java` (ADR-0028, ADR-0063).

**Identity bound to a process.** In IAM mode an agent's Keycloak token is accepted only from the process Floor42
started: the owner of the connecting socket must be that PID. A copy of the token used by `curl` from the agent's own
shell is refused. See `floor42-server/src/main/java/dev/floor42/server/iam/IamSessions.java` and
`floor42-server/src/main/java/dev/floor42/server/iam/SocketOwners.java` (ADR-0064).

**A broker instead of credentials.** An agent never holds a credential for an external resource: Floor42 exchanges a
token for one audience, for one call, and makes the call itself to an address it resolved and checked once. See
`floor42-server/src/main/java/dev/floor42/server/iam/ResourceBroker.java` and `SafeUrls.java`.

**Secrets kept out of reach and out of text.** Client secrets and delegations are encrypted at rest (AES-256-GCM); the
journal keeps a token's `jti` or a SHA-256, never the token; text leaving the machine goes through one masker. See
`floor42-server/src/main/java/dev/floor42/server/iam/SecretVault.java` and
`floor42-server/src/main/java/dev/floor42/server/security/SecretMasker.java`.

**Module boundaries checked by the build.** The domain depends on the JDK only; runtime and journal may not reach the
application; nothing but the API depends on the API; security does not depend on business state; threads start only
in `MissionScheduler` and a justified list. See
`floor42-server/src/test/java/dev/floor42/server/ArchitectureTest.java`, and for the web app
`floor42-web/src/scene/boundaries.test.ts`.

**SizeGate.** A source file and a method have a maximum length, with a small tolerance that warns before it fails.
See `floor42-server/src/test/java/dev/floor42/server/SizeGateTest.java`.

**CatchGate.** No failure is swallowed silently: every catch either rethrows or says, in a `// Handled:` comment, what
happens instead. See `floor42-server/src/test/java/dev/floor42/server/CatchGateTest.java`.

**DebtGate.** Above five open items in `docs/DEBT.md` the build fails, so debt is paid before features land. See
`floor42-server/src/test/java/dev/floor42/server/DebtGateTest.java`.

**SentenceCodeGate.** A view that carries a sentence built by the server also carries a code, so the UI can show the
sentence in the director's language. See `floor42-server/src/test/java/dev/floor42/server/SentenceCodeGateTest.java`.

**Golden master.** One long scripted day touching every area is replayed on each build; what each command emitted or
why it was refused, and the replayed state, must match the recorded master. See
`floor42-domain/src/test/java/dev/floor42/domain/GoldenScenarioTest.java` and `floor42-domain/src/test/resources/golden`.

**A fake Claude Code in tests.** Server tests launch a test double of the Claude Code CLI that speaks stream-json and
makes real HTTP calls to the hook, so the hook path is tested end to end without a paid model. Keycloak has a fake
too. See `floor42-server/src/test/resources/fake-claude/FakeClaude.java` and
`floor42-server/src/test/java/dev/floor42/server/iam/FakeKeycloak.java`.

**Contract test of the OpenAPI snapshot.** The server's API is compared with the snapshot `openapi.json` kept in the
web app, and the hand-written TypeScript views must have exactly the snapshot's fields, or the type check fails. See
`floor42-server/src/test/java/dev/floor42/server/api/OpenApiContractTest.java` and `floor42-web/src/api/contract.ts`.

**Two language catalogues.** Every UI text exists in English and French: `fr` is typed as
`Record<keyof typeof en, string>`, so a key missing in French fails the type check, and a test checks that both
catalogues know every event code the server can emit. See `floor42-web/src/i18n/fr.ts` and
`floor42-web/src/i18n/catalogue.test.ts` (ADR-0021).

**Browser tests against a real daemon.** Playwright journeys run the packaged jar with the fake Claude Code on a
temporary data folder; no console error is allowed. See `floor42-web/e2e/` (run with `make e2e`).

## Keeping them true

When a box no longer matches the code, fix `tools/diagrams/pages.py` in the same change as the code, regenerate, and
commit the `.drawio` files. The event catalogue, the import matrices and the route lists are read
from the sources at build time; every other name is written by hand and was checked against the code at 1.17.
