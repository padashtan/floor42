# ADR-0058: Prove the delivery: a recipe from the producer, run by Floor42 in the sandbox, finished by the director

Date: 2026-11-03 · Status: Accepted (the director approved the four event types of section 8 and "Send back to the
producer" as a directive on the producer's ticket; their point of no return is the release that ships them)

## Context
The director: "Once the project is delivered, how do we test it and show it? The deliverable is a docker compose or a
script: how do we prove to the user it really works?" Today a mission ends on the reviewer's word and, at best, on the
mission's check command (one command, chosen by the director, run in the producer's worktree). Nothing asks the
producer how its work is shown to work, nothing runs that, and nothing records what the director saw when they tried
it. A compose file that never started can be accepted with every ticket validated.

## Decision

### 1. The recipe: `PROVE.md` at the root of the producer's worktree
The producer delivers, with its work, a file `PROVE.md`: the commands to run and what each must show. Grammar:

    # Any title (ignored)
    Free text (ignored).

    ## Start the service
    ```sh director
    docker compose up -d
    ```
    Expect exit: 0

    ## The health route answers
    ```sh director
    curl -s localhost:8080/health
    ```
    Expect output: {"status":"UP"}

- A step starts at a level-two heading (its title, at most 120 characters) and holds exactly one fenced block whose
  info string is `sh`, `bash` or `shell`, optionally followed by the word `director`. The block is the command.
- `Expect exit: <n>` at most once, `Expect output: <text>` any number of times (the text must appear in the output,
  line endings normalised, case kept). A step with neither is not run (`NO_EXPECTATION`).
- Limits: 64 KiB per file, 20 steps, 4 000 characters per command. A step with a problem is shown with its code and
  never run: `NO_COMMAND`, `TWO_COMMANDS`, `NO_EXPECTATION`, `TWO_EXIT_CODES`, `BAD_EXIT_CODE`, `COMMAND_TOO_LONG`,
  `TITLE_TOO_LONG`; for the file: `TOO_LARGE`, `TOO_MANY_STEPS`, `NO_STEPS`, `NOT_A_FILE` (a link, a folder).
- No recipe in a worktree is said, never read as a pass: `MISSING`. A worktree Floor42 did not make is `UNKNOWN`.
- The producer's brief asks for the file; the reviewer's brief asks the reviewer to read it and to say when it is
  missing or proves nothing of the acceptance criteria.

Why a file in the worktree and not a block parsed from the producer's report: the file is part of the work under
review (the reviewer opens it, the workshop shows its diff, a commit and a pull request carry it to the director's
project), it survives a retry or a directive that rewrites the report, and it is parsed the same way every time. A
report is free text, cut when long, and replaced by the next session. Why Markdown and not JSON or YAML: the director
reads it as is, the commands sit in the fences any Markdown viewer shows, and no parser is added.

### 2. Who runs each step
- A step marked `director`, or whose command starts a line with `docker`, `docker-compose`, `podman`, `kubectl`
  (no daemon in the sandbox) or `open`, `xdg-open`, `start`, `explorer` (a desktop), is for the director:
  `MARKED_FOR_DIRECTOR`, `NEEDS_DOCKER`, `NEEDS_DESKTOP`.
- Every step after a director step is for the director too (`AFTER_DIRECTOR_STEP`): it may need what that step started.
- When the sandbox does not work on this machine, every step is for the director (`SANDBOX_UNAVAILABLE`).
- The others are Floor42's. Floor42 runs them in order, one sandbox per step (only the copy's files carry over), and
  stops at the first that fails: the rest are `NOT_RUN` (`EARLIER_STEP_FAILED`).
- A step's outcome: `PASSED`, `FAILED` (`EXIT_CODE`, `OUTPUT_MISSING`, `TIMED_OUT`, `DID_NOT_START`) or `NOT_RUN`.
  Exit code, duration and output are measured by Floor42 (basis `MEASURED`); the recipe stays the producer's words
  (`REPORTED`).
- A step whose command, title or expected text holds a bidirectional override or isolate, a zero-width character or a
  control character other than a line break or a tab is `HIDDEN_CHARACTER`: never run and never offered to copy (the
  text pasted would not be the text read). A cut command (`COMMAND_TOO_LONG`) has no copy button either.

### 3. Security
- A command written by an agent runs only in the WSL sandbox of ADR-0028, never on the host: there is no fallback.
  The proof needs the sandbox to work, whatever the director's `SHELL_SANDBOX` setting says for agents; when it does
  not work, nothing runs and the steps go to the director.
