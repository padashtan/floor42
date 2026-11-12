# Quality suite

Static analysis and test quality, opt in. The normal `./mvnw verify` never runs any of it. Reports only: nothing
fails the build yet (no threshold until the director sets one).

| Tool | What | Where it lands |
|------|------|----------------|
| JaCoCo 0.8.16 | line and branch coverage of the unit and Quarkus tests (same surefire JVM) | `*/target/site/jacoco/jacoco.xml`, aggregate `floor42-coverage/target/site/jacoco-aggregate/jacoco.xml` |
| PMD 7 + CPD (plugin 3.28.0) | `tools/quality/pmd-ruleset.xml` | `*/target/pmd.xml`, `*/target/cpd.xml` |
| SpotBugs 4.10.5 + FindSecBugs 1.14.0 | `tools/quality/spotbugs-exclude.xml` (generated code only) | `*/target/spotbugsXml.xml` |
| PIT 1.30.2 (JUnit 5 plugin 1.2.3) | mutation tests on the security code, on demand | `*/target/pit-reports/` |
| ESLint 10 (floor42-web) | typescript-eslint recommended + vue essential | `floor42-web/target/eslint-report.json` |
| SonarQube Community | all of the above, in one place | http://127.0.0.1:9000 |

Vulnerability scanning (CodeQL, Semgrep, Trivy, ZAP, Scorecard, hadolint) is in [security-scanning.md](security-scanning.md).

## Run

```bash
make quality        # ESLint report, then ./mvnw -Pquality verify (about 20 min: the server tests run under JaCoCo)
make mutation       # PIT on the targeted classes (about 10 min)
```

Without make: `cd floor42-web && npm run lint:report`, then `./mvnw -Pquality verify`
(`-Dspotless.check.skip=true` to skip the format check, `-Dquarkus.quinoa=false` to skip the web build).
A failing test does not stop the profile (`maven.test.failure.ignore`): the reports are still written.

## Fuzzing (Jazzer)

Coverage-guided fuzzing of the parsers and guards that read untrusted text (`jazzer-junit`, test scope, `@FuzzTest`
methods tagged `fuzz`). Each target states a property the output must keep, not only "does not throw":

| Target | Property |
| --- | --- |
| `NetworkConfigGuardFuzzTest` | on `git config --list -z --show-scope` output built from parts, the first local or worktree key in a refused family is reported, nothing else is |
| `ShellPatternsFuzzTest` | `gitPush`, `claudeConfig` and `stopsJava` agree with the frozen old regular expressions of `ShellPatternsDifferentialTest`; a long line is read within 10 s |
| `ReviewClaimsFuzzTest` | a reservation value is stored character for character; any text is read within 10 s |
| `SafeUrlsFuzzTest` | a built address keeps the catalogue's scheme, host and port, no user info, an allowed prefix, no `..` nor encoded dot or slash |
| `SecretMaskingFuzzTest` | a planted credential never survives `maskText`; the replay never carries the session token and stays within its clip bound |

In a normal `./mvnw verify` they run in regression mode: only the seeds under
`floor42-server/src/test/resources/<package>/<Class>Inputs/<method>/` are replayed, in a few seconds. They run in their
own surefire execution (`fuzz`) because Jazzer attaches its agent to its own JVM, which `-XX:+DisableAttachMechanism`
of the shared test JVMs forbids.

Real fuzzing, one target after the other (Jazzer runs one fuzz test per JVM):

```bash
make fuzz                                     # every target, 120 s each
FUZZ_SECONDS=300 make fuzz FUZZ_TARGET=SafeUrlsFuzzTest   # one class, or Class#method, 5 min
```

Without make: `bin/floor42-fuzz [filter]` (`FUZZ_SECONDS`). By hand, one target:
`JAZZER_FUZZ=1 ./mvnw -pl floor42-server -am test -Dtest='Class#method' -Djazzer.max_duration=120s
-Dsurefire.failIfNoSpecifiedTests=false -Dquarkus.quinoa=false -Dspotless.check.skip=true`.
A finding is written as a reproducer file in the `...Inputs/<method>/` folder: keep it as a seed, then fix the code or,
when the property was too strict, the oracle (explain why in a comment). The corpus (`floor42-server/.cifuzz-corpus`)
is ignored by git. The nightly `quality` job fuzzes every target for 60 s each.

