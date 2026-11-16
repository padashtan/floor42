# ADR-0068: A test map keyed by path, kept honest by a gate and an escape survey

Date: 2026-11-13 · Status: Proposed

## Context

The quick loop (`bin/floor42-quick`) chose Java tests by name: `<Class>Test*` for each changed main class. The
modularisation study of 2026-11-12 measured that this rule finds a test for 103 of the server's 415 main classes. The
slow tests are API tests: they call URLs, not classes, so no name and no static analysis says what they cover. The
same study measured one feature lot (delivery) at 2 min 34 against 8 min 53 for every Java test.

Since ADR-0067 the server's `app` and `api` packages are split by feature context, and the tests sit in the same
packages as the code. Surefire takes a package path as a selector (`-Dtest='dev/floor42/server/api/delivery/**'`).

| Option | What it gives | Cost |
|---|---|---|
| Keep the name rule | nothing to maintain | no test for three classes out of four; the API tests never selected |
| JUnit tags per domain | the same selection as a package path | 296 classes to annotate, and a gate to keep the tags right |
| Maven `-pl -amd` | exact for the domain module alone | any upstream change reruns the whole server |
| **A map from path globs to selectors, read from `git diff`** | one short file; a new class is covered by its package | a selection can miss a test: needs a default of "everything", a gate and a measure of the misses |

## Decision

1. One file, `tools/test-map.conf`, maps path globs to targets: Surefire patterns (`java:`), Vitest (`web:related`,
   `web:all`) and Playwright grep terms (`e2e:`, a domain tag of `e2e/fixtures.ts` or a journey's file name). The first
   rule that matches a path wins. A feature context is one rule with a `{ctx}` capture: a change in `app/<ctx>` or
   `api/<ctx>` runs the tests of both packages, so a new context needs no rule, only the line that names its journeys.
2. A core set runs with every Java selection: the gates at the root of the server's tests, the contract and
   characterisation tests, the domain module's tests.
3. The shared core (`app.core`, `app.boot`, `api` itself, `api.event`, `api.journal`, `config`, `journal`, `runtime`,
   `security`, the domain, resources, poms) calls for every Java test. A path no rule matches calls for everything.
4. The reader is one JDK-only class in the server's test sources (`TestMap`), run from its source by the scripts
   (`java .../TestMap.java select`): one implementation for the quick loop, the gate scripts and the test that checks
   the map. A YAML or JSON file would have needed a parser in bash, in PowerShell and in Java.
5. `TestMapGateTest` (in the core set) fails when a source file has no rule of its own, when a selection finds no
   test class, when a tag or a journey file does not exist, or when the share of the server's main classes that are
   targeted falls under 60 percent (64 percent today, 25 percent with the name rule).
6. Escape survey: each full gate (`tools/gate/`) asks the map which of its failing classes the quick loop would not
   have run for the commit's diff against main, prints the line and appends it to `~/floor42-escapes.tsv` (date, host,
   commit, line), green gates included. The miss rate is the gates with an escape over the gates with a failing class.
   An escape that is not a flake corrects the map.
7. The quick loop is still not a gate: the full gate runs before a merge to main.

### Quarkus boots

The gate log of 2026-11-13 counts 106 Quarkus starts in `verify`, about one per `@QuarkusTest` class, for 16 test
configurations, with 2 test JVMs as with 6. The cause is not the order of the classes: with more than one test JVM,
Surefire hands the classes out one at a time, each as its own JUnit request, and Quarkus stops its application when a
request ends. A class orderer or a Surefire `runOrder` sorts inside one request, so neither can group the classes of a
profile across JVMs. With one test JVM (`-Dfloor42.test.forks=1`) all classes are in one request and Quarkus's own
orderer already groups them by profile.

What this ADR does: the two identical proof profiles become one, the gate scripts print the count of boots, and the
comment in the pom says what is measured. The other profiles with the same settings are kept apart on purpose: each
says it needs a daemon of its own (a replaced director token, a planted journal).

What it leaves: fewer boots with several JVMs needs batches, several classes in one request per JVM (JUnit suites per
package lot, dispatched by Surefire as one unit). That changes how every test is discovered and reported, and its gain
(the study's estimate: 15 to 20 percent of the Java test time) can only be measured with several JVMs. Not done here.

A second cost, observed the same day: the Linux gate run with one test JVM on 1be11227 went red on 9 classes while
the Windows gate, with its default JVMs, was green on the same commit. With one JVM the application is reused by the
classes of a profile, and what a class leaves behind reaches the next one:

- a mission still running: `DispatchAgentsTest`, `DispatchDirectorTest`, `DispatchPlanLimitTest`,
  `DispatchRecoveryTest` and `CoreMembersApiTest` fail in `SchedulerQuiet.await` ("The dispatcher is still busy after
  PT1M");
- a fake server's request log that is not reset: `ResourceBrokerTest` finds 5 token-exchange requests where it
  expects 1;
- `RetroDelegationTest`, `MeetingHoldTest` and `ReviewShellRunTest` fail in the same run (cause not read here).

So the tests pass today because each class gets a fresh daemon, not because they clean up. Any grouping of boots,
batches included, reproduces this red until these classes reset what they share (missions stopped, request logs
cleared) before or after each class. That reset is the first step of the batching work, and the one-JVM run is the
way to check it.

### Measured, 2026-11-16

On this machine (Windows, 16 logical cores), branch on e97f7679, server and domain tests (1629 tests), wall time of
the Maven command, compilation included:

| Run | Wall | Quarkus boots | Result |
|---|---|---|---|
| Every Java test, 1 test JVM | 48 min 49 s | 15 | red: the 9 classes above, the same as the Linux gate, no other |
| Every Java test, 2 test JVMs (the CI setting) | 15 min 19 s | 106 | green |
| Quick loop, one file of `app.delivery` changed, 1 test JVM | 4 min 47 s | 3 | green |
| `ProofApiTest` and `ObjectionsApiTest`, 1 test JVM, two profiles | 1 min 45 s | 2 | green |
| The same two classes, one profile | 1 min 24 s | 1 | green |

The one-JVM run is three times slower than two JVMs although it boots seven times less: part of it is the five
classes that wait one minute for a dispatcher that never goes quiet, the rest is the lost parallelism. Fewer boots
alone do not buy time; boots saved inside each JVM of a parallel run would, which is the batching left above.

## Consequences

- A change in a feature context runs its own lot and the core set instead of one class in four found by name.
- The map is prudent: more than half of the Java commits of the history touch the shared core and still run
  everything (the study's simulation). Splitting the core finer (sandbox apart, resources by kind) is a later step, to
  take when the survey shows it is safe.
- `ObjectionsApiTest` and `ProofApiTest` share a daemon when they run in the same request (one test JVM only).
- The one-JVM run (`-Dfloor42.test.forks=1`) is not green today, for the leaks above: it is a check of test hygiene,
  not a faster gate.
- The escape ledger is a local file per machine, not versioned.

## Reopen if

- The survey shows escapes that are not flakes on more than a few gates: tighten the rule, or send the context back to
  "everything".
- A context's lot grows close to the full run: the map gives nothing for it.