- **Isolation from the host relies on the sandbox escape fix (amended ADR-0028), merged in 1.14.0.** The
  counter-check found that a command could unmount the masks over `/mnt` and `/run` and start a Windows program
  through WSL's interop. `Sandbox.argvOffline` builds its arguments through the same `tail` as every sandboxed command,
  so a proof step runs under the same `run.sh`, `init.py`, seccomp filter and limits (process count, file size and wall
  time, arguments 7 to 9 of `run.sh`) as an agent's command, plus the network cut and no git folder mounted. The proof
  adds no limit of its own beyond the per step timeout below.
- The network is always cut for the proof, even when `SANDBOX_NETWORK` is on for the agents: a recipe gets less than
  the agent that wrote it, never more. `ProofSandboxIsolationTest` runs against the real sandbox (skipped where WSL does
  not work): a step writing to a Windows path, to the folder above its folder or to `/etc` leaves the machine unchanged,
  and a name lookup fails.
- **The proof never runs in the worktree.** Under the mission's change lock (refused while it closes, 409 `CLOSING`) and
  holding every producer (refused while a session works on the mission, 409 `WORKTREE_BUSY`, the workshop's hold),
  Floor42 reads each worktree's commit and a digest of its uncommitted work, then copies every file git lists (tracked
  and new, not ignored; never `.git`, never a link, never outside the worktree; at most 20 000 files and 500 MiB) into a
  folder of its own under the data folder. The sizes are read and the caps checked before the first byte is copied.
  A file git lists that cannot be copied is left out and named with its reason (`OUTSIDE`, `GIT`, `WINDOWS_NAME` for a
  name Windows cannot hold, `CASE_COLLISION`, `NOT_A_FILE` for a link or a junction), never a failed request; the
  screen shows the list (kept in memory: `ProofRan` holds the steps only). The steps run on that copy, in the
  background (the route answers at once, the screen follows the run), and the copy is deleted when the run ends, without
  following any link or junction a step left in it (a link is removed itself, never entered; what cannot be deleted is
  logged). The holds last until then. Edits, commits, resets and build outputs of a step never reach the work.
- **Reading the code never fails the acceptance.** The digest of the uncommitted work is streamed (git's diff through a
  file, each new file in 64 KiB reads); a new file over 16 MiB is named by its size and last change, not read. When git
  fails or a file cannot be read, the code is unknown: the screen says so and the acceptance asks (`NO_RECIPE`, "cannot
  tell what code was delivered"), never a server error.
- A close or a cancel is refused while the proof runs (409 `PROOF_RUNNING`): the run ends first, then the mission
  closes. A run cannot start while a close holds the mission.
- **No git folder is shared with a proof step.** `argvOffline` reads nothing from the folder (no `.git` file, no git
  configuration) and passes `none` for every git mount: a step that rewrites a `.git` file cannot choose what the next
  step mounts.
- Only a folder Floor42 made (`MissionBranches.madeByFloor42`, links resolved) is read or copied; `PROVE.md` must be a
  regular file inside it.
- Each step stops at `floor42.proof.step-timeout-seconds` (300), its children with it; the output keeps its last 8 000
  characters. One run at a time per mission (`PROOF_RUNNING`); an acceptance waits for the run to end. The environment
  loses `CLAUDE*`, `FLOOR42*`, `OFFICE*`.
- Director steps are shown and never executed by Floor42 anywhere. The screen has no button that runs a director step.
- Only the director runs the proof and records reports and verdicts (the routes take the director's credentials; a
  browser change needs the usual request header and origin, ADR-0044). Outputs and recipe text are data on screen.

### 4. A run, a report and a verdict are bound to the code they proved
Each is recorded with its target: the worktree, the digest of `PROVE.md`, the commit and the digest of the uncommitted
work (`ProofTarget`). When any of them changes, the run is `stale`, the reports and the verdict count for nothing, and
the acceptance asks again. A verdict covers every producer's target as it was. A report carries back the worktree's
`target` and a verdict the view's `targets` as the director read them: when the recipe or the code changed in between,
they are refused (409 `PROOF_STALE`) and the screen shows what is there now, so nothing is recorded against code the
director did not see.

### 5. The screen "Prove the delivery"
A panel on the frame of Settings (`SectionedPanel`), opened from the Work menu, the palette and the end-of-mission
report. Sections: Recipe (each worktree's steps: title, command with a copy button, expected result, who runs it and
why), Floor42's run (the button, then each step's exit code, duration, outcome and output; a stale run is said), Your
run (the director's steps, to run by hand: "works" or "does not work, here is what I see", recorded), Verdict (what is
still unproven, the verdict recorded, "Send back to the producer").

