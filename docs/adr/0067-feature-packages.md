# ADR-0067: Feature packages in `app` and `api`, their dependencies checked by ArchUnit

Date: 2026-11-12 · Status: Accepted

## Context

`floor42-server` keeps the layer packages of ADR-0003 (`api`, `app`, `config`, `iam`, `journal`, `runtime`,
`security`). Two of them grew flat: `app` held 172 classes (26 480 lines, 46 % of the server) and `api` 88 classes
beside `api.event`. Reading one feature meant scanning a list of 172 names, and nothing stopped a new class from
reaching any other.

The modularisation study of 2026-11-12 (measured on `main` 5ac80194) weighed three options:

| Option | What it gives | Measured cost |
|---|---|---|
| Keep `app` and `api` flat | nothing to move | no boundary inside 46 % of the server; one cycle between layer packages (`iam` and `runtime`) not even checked |
| **Feature packages inside `app` and `api`, ArchUnit rules** | a list of 1 to 40 names per feature; boundaries checked at test time (about 6 s) | 272 class references between the proposed contexts, 39 against the intended direction; at least 74 declarations to make public (a `javac` run on a copy, a minimum since `javac` hides errors behind the first ones) |
| Maven modules per context | boundaries checked at compile time | pilot on `security`: unit tests 7.4 s instead of 18 s, but Quarkus CDI failed (90 unsatisfied dependencies) until a `beans.xml` was added, `SizeGateTest` and the other source gates went blind to the moved files, and nothing gained on the API tests, which are 74 % of the test time and need the assembled application |

Neither the merge collisions (they sit in shared files: `PROGRESS.md`, `CHANGELOG.md`, the i18n catalogues,
`openapi.json`) nor the test time depend on how Java is split: the gain is reading and rules.

## Decision

1. ADR-0003 is not reopened: no new Maven module. Its criterion (a package needs its own release cycle) is not met,
   and the pilot showed the price above. The layer packages and their rules stay as they are.
2. `app` is split into business contexts. Each one is cohesive, and the dependencies between them form a directed
   acyclic graph; a context may use only the contexts listed after the arrow:

   | Context | Holds | May use |
   |---|---|---|
   | `app.core` | the shared kernel: `OfficeService` (the company), settings, the scheduler, process helpers, `RefusedException` | nothing |
   | `app.workspace` | worktrees and project folders on disk, the destination folder rules | core |
   | `app.mission` | missions and their dispatch: tasks, runners' contract, reservations, holds, dependencies, staff, permissions, the director's workshop | core, workspace |
   | `app.delivery` | deliverables, proofs, checks, pull requests, reports, the director's files, reading what a branch delivered | core, workspace, mission |
   | `app.session` | agent sessions: the gate, the governor, the runners, every brief and its parsers, node tests | core, workspace, mission, delivery |
   | `app.committee` | work convened outside the dispatcher: committees, retrospectives, the director's meetings, staffing reviews | core, workspace, mission, session |
   | `app.asset` | skills, agents, plugins, the marketplace | core, session |
   | `app.demo` | the demo and the practice mission | core, workspace, session |
   | `app.terminal` | the director's terminal | core |
   | `app.cost` | costs | core |
   | `app.boot` | the wiring: event reactions, the start, the recovery after a restart | every context |

   ```mermaid
   flowchart TD
     boot --> committee & asset & demo & terminal & cost
     boot --> session & delivery & mission & workspace & core
     committee --> session & mission & workspace & core
     asset --> session & core
     demo --> session & workspace & core
     session --> delivery & mission & workspace & core
     delivery --> mission & workspace & core
     mission --> workspace & core
     workspace --> core
     terminal --> core
     cost --> core
   ```

   Departures from the study's mapping, each to keep a context cohesive or the graph acyclic: `Workshop` and its three
   helpers join `mission` (they hold the mission's dispatch while the director edits, a class-level cycle with
   `AgentReservations` and `Dispatcher`); the git readers of a branch (`MissionBranches`, `ResolvedBranch`,
   `GitReader`, `WorktreeScanner`, `Delivered`, `FileKinds`, `BlobBytes`) join `delivery`, their only user; every brief
   (`CommitteeBriefs`, `RetroBriefs`, `StaffingBrief`) stays with `Briefs` in `session`, which lists them;
   `GraphExtensions`, `TicketSpread`, `WorkflowProposals` and `UnnamedLaunch` join `session`, which uses them;
   `CommitteeRoles` and `MissionPermissions` go down to `mission`; `Checks` joins `delivery`; `Staffing` joins
   `committee` (a session outside the dispatcher, like a retrospective's turn); `MissionRecovery` joins `boot`;
   `Costs` is a context of its own (`app.cost`). One code change was needed: `MissionFactory` resolves the delivery
   folder itself instead of asking `DeliverablesHome` (mission may not use delivery).
3. `api` is split the same way: the resources and views of a context live in `api.<context>`. The package `api`
   itself keeps the HTTP plumbing every resource shares (contract, errors, headers, `DirectorRequests`,
   `FileResponses`, `SocketSessions`, status, sign-in) and depends on no sub-package. Four contexts exist only in the API:
   `api.console` (the API console), `api.journal` (event log, journal, replay export), `api.iam` (identity, audit,
   broker catalogue, trust chain) and `api.gateway` (what agent sessions call: hooks and MCP); and `api.office`
   (company-wide pages: settings, sandbox readiness, unsafe mode, notes). The mission's read model (`MissionViews` and
   every view it composes, committee and delivery views included) is `api.mission`: the committee, delivery and cost
   resources answer with it. `missionView` moved from `DirectorRequests` to `MissionViews` for that. Routes are not
   changed: URLs are a public contract since v1.0.0.