First runs (2026-11-09, 150 s per target) found three defects, fixed, and left their reproducers as seeds:

- `ShellPatterns.stopsJava` and `ShellGuard.STOP_VERB`: the JDK's `\b` reads back over a whole run of combining marks at
  each position, so 20000 of them (an agent's command) took 40 s of CPU. Now ASCII look-arounds.
- `SecretMasker`: `token: Bearer abc12345xyz` took `Bearer` as the value and left the credential in clear.
- `ReviewClaims`: `RESERVATION - text with a colon: more` read the colon as the separator and kept the dash in the
  stored text.

One divergence was kept: `gitPush` and `claudeConfig` also refuse a verb followed by a combining mark, which the old
`\b` read as part of the word (stricter, so the property is "never fewer refusals").

## SonarQube, locally

```bash
docker run -d --name sonarqube -p 127.0.0.1:9000:9000 -e SONAR_ES_BOOTSTRAP_CHECKS_DISABLE=true sonarqube:community
```

`SONAR_ES_BOOTSTRAP_CHECKS_DISABLE=true` is for a dev box only (it skips the Elasticsearch host checks).
Open http://127.0.0.1:9000 (first login admin/admin, you are asked to change it), create a project with the key
`floor42`, generate a token (My Account, Security) and keep it in your environment, never in a file:

```bash
export SONAR_TOKEN=...            # PowerShell: $env:SONAR_TOKEN = '...'
./mvnw -Pquality verify sonar:sonar
```

The host, the project key, the sources (`floor42-web/src` for the root module, `src/main/java` for the Java modules),
the JaCoCo and ESLint report paths and the exclusions (generated code, `target`, `node_modules`, `dist`, test
resources) are in the `quality` profile of the root `pom.xml`. The token is read from `SONAR_TOKEN` only. Run
`make quality` first, or Sonar sees no ESLint report. Vitest coverage for the TypeScript side is not wired (it needs
`@vitest/coverage-v8`).

`tools/quality/sonar-gate.sh` creates the `floor42` project and the "Floor42 way" quality gate on a local SonarQube (idempotent; `SONAR_URL` defaults to http://localhost:9000). The token is read from the file `~/.floor42-sonar-token` and never printed. The conditions: Sonar way on new code (reliability, security and maintainability ratings A, new coverage at least 80, duplication at most 3 %, every new hotspot reviewed) plus, on overall code, coverage at least 75, no vulnerability and a security rating of A.

## CI

The nightly workflow has a `quality` job: ESLint report, `-Pquality verify`, PIT, then the reports as the
`quality-reports` artifact (14 days). It then fuzzes every target for 60 s (`bin/floor42-fuzz`). No SonarQube server in CI.

## Assurance chain (node Q9)

The README table ("Quality and security assurance") lists each tool with its command and oracle. This is how the layers
are ordered and wired.

Order, as in the assurance graph, locally (`make assurance`, run without `-j`; `make -k assurance` goes on after a
failed layer) and in CI (`.github/workflows/assurance.yml`, `workflow_dispatch` and a weekly schedule, never on a push):

| Stage | Local targets | CI job (`needs:` the stage before) |
|-------|---------------|------------------------------------|
| 1 build | `build` | `build` (uploads the packaged app as the `quarkus-app` artifact) |
| 2 static | `quality`, `semgrep` | `semgrep`; `codeql` when `public-repository` is true |
| 2 dependencies and secrets | `security-static` (OSV-Scanner, gitleaks, Bearer), `trivy` | `static-scan` (calls `static-scan.yml`), `trivy` with `TRIVY_EXIT=1` |
| 2 supply chain | `hadolint`, `actionlint` | `hadolint`, `actionlint`; `scorecard` when `public-repository` is true |
| 3 tests, mutation, fuzzing | `test-fast`, `test-slow`, `mutation`, `fuzz`, `e2e` | `verify` (Linux), `verify-windows`, and `nightly-layers` (calls `nightly.yml`: slow tests, the quality profile, PIT, Jazzer; `e2e` runs on the developer machine) |
| 4 dynamic and conformance | `zap`, `oidc-conformance` | `zap` (downloads `quarkus-app`, starts the daemon through `zap-scan.sh`), `oidc-conformance` (calls `oidc-conformance.yml`, which starts the suite and the daemon) |
| 5 summary | none | `summary`: the result of every job in the step summary, red if one failed or was cancelled (skipped is fine) |

Wiring notes:

- `workflow_call` was added, and nothing else changed, to `static-scan.yml`, `nightly.yml`, `oidc-conformance.yml`,
  `codeql.yml` and `scorecard.yml`. `e2e.yml` runs by hand only.
- In CI the PMD, CPD, SpotBugs, JaCoCo and ESLint reports come from the `quality` job inside `nightly.yml`, which runs
  them with PIT and Jazzer in one Maven setup. The assurance workflow does not run them a second time.
- CodeQL and Scorecard need a public repository (see "When the repository is private" in
  [security-scanning.md](security-scanning.md)); they are skipped otherwise.
- The workflow's top-level permission is `contents: read`. Only the CodeQL and Scorecard jobs add what those actions
  need, at job level. Actions are pinned by commit SHA; the scanners are pinned by tag and digest in `tools/quality/`.
- `make actionlint` reports no finding on `assurance.yml` (actionlint 1.7.12, image pinned in
  `tools/quality/actionlint-run.sh`). The workflow itself has not been run on GitHub.

Thresholds, in one place. Fails the build: `spotless` format, any test, a Jazzer crash or broken property, OSV-Scanner
(advisory not in `osv-scanner.toml`), gitleaks (secret not in `.gitleaks.toml`), Bearer (finding not in `bearer.ignore`),
hadolint (any warning), actionlint (any finding), Trivy with `TRIVY_EXIT=1` (HIGH or CRITICAL not in `.trivyignore`),
the OIDC conformance run (a module that differs from its expected verdict in `tools/conformance/run.py`). Report only:
JaCoCo, PMD, CPD, SpotBugs, ESLint (currently 0), PIT (`mutationThreshold` 0), Semgrep, ZAP (unless `ZAP_FAIL=1`).
SonarQube has no gate recorded and no CI job. Figures with a date are in the baseline sections above and in
[security-scanning.md](security-scanning.md); the conformance results are in [oidc-conformance.md](oidc-conformance.md).

### Schemathesis

Property-based testing of the API against the served OpenAPI document, by `make api-fuzz`
(`tools/security/api-fuzz.sh`): a throwaway daemon whose data, home and working directory all sit in one temp folder,
signed in through its printed link, then a run without a session. It runs nightly in the `api-fuzz` job of
`security-scan.yml`, not in `assurance.yml`. Results, fixes and accepted deviations are in
[security-scanning.md](security-scanning.md).

## Choices

- PMD starts from the PMD 7 quickstart set. Three rules are dropped: `ControlStatementBraces` (2112 hits, brace-less
  guard clauses are the code style), `AvoidUsingVolatile` (a volatile immutable snapshot is the lock-free idiom),
  `GuardLogStatement` (lazy-argument logger).
- SpotBugs excludes Quarkus-generated classes, and the SECURITY false positives listed in "Security triage" (each with its reason in `tools/quality/spotbugs-exclude.xml`). `EI_EXPOSE_REP*` (975 hits on records and views) is left in for
  now: decide when the first threshold is set.
- PIT targets `dev.floor42.server.iam.*`, `security.*`, `runtime.Sandbox*` and, in the domain, `PermissionRule*` and
  `SessionRisk*`. PIT's mutation JVM does not boot a Quarkus app, so the eight `@QuarkusTest` classes of those
  packages are excluded (`IdentityWatchTest`, `KeycloakSignInTest`, `ProcessBoundHookTest`, `ResourceBrokerTest`,
  `RevocationGuaranteesTest`, `BrowserRequestsTest`, `LocalAccessGuardTest`, `SessionsApiTest`). A class tested only
  by them (`LocalAccessGuard`, `KeycloakSignIn`, `ResourceCatalogue`...) scores 0 and counts as "no coverage": read
  that as "not covered by a plain unit test", not as "untested". Keep that list in step with new `@QuarkusTest`s
  (in `floor42-server/pom.xml`).
- The aggregate JaCoCo report lives in `floor42-coverage`, a pom-only module listed in the `quality` profile only.
- Tests tagged `slow` are not run (as in the normal build).

## Baseline (2026-11-09, the branch that became v1.0.0)

Tests under JaCoCo: domain 484 green; server 1141 run, 4 failed and 3 skipped in the last run (the characterisation
tests `BriefsCharacterisationTest` and `ResponseBodiesCharacterisationTest` fail in this worktree with or without
JaCoCo; the 30 s "Timed out waiting for: the plan" failures of `DockerQuestionApiTest`, `GraphNodesApiTest` and
`MissingToolsApiTest` showed up only on a loaded machine).

| | Domain | Server | Aggregate |
|-|-------:|-------:|----------:|
| JaCoCo line | 97.2% | 70.5% | 77.1% |
| JaCoCo branch | 82.8% | 59.3% | 65.9% |
| PMD violations (P1 / P2 / P3 / P4) | 93 (0 / 0 / 67 / 26) | 426 (3 / 3 / 402 / 18) | 519 |
| CPD duplications | 1 | 0 | 1 |
| SpotBugs (Medium and up) | 120 | 597 | 717 |
| ESLint (floor42-web) | | | 0 |

CPD and ESLint after node Q4 (`q4-dup-lint`): the one CPD block left is the 10 lines that the frozen check readers of
`WorkflowDocumentV2` and `WorkflowDocumentV3` share, kept apart on purpose (a released reader is never changed, and
their refusal texts differ). ESLint measured 10 (9 errors, 1 warning) in the worktree, now 0 without a disabled rule,
but for one justified `no-explicit-any` line in `e2e/seed.ts` (the screenshot seeds patch the daemon's untyped JSON).

SpotBugs by category, after the security triage below (`sec-findings`): domain MALICIOUS_CODE 108, CORRECTNESS 10,
PERFORMANCE 1, BAD_PRACTICE 1, SECURITY 0 (was 2); server MALICIOUS_CODE 517, STYLE 63, BAD_PRACTICE 11,
MT_CORRECTNESS 6, SECURITY 0 (was 30).

PIT (mutants killed or timed out / generated): domain `PermissionRule` 67/67 (100%), `SessionRisk` 37/38 (97%);
server 862/1810 (48%), 803 mutants with no coverage, test strength 86%. Per class: `IdTokenCheck` 100%,
`SandboxEnv` 100%, `IdentityAudit` 89%, `AgentTokenVerifier` 87%, `Jwts` 87%, `KeycloakClient` 83%, `SafeUrls` 82%,
`IdpExchanges` 80%, `SecretVault` 77%, `WindowsSocketOwners` 77%, `ReplaySanitizer` 72%, `ConfigGuard` 72%,
`SandboxToolkit` 71%, `Sandbox` 65%, `SandboxProbe` 66%, `Delegations` 65%, `SessionRegistry` 49%, `IamStatus` 45%,
`SecretMasker` 30%, `ResourceBroker` 10%, `IamSessions` 9%, `SessionToken` 6%, `StepUpApprovals` 4%, and 0% for
`LocalAccessGuard`, `KeycloakSignIn`, `DirectorSignIn`, `ProcessHardening`, `ResourceCatalogue`, `SessionIdentity`,
`IdentityWatch`, `PreSignIn`, `DirectorTokens`.

## Security triage (`sec-findings`, 2026-11-09)

32 SECURITY findings (2 domain, 30 server), all decided. Fixed, each with a test that failed before:

- ReDoS, measured on 50 000 character inputs before the fix: the `git push` guard overflowed the stack (a guard that
  throws), the `claude plugin` guard too, the Java kill guard took 0.7 to 1.3 s, a long run of blanks in a review line
  took seconds (cubic backtracking, 7 s at 3 000 characters), `CHANGE` after a long run of stars was quadratic, and a run
  of slashes made `ReplaySanitizer.root` take 18 s. Now: `runtime/ShellPatterns` reads the command word by word (the
  `git push` and `claude plugin` tests refuse exactly what the old expressions refused, the Java kill test all of it and
  `cmd /ctaskkill` and the `gps`/`spps` aliases too; `ShellPatternsDifferentialTest` holds the old expressions and
  compares on adversarial lines and a seeded random corpus), `ReviewClaims` matches on a copy with blank runs squeezed
  to 32 (tabs read as spaces) and stores the text cut from the original, and finds `CHANGE` without a leading
  quantifier, folder patterns start at the first separator of a run. Tests: `ShellPatternsTest`, `RegexBudgetTest`,
  `ReplaySanitizerTest`, `LegacyNamesTest` (200 ms each).
- `ShellGuard` is a tripwire, not a boundary: with the sandbox off, `git --git-dir .git push`, quoting, aliases,
  variables and a PowerShell no-break space bypass it. The boundary is the sandbox and the permission table.
- A repository whose own settings redirect a push (agents can write `.git/config` without the sandbox): `GitProcess`
  now refuses `push`, `ls-remote`, `fetch` and `pull` when the repository or worktree sets `core.sshCommand`,
  `core.gitProxy`, `core.askpass`, `core.hooksPath`, any `credential.*`, `http.*` or `https.*` (proxy, TLS, resolve,
  extra header), `protocol.*`, any `gpg.*`,
  `remote.*.pushurl/receivepack/uploadpack/vcs/proxy/proxyauthmethod`, any `remote.<name>.*` whose name holds a
  `:`, `/` or `\` (git takes a remote named like the URL Floor42 pushes to, so its `url` would win), any `url.*`
  (insteadOf) or an include (`NetworkConfigGuard`; global and system settings are untouched, and nothing is overridden because an empty
  `sshCommand` would break pushes; Floor42 writes none of these into a repository), and the push goes to the URL of
  the remote as previewed, not to its name. The settings are read with `git config --list -z --show-scope`, so an `=`
  in a quoted subsection name does not hide a key. Known gap: the check and the push are two git processes, so a
  setting written between them is honoured; only reachable with the shell sandbox off (with it on, the agent cannot
  write the configuration). A `pre-push` hook in `.git/hooks` would run on a push (same condition), so it is
  mitigated: the one PR push passes `--no-verify` when no global or system `core.hooksPath` is set (every pre-push
  hook then lives in `.git/hooks`, where an agent can write, so a planted hook and the director's look the same); with
  a global one git never reads `.git/hooks`, and the director's hooks stay on. The push also carries the remote URL the
  director previewed (`PullRequestRequest.remoteUrl`): a `remote.origin.url` rewritten since is refused as
  `PREVIEW_STALE`, and the push and gh use only the previewed URL. Availability cost: a remote named with `/`, `:` or
  `\`, or a repository with its own `core.hooksPath` (husky, lefthook), refuses PR pushes; the refusal names the key
  (`the repository sets <key>`, also in the daemon log). Tests: `NetworkConfigGuardTest` (real git, hostile config), `ArgumentInjectionTest`. Not done: `GIT_SSH_COMMAND` in the daemon's own environment is the director's, so it is kept.
- `gh repo view <repository>` takes the repository positionally and it comes from the remote URL: `repositoryOf` now
  accepts only a lower case host, owner and name with no leading dash and no odd character.
- Option injection (COMMAND_INJECTION, argument lists were already shell free): a remote named like an option reached
  `git push` and `git ls-remote` (`PullRequestGit` now refuses it and ends options with `--`), and a marketplace or plugin
  name, repo or folder starting with a dash reached `claude plugin ...` (`MarketplaceStore.NAME`, `REPO`,
  `addArgument`). Test: `ArgumentInjectionTest`.
- `ProofSnapshot.copy` refuses a name with a separator or `..` (defence in depth: callers pass identifiers). Test:
  `ProofSnapshotTest`.
- `Jwks` (USO_UNSAFE_METHOD_SYNCHRONIZATION): the fetch lock is private; code holding the instance's monitor could stall
  every key miss and the 60 s refresh. Test: `JwksTest`.

False positives, excluded with a reason: REDOS on `DocumentCheck` and `Workflow` (key length checked first, one
unambiguous separator), `PullRequestGit`, `LegacyNames` and the path patterns of `ReplaySanitizer` (linear, tested);
`ReviewClaims` (blank runs squeezed first). PATH_TRAVERSAL_IN: `SecretVault` (name checked against `[a-z0-9-]{1,80}`,
`..`, absolute and NUL names tested), `ResourceCatalogue` (fixed file names), `MarketplaceStore` (Claude Code's own
registry, read only, plugin paths checked beneath the clone), `ProofSnapshot` (paths pass `ProofCopyRules`, no link, beneath
the root). COMMAND_INJECTION: `BoundedProcess` (the director's check recipe, run in the sandbox: it is the feature),
`ClaudeCli`, `Deliverables` (absolute folder, only argument), `GhCli` (the repository is validated, see above; the other
values follow a flag or are numbers),
`ClaudeCodeProbe`, `ClaudeCodeRuntime`, `GitProcess`, `SandboxProbe`, `PosixPty`, `DirectorSignIn` (own URL, no shell).
UNSAFE_HASH_EQUALS (`Workshop`, `PendingRestore`): git and file digests, not secrets. UNENCRYPTED_SOCKET and
UNENCRYPTED_SERVER_SOCKET (`SocketOwners`, `DataChecks`): loopback probes. Every exclusion is narrowed to its method and
bug pattern in `tools/quality/spotbugs-exclude.xml`, so a new caller in the same class is still reported.

Not audited: every other caller of `GitProcess` was read for option injection only where an agent or a repository
configuration can choose the value (branch names cannot start with a dash in git itself).

## Top findings to look at first (items 1, 2, 5 to 8 below are done, see above)

1. `runtime/ShellGuard.java:33` and `:42`: ReDoS on the `git push` guard, a nested quantifier run over
   agent-controlled command lines. A hostile line can stall the guard that enforces ADR-0036.
2. `security/ReplaySanitizer.java:56`, `:61`, `:64`: ReDoS patterns applied to transcript text.
3. `iam/IamSessions`, `iam/StepUpApprovals`, `security/SessionToken`: 9%, 4% and 6% mutation score from plain unit
   tests; their guarantees rest on the Quarkus tests only.
4. `security/LocalAccessGuard` (119 mutants, none killed by a unit test): the main request guard has no plain test.
5. `app/PullRequestGit.java:23-24`, `app/ReviewClaims.java:41`, `config/LegacyNames.java:24`: more ReDoS candidates.
6. `iam/SecretVault.java:80` and `iam/ResourceCatalogue.java:164`: PATH_TRAVERSAL_IN, check the path is confined to
   the vault or catalogue folder.
7. `runtime/GitProcess.java:226`, `:268`, `app/GhCli.java:54`, `app/Deliverables.java:232`: COMMAND_INJECTION on
   non-constant arguments; confirm each argument is a list element, never a shell string.
8. `iam/Jwks.java:75`: USO_UNSAFE_METHOD_SYNCHRONIZATION on the key refresh.
9. (done, see "Correctness round") 42 `PreserveStackTrace`, 21 `EmptyCatchBlock`, 18 `AvoidCatchingThrowable` (PMD): lost causes in error paths.
10. (done, see "Correctness round") `app/SessionReach.java:136`, `iam/WindowsSocketOwners.java:53`, `:79`: `ReturnEmptyCollectionRatherThanNull`,
    callers risk a null dereference (SpotBugs also reports 52 `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` in the server).

The `UNSAFE_HASH_EQUALS` hits (`Workshop.java:311`, `PendingRestore.java:234`) compare git and file digests, not
secrets: false positives.

## Mutation round 2 (branch `q2-mutation`)

Plain JUnit tests, no Quarkus boot, so PIT can run them: the handlers are driven through `FakeRc` (a Vert.x request and
response as data, in `floor42-server/src/test/java/dev/floor42/server/iam/`), the fake Keycloak and a mutable clock.
Whole target list, one PIT run: 862/1810 (48%) before, 1235/1813 (68%) after, measured before `SecretMasker` and
`SessionRegistry` got their tests (below).

| Class | Before | After | Tests |
|-------|-------:|------:|-------|
| `StepUpApprovals` | 4% | 94% | `StepUpApprovalsTest` |
| `SessionToken` | 6% | 94% | `SessionTokenTest` |
| `IamSessions` | 9% | 90% | `IamSessionsTest` |
| `LocalAccessGuard` | 0% | 97% | `LocalAccessGuardHandlersTest` |
| `KeycloakSignIn` | 0% | 98% | `KeycloakSignInHandlersTest` |
| `DirectorSignIn` | 0% | 87% | `DirectorSignInTest` |
| `SecretMasker` | 30% | 100% | `SecretMaskerTest` |
| `SessionRegistry` | 49% | 93% | `SessionRegistryRevocationTest` |

No production code changed and no real bug found. Over-masking is intended and now pinned: `SecretMasker` masks
"the token count is low" (the word `token` followed by a word), the safe side its Javadoc states.

Still under 80% in the last full run (not done): `ResourceCatalogue`, `IdentityWatch`, `SessionIdentity`,
`ProcessHardening`, `ResourceBroker` (9%), `DirectorTokens` (22%), `IamStatus` (45%), `AgentClients` (52%),
`Sandbox` (64%), `SandboxProbe` (67%), `Delegations` (68%), `Jwks` (68%), `SandboxToolkit` (70%), `ReplaySanitizer`
(72%), `ConfigGuard` (72%), `PinnedClients` (73%), `SecretVault` (76%). The `@QuarkusTest`-only ones among them need
the same treatment as the guard (constructor access plus fakes).

## Mutation round 3 (branch `q2b-mutation`)

Same method as round 2 (plain JUnit, fake Keycloak, no Quarkus boot, no production change). Score is PIT killed or
timed out over generated, run one class at a time:

| Class | Before | After | Tests |
|-------|-------:|------:|-------|
| `ResourceBroker` | 9% | 82% | `ResourceBrokerUnitTest` (real `SafeUrls` and `PinnedClients`, a local HTTP server as the resource) |
| `DirectorTokens` | 22% | 94% | `DirectorTokensTest` |
| `IdentityWatch` | 0% | 88% | `IdentityWatchPassTest` |
| `SessionIdentity` | 0% | 90% | `SessionIdentityTest` |
| `ResourceCatalogue` | 0% | 95% | `ResourceCatalogueTest` |
| `IamStatus` | 45% | 88% | `IamStatusBackgroundTest` (with the existing `IamStatusTest`) |
| `AgentClients` | 52% | 95% | `AgentClientsDismissalTest` |
| `Delegations` | 68% | 92% | `DelegationsGuaranteesTest` |
| `PinnedClients` | 73% | 96% | `PinnedClientsPinTest` |

Not done: `ProcessHardening` (0%). It only does something through `prctl` on Linux (a native call through the foreign
function API); on a Windows or macOS run every branch that matters is out of reach, so it needs a Linux CI job, not a
unit test. No real bug found. Notes for whoever reads the surviving mutants: `ResourceBroker` reports "did not answer"
rather than "did not answer in time" when the HTTP client's own timeout fires before the broker's (same code, only the
message differs); `IamSessions.stopRevoked` calls `stop` again at each pass for a session that is still registered,
which is harmless because the real `stop` unregisters it.

## Mutation round 4 (branch `q2c-mutation`)

Same method (plain JUnit, no Quarkus boot, no production change, no real bug found), PIT run one class at a time with
`-DtargetClasses=... -DtargetTests=...`:

| Class | Before | After | Tests |
|-------|-------:|------:|-------|
| `ConfigGuard` | 72% | 97% | `ConfigGuardTest` (verify, foreign files, datasource values) |
| `Jwks` | 69% | 93% | `JwksTest` (recorded exchanges, interrupt, refetch window with a mutable clock) |
| `SecretVault` | 80% | 87% | `SecretVaultTest` (bad key size, damaged entry, folder rights) |
| `ReplaySanitizer` | 83% | 96% | `ReplaySanitizerTest` (clip limits, details cap, root order) |
| `Sandbox*` (all, one run) | 72% | 91% | `SandboxArgvTest`, `SandboxProbeReadTest`, `SandboxHostTest`, `SandboxNativeScriptTest`, `SandboxToolkitTest` |

Left, and why: `ConfigGuard.ownRoot` (`getParent() == null` never happens for a code location), `Jwks.key` second
cache lookup and the rate-limit return (the second is the same value as the mutant; the first needs a race),
`SecretVault` `OwnerOnly.restrict` on files and sub-folders (on Windows they inherit the owner-only ACL of the data
folder, so the call is invisible there; a POSIX run kills them), `SecretVault.keyProblem` unreadable key,
`ReplaySanitizer.root` `parts.length > 0` (`split` never returns an empty array), `SandboxToolkit.installCommandHere`
and `Sandbox.autocrlf` interrupt-free paths that only differ on Linux or on a git failure, `SandboxStartCheck.start`
(the launch mode in a unit test is not controllable), a few boundary mutants on size clips and log messages.
`ProcessHardening` is Linux-only (`prctl`) and was not run here.

## Correctness round (branch `q3-correctness`)

Before and after, same `-Pquality` SpotBugs (Medium and up) and PMD run on the branch that became v1.0.0:

| | Before | After |
|-|-------:|------:|
| SpotBugs, all | 717 | 44 |
| SpotBugs `CORRECTNESS` (domain) | 10 | 0 |
| SpotBugs `MT_CORRECTNESS` (server) | 6 | 2 (`Doctor`, not touched here) |
| `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` (server) | 52 | 0 |
| `EI_EXPOSE_REP` and `EI_EXPOSE_REP2` | 975 | 18 (`SandboxResource.SandboxReadiness`, not touched here) |
| PMD priority 1 and 2 | 6 | 0 |
| PMD `PreserveStackTrace` / `EmptyCatchBlock` / `AvoidCatchingThrowable` | 42 / 21 / 18 | 0 / 0 / 0 |
| PMD, all | 519 | 447 |

- `EI_EXPOSE_REP*`: every `List` and `Map` component of a record (96 files, domain events and aggregates, API views)
  is copied in its compact constructor, `x = x == null ? null : Collections.unmodifiableList(new ArrayList<>(x))`
  (`LinkedHashMap` for a map). Not `List.copyOf`: a null element is data (`OptionCode.none` is a list of nulls) and
  `Map.copyOf` loses the order the JSON bodies show. A helper method does not do either: SpotBugs only recognises the
  inline form on the accessor. A null list stays null. Excluded with a reason in `spotbugs-exclude.xml`: services
  injected by the container, the `Company` facades, `Mission.policy()`, two byte arrays, an `InetAddress`, a future.
- Real bugs: `IamStatus` counted failed checks with a volatile increment from several threads (lost counts; now an
  `AtomicInteger` under the status lock; `IamStatusBackgroundTest.concurrentMissesAreAllCounted`);
  `SessionProcesses.watch` swallowed an interrupt (the flag is restored); about 50 refusals and wrapped errors dropped
  their cause (`RefusedException`, `DomainException`, `ResourceBroker.Refusal`, `DeliveryDestination.refused` take it
  now, and `KeycloakClient.store` keeps the first failure as suppressed).
- `WindowsSocketOwners.rows` and `scan` return `Optional<long[]>`: an unreadable table stays different from a table
  with no row, so the owner is not claimed when one of the two tables could not be read
  (`WindowsSocketOwnersScanTest`). `SessionReach.names` keeps its null, which means "this init line has no such list"
  (verdict `notReported`), and carries a `@SuppressWarnings` saying so.
- False positives, excluded per method: the 10 domain `NP_NULL_ON_SOME_PATH` (`require(...)` throws before the
  dereference) and the 52 `Path.getParent()`/`getFileName()` calls (paths under the data folder, absolute, or listed
  from a directory).
- PMD ruleset: `EmptyCatchBlock` accepts a commented block (`CatchGateTest` already demands `// Handled:` and a
  reason), `AvoidCatchingThrowable` is suppressed in the classes that call native handles (`invokeExact` declares
  `throws Throwable`) and in `IdempotentCreations` (task boundary). Three `AvoidBranchingStatementAsLastInLoop`
  hits are loops that end on their first usable element; each method says so in a `@SuppressWarnings`.
- Left: `NP_BOOLEAN_RETURN_NULL` (3, tri-state `Boolean` of `CatalogueResource.offered`, `PullRequestGit.pushedAt`,
  `ProcessHardening.attachDisabled`), the `Sandbox*` and `Doctor` findings (another branch), `CatalogueResource` is
  417 lines (SizeGate warns, fails at 440; the four record constructors added 22).
- Full run on this branch: 1442 server tests, 6 failures, none from this change: `ApiDocsTest` (no entry for
  `POST /api/missions/{id}/quarantine`), `GovernorWrappersTest` (`MeetingHold.actionId`), the two characterisation
  tests (their goldens lack the `SessionRiskRaisedTest` events, already noted above) and two `PullRequestsApiTest`
  (git in this worktree answers 409).