### 6. Send back to the producer
"Send back to the producer" sends the ticket the agent of that worktree produced (its latest done work ticket) back to
work, in the same mission and the same worktree, with the evidence as a directive: each step Floor42 ran that failed on
the current code (command, expected result, exit code, the end of the output), each of the director's steps recorded
as not working with what they saw, and the director's words. Its review starts again after the fix. An existing event
only: `DirectiveGiven`, the director as actor, the mission's origin. No new ticket and no new mission (approved by the
director, 2026-11-03).

The design agreed first said "a fix ticket". Measured while building it: a new ticket assigned to that agent never
starts while the agent's earlier work waits in the workshop, unaccepted (`HeldWork` keeps a developer with pending
changes off new tickets). That is the usual state at this point of a mission, so the fix ticket would wait for the
director to commit work the proof just showed broken. A directive resumes the same ticket at once.

### 7. The report and the acceptance
The end-of-mission report has a section "Proof": who verified (Floor42 in the sandbox, the director by hand), when, on
which commit, the outcomes, the verdict, and an acceptance without proof with its state. `GET /proof/acceptance` says what an acceptance must be told: nothing when
every Floor42 step passed on the current code and a current verdict says `PROVEN` (or the mission has no producer
worktree); else `NO_RECIPE`, `FAILED`, `NOT_RUN`, `NOT_PROVEN` or `NO_VERDICT`, with a token naming that exact state.
`POST /complete` is refused with 409 `UNPROVEN_DELIVERY` unless its `proofAcknowledged` carries the token, as ADR-0055
does for uncommitted work; the screen asks the director with the reasons first, and asks again when the state changed.
The override is journaled with the completion. A proof Floor42 cannot read is said as such, never taken for a pass.
`floor42.proof.required` (true) turns the question off in tests, as retrospectives are, except `ProofApiTest`.

### 8. Events (approved by the director, 2026-11-03)
Each is a point of no return once released (the journal keeps them forever). Area: deliveries.

| Event | Fields | Actor | Origin |
|---|---|---|---|
| `ProofRan` | mission, target (workspace, recipeDigest, head, changesDigest), steps: list of {step, title, command, runner, reason, outcome, failure, exitCode, timedOut, durationMs, output (8 000 characters at most)} | system (Floor42 measured it) | the mission's |
| `ProofStepWitnessed` | mission, target, step, works, seen (4 000 characters at most, required when it does not work) | director | the mission's |
| `ProofVerdictGiven` | mission, verdict (`PROVEN`, `NOT_PROVEN`), targets, note | director | the mission's |
| `UnprovenDeliveryAccepted` | mission, state (`NO_RECIPE`, `NOT_RUN`, `FAILED`, `NOT_PROVEN`, `NO_VERDICT`), token | director, in the same command as `MissionCompleted` | the mission's |

Each has its lines in `DeliveryRenderer` (`ProofLines`) and `ev.*` codes in both catalogues. Nothing is recorded on a
closed mission.

## i18n
Every text of the screen, the reasons, outcomes and refusals, and the acceptance question are `prove.*` codes in `en.ts`
and `fr.ts`; the event lines are `ev.Proof*` and `ev.UnprovenDeliveryAccepted.*`. The recipe, the commands and the
outputs are shown as written: they are data, not translated.

## Options not taken
- Parsing the recipe from the producer's report: see section 1.
- Running the recipe on the host when the sandbox does not work: an agent's command would reach every file of the
  director's account. Refused outright.
- Running the steps in the worktree and removing what they create afterwards (what the checks do): a step can edit a
  tracked file, commit or reset, and the work under review would change. A copy costs a copy.
- A linked `git worktree add` of the head for the copy: its `.git` file and git directory would be shared with the
  steps. A plain copy of the listed files has none.
- Reusing `ChecksRecorded` to journal Floor42's run: a run is a list of steps with expectations a `CheckRun` cannot
  hold, and the checks feed the briefs' "last check failed".
- A fix ticket for "Send back to the producer": see section 6.

## Rollback
The four event types are readable forever once released: a rollback build keeps their records, their area's `apply`
and their renderer lines; the screen, the routes and the acceptance question may go. `PROVE.md` files left in
worktrees are harmless text. Copies under `<data>/proofs` are deleted at the end of each run; one left by a crash can be
deleted by hand.

## Reopen when
A recipe needs the network to prove anything (a package download): the network then needs a rule of its own per
mission, not the agents' switch. Docker becomes reachable from the sandbox: `NEEDS_DOCKER` steps can then run there.
Directors accept unproven deliveries most of the time: the producers' briefs need work, not the alert. A worktree is
too large to copy for each run.