4. `api.event` (the event renderers, 25 classes) stays one package: its classes follow the domain's event files, not
   the application's contexts, and one registry (`EventRenderers`) lists them all; only `api.journal` uses it.
   Spreading the renderers over the contexts would make that registry reach into every API context.
5. `ArchitectureTest` checks the table above for `app` and the matching one for `api` (`layeredArchitecture`), that
   the contexts of each are free of cycles (`slices().beFreeOfCycles()`), and that no class is left in `app` itself.
   The eight existing rules stay unchanged; `app` never depends on `api` (`apiIsALeaf`).
6. Tests follow the class they test (same sub-package); a test that crosses contexts stays with the one it uses most.

## Consequences

- Declarations made public: 129 in the main sources (18 types, 111 members, in 53 classes) and 20 in the tests, against
  at least 74 the study counted (its `javac` run stopped at the first errors). Each is listed in the annex. They are the contract of a context towards
  the contexts above it (the runners' `TaskRunner`, `Task`, `Work`; `SessionGate` for the committees; `AgentWorktrees`
  for the sessions), or a test seam a test in another context calls. A rule per context, not an `internal`
  sub-package, keeps the surface from growing in silence.
- Package-private stays the default inside a context; a class moves to the context that uses it rather than turning
  public.
- Class names do not change and the journal stores events by their simple name: moving a package breaks no journal.
- A clean build is needed once after pulling: stale classes of the old packages in `target/classes` would give
  ArchUnit and CDI two copies of each class (`rm -rf floor42-server/target/classes floor42-server/target/test-classes`).
- The notes that cite `server/app/X` (`docs/PROGRESS.md`, claude-mem) keep the old path; `PROGRESS.md` points here.

## Reopen if

- A context needs its own release cycle or runtime (the IAM broker or the vault in a separate process, a second agent
  runtime): then a Maven module, and ADR-0003 reopened.
- A context passes 45 classes or two contexts need each other: split or merge them, never allow a cycle.

## Annex: declarations made public

Counted against 656b9d0e (the commit before the moves): a declaration that was package-private there and is
`public` now. The reason is the contexts whose code names the class (comments included), outside its own; "test seam
only" means only tests of another package call it. The three test bases `ApiTestBase`, `DispatchFixture` and
`QuestionApiTestBase` also expose their helpers as `protected`, for the tests that moved to `api.<context>`.

| Class | Made public | Named by |
|---|---|---|
| `api.BoundedBody` | `BoundedBody`, `read()` | api.mission |
| `api.DirectorRequests` | `parse()`, `blank()`, `asDirector()`, `originOf()`, `missionOf()` | api.committee, api.delivery, api.demo, api.iam, api.mission |
| `api.FileResponses` | `FileResponses`, `SANDBOX`, `head()`, `of()`, `disposition()` | api.delivery; tests in api.delivery |
| `api.NoNullItems` | `NoNullItems` | api.committee, api.delivery, api.iam, api.mission |
| `api.mission.MeetingViews` | `of()` | api.committee |
| `api.mission.MissionViews` | `of()` | api.committee, api.delivery; tests in server, api.demo |
| `api.mission.Scorecard` | `sum()` | api.cost |
| `app.asset.MarketplaceStore` | `NAME`, `REPO`, `addArgument()` | test seam only: tests in app.delivery |
| `app.boot.LiveStartup` | `resume()` | app.committee, app.mission, runtime; tests in api.committee, app.core, app.mission |
| `app.committee.MeetingVoices` | `beforeLaunch()`, `closeOut()` | test seam only: tests in api.committee, app.mission |
| `app.committee.RetroDelegations` | `RetroDelegations`, `holdForRetro()` | app.boot |
| `app.committee.Retrospectives` | `holdsRetro()`, `run()` | api.committee, api.event, app.boot; tests in server, api.committee, api.mission, app.mission |
| `app.committee.Staffing` | `closeInterrupted()`, `Facts`, `facts()` | api.event, api.mission, app.boot; tests in server, api.committee, api.mission, app.demo, app.session |
| `app.core.BoundedProcess` | `MAX_OUTPUT`, `builder()`, `run()` | app.delivery, app.session; tests in server, api.delivery, api.mission, app.delivery |
| `app.core.CliProcess` | `CliProcess`, `Outcome`, `run()` | app.asset, app.delivery |
| `app.core.OfficeSettings` | `sandboxed()`, `pinMandatoryForTests()`, `DIRECTOR_PROFILE` | api.cost, api.mission, api.office, app.committee, app.mission, app.session; tests in api.mission, api.office, app.session |
| `app.delivery.MissionReports` | `producerVerdict()`, `firstLine()` | api.delivery; tests in app.session |
| `app.delivery.NoteArchive` | `closeImplemented()` | app.boot |
| `app.delivery.ProofFolders` | `ProofFolders`, `delete()` | app.session |
| `app.delivery.ProofShell` | `works()`, `run()` | app.session; tests in api.delivery, api.mission |
| `app.delivery.PullRequestGit` | `repositoryOf()` | runtime; tests in app.session, runtime |
| `app.demo.SimulatedCrew` | `CHRIS`, `DARIUS` | test seam only: tests in api.demo, app.committee, app.mission, app.session |
| `app.mission.AgentReservations` | `reserveForMeeting()` | app.committee, app.delivery; tests in api.delivery, api.mission, app.committee |
| `app.mission.DependencyMerge` | `merge()` | app.session |
| `app.mission.DirectorAnswers` | `raisedBudget()`, `adoptedForImplementation()`, `answered()` | app.boot; tests in api.mission |
| `app.mission.Dispatcher` | `nudge()`, `staffChanged()`, `blocked()`, `produce()` | app.boot, app.committee, app.delivery, app.session; tests in app.boot |
| `app.mission.DockerQuestion` | `prepare()`, `askWithin()` | app.session |
| `app.mission.HoldRulings` | `ruleOnPlanLimit()`, `wakeUpOf()` | app.boot; tests in app.core |
| `app.mission.LiveMissions` | `reserve()` | api.mission, api.office, api.session, app.committee; tests in api.committee, api.delivery, api.mission, app.committee |
| `app.mission.MissingTools` | `askWithin()` | app.session; tests in api.mission |
| `app.mission.MissionFactory` | `chosen()` | test seam only: tests in app.session |
| `app.mission.MissionHolds` | `held()`, `waiting()` | app.boot, app.committee, app.session |
| `app.mission.MissionPermissions` | `CHANGES`, `denyAll()`, `allowReviewShell()`, `directorRefusesShell()`, `dropLeftoverReviewShells()`, `remove()` | app.session; tests in app.session |
| `app.mission.MissionSigns` | `listen()` | app.boot |
| `app.mission.RetryDesk` | `ask()` | app.boot, app.committee, app.session |
| `app.mission.Staff` | `active()` | api.mission, app.committee, app.session; tests in api.committee, api.mission |
| `app.mission.StaffProvisioner` | `PLANNER`, `RESEARCHER`, `staff()`, `requirePresent()`, `ensureResearcher()`, `hireIfMissing()` | app.committee, app.session; tests in api.mission |
| `app.mission.TaskRunner` | `TaskRunner` | app.committee, app.session |
| `app.mission.Work` | `Work` | api.mission, app.committee, app.core, app.delivery, app.session, iam; tests in api.delivery, app.core, app.session |
| `app.session.BriefTemplate` | `BriefTemplate`, `named()`, `with()`, `render()` | app.committee |
| `app.session.Briefs` | `measuredTools()` | api.asset, app.asset, app.committee; tests in api.committee, api.mission, api.office, app.mission |
| `app.session.HeldSessions` | `of()` | app.committee |
| `app.session.MeetingHold` | `MeetingHold`, `spec()`, `toolRunning()` | app.committee |
| `app.session.RetroBriefs` | `RetroBriefs`, `facts()` | test seam only: tests in app.cost, app.demo |
| `app.session.SessionGate` | `spec()`, `report()`, `runUnheld()`, `runTurn()` | app.committee; tests in app.mission |
| `app.session.Unheld` | `Unheld`, `heard()`, `why()` | app.committee; tests in app.committee, app.mission |
| `app.session.UnnamedLaunch` | `UnnamedLaunch`, `idOf()` | app.boot |
| `app.workspace.AgentWorktrees` | `path()`, `ready()`, `research()`, `recordKnown()` | app.boot, app.committee, app.mission, app.session |
| `app.workspace.BuildArtefacts` | `BuildArtefacts`, `KINDS`, `untrackedKinds()`, `isOne()` | app.delivery, app.mission |
| `app.workspace.DeliveryDestination` | `DeliveryDestination`, `resolve()`, `check()`, `realOfNearest()`, `refused()` | app.delivery, app.mission |
| `app.workspace.MissionBranch` | `MissionBranch`, `checked()` | app.mission |
| `app.workspace.StaleIndexLocks` | `StaleIndexLocks`, `clear()` | app.boot |
| `app.workspace.Workspaces` | `previousSessionsStopped()`, `exists()` | api.asset, api.event, api.journal, api.workspace, app.boot, app.committee, app.delivery, app.demo, app.mission; tests in api.delivery, api.workspace, app.mission, app.session |

In the tests (20): `ApiTestBase` and its token, `DispatchFixture` and its office, `RetroApiTest`, `FileResponsesTest.PNG`,
`LiveMissionTest.gitRepository` and `run`, `OfficeServiceTest.MemoryJournal`, `StallTest.gitRepository`,
`DeliveryDestinationTest.link`, and three overrides in `DispatchWakeTest` (a public method stays public when
overridden).
