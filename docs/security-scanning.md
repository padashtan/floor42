# Security scanning

Vulnerability scanning on top of the quality suite ([quality.md](quality.md)): what each tool covers, how to run it,
where it runs in CI, and the baseline of 2026-11-09 (branch `q7-vulns`, built from the branch that became v1.0.0).

| Tool | Covers | Runs | Gate |
|------|--------|------|------|
| Dependabot, dependency review, gitleaks | dependency bumps, new vulnerable dependencies in a PR, secrets in history | existing (`dependabot.yml`, `dependency-review.yml`, `ci.yml`) | high severity in a PR; any secret |
| CodeQL (`security-extended`) | data-flow bugs in Java and in TypeScript | push to main, PRs, weekly (`codeql.yml`) | the Security tab; **public repository only** |
| Semgrep | registry packs `p/java`, `p/owasp-top-ten`, `p/jwt`, `p/secrets`, `p/typescript` plus `tools/quality/semgrep.yml` | nightly, `make semgrep` | none: SARIF artifact to triage |
| Trivy | lock files and source (`fs`), `target/bom.json` (`sbom`), Dockerfile and compose (`config`), the built image (`image`) | nightly, release, `make trivy` | release: HIGH or CRITICAL not in `.trivyignore` |
| OWASP ZAP baseline | passive scan of a running test daemon, signed in | nightly, `make zap` | none: report artifact to triage |
| Schemathesis | property-based contract test of the served OpenAPI document, signed in and without a session | nightly, `make api-fuzz` | a failure the baseline does not list turns the nightly job red; not a release gate |
| OpenSSF Scorecard | repository practices (pinning, branch protection, signed releases) | weekly (`scorecard.yml`) | **public repository only** |
| hadolint | the Dockerfile and `iam/keycloak/Dockerfile` | every CI run, `make hadolint` | any warning |
| OSV-Scanner | the SBOM (`target/bom.json`, resolved Maven tree), `floor42-web/package-lock.json`, `iam/keycloak-extensions/pom.xml` | pull requests, nightly (`static-scan.yml`), `make security-static` | any advisory not in `osv-scanner.toml` |
| gitleaks | every commit of every ref, and the working tree | same (the `secrets` job of `ci.yml` stays) | any secret not allowed in `.gitleaks.toml` |
| Bearer | Java, TypeScript, Python: code weaknesses (security report) and personal data reaching a log, a URL or a third party (privacy report) | same | any finding not in `bearer.ignore` |

Every tool runs from its official image or action, pinned (actions by commit SHA with the version in a comment, images
by tag and digest). Dependabot proposes the new pins. The scripts in `tools/quality/` are the single definition of each
command: the workflows and the Makefile call them.

## When the repository is private

CodeQL, Scorecard and the dependency review only work once the repository is public: code scanning and the dependency
review API are paid features of a private repository, and the Scorecard action refuses a private repository and
publishes to a public API. Their jobs carry `if: github.repository == 'padashtan/floor42'` (the public repository's name, true for every event): they are skipped (not red)
in the private repository and run as they are once it is public. The README
badge for Scorecard is commented out and gets uncommented once the first run has published. Semgrep, Trivy, ZAP and
hadolint have no such limit and run today.

## Run it locally

Needs Docker (Docker Engine in WSL 2 is enough) and network access. Run one heavy job at a time.

```bash
make hadolint                      # seconds
make semgrep                       # a few minutes; target/semgrep.sarif
make build && make trivy           # fs, sbom, config, then builds the image and scans it; target/trivy/
TRIVY_EXIT=1 make trivy            # the release gate: exit 1 on HIGH or CRITICAL not in .trivyignore
make zap                           # packages the daemon, starts it, signs in, scans; target/zap/
make api-fuzz                      # Schemathesis against a test daemon; target/api-fuzz/
```

Notes:

- `make trivy` uses the local Maven repository (`~/.m2`) for the `pom.xml` files. Without one, the poms are skipped (Maven
  Central answers HTTP 429 to the flood of requests Trivy makes for them, and blocks the address for 30 minutes) and the
  `sbom` scan covers the resolved Maven dependencies. `make build` writes `target/bom.json`.
- `trivy-run.sh image` builds the `floor42` target (the published image, without Claude Code) as `floor42:scan`.
- `zap-scan.sh` needs Java 25 on the machine. In WSL without Java, start the daemon in an `eclipse-temurin:25-jdk`
  container with `--network host` (the same properties as `zap-scan.sh`) and call the script with `FLOOR42_URL` and
  `FLOOR42_COOKIE=floor42_session=...`; it then only runs ZAP. That is how the baseline below was taken.
- A Semgrep rules file cannot reference registry packs, so `semgrep.yml` holds the project rules and `semgrep-run.sh`
  lists the packs.
- ZAP runs with `--network host`: the daemon refuses any Host that is not a loopback name, so ZAP must reach it as
  `127.0.0.1`.

## The ZAP scan, signed in

The scan signs in the way the browser does: `POST /auth/open` (with `X-Floor42-Request: 1`) makes the daemon print a
single-use link (`floor42.sign-in.delivery=stdout`), the script redeems it with `curl`, and hands the `floor42_session`
cookie to ZAP as a replacer rule. Checked on the baseline run: `/api/missions` answers 200 with the cookie and 401
without. Limits: the baseline is passive and its spider follows links, and the UI is a single-page application, so the
scan reached 6 URLs (the page, its assets, `robots.txt`, `sitemap.xml`). The API routes are not crawled, so
`zap-scan.sh` also runs `zap-api-scan.py` on the committed OpenAPI document, with the same cookie (see Q7b below).

Rules: `tools/quality/zap-rules.tsv`, only justified IGNOREs; everything else stays a warning. A FAIL level can be turned
on per rule, and `ZAP_FAIL=1` makes the script exit on it.

## Exceptions: `.trivyignore`

One id per line, `exp:YYYY-MM-DD` after it, a comment above saying why (not affected, with the proof; no fix yet; who
decided). An exception lapses on its date and the finding fails the release again. The file is empty at baseline.

## Baseline, 2026-11-09

| Tool | Result |
|------|--------|
| hadolint | `Dockerfile`: 3 warnings fixed or justified (see below); `iam/keycloak/Dockerfile`: clean. One info left (DL3066, user `floor42` by name). |
| Semgrep 1.184.0 (359 rules, 1272 files) | 9 findings, 0 real. Project rules: 0 matches. |
| Trivy fs (npm lock file, poms, secrets) | 0 vulnerabilities, 0 secrets |
| Trivy sbom (`target/bom.json`, 163 components) | 0 vulnerabilities |
| Trivy config (Dockerfile, compose) | 0 after the fix below (one HIGH, DS-0002, before) |
| Trivy image (`floor42` target, Ubuntu 26.04 base) | 2 HIGH, 0 CRITICAL, 58 MEDIUM, 4 LOW, all in the operating system layer; no finding in the application jars |
| ZAP baseline, signed in | 0 FAIL, 8 WARN before the rules file (63 PASS); after it, 2 alert groups left (below) |
| CodeQL, Scorecard | not run (public repository only) |

### Fixed in this task (trivial, Dockerfiles)

- `Dockerfile`: `RUN cd floor42-web && npm ci` is now `WORKDIR` then `RUN` (DL3003). The unpinned `apt-get install`
  (DL3008) and the shell-form `HEALTHCHECK` (DL3025) carry a justified `hadolint ignore` with the reason above it.
- `iam/keycloak/Dockerfile`: `USER 1000` stated (Trivy DS-0002, HIGH). The base image already runs as uid 1000, so
  nothing changes at run time; the line keeps a scan and a future base image honest.

### HIGH and CRITICAL triage

| Finding | Where | Verdict |
|---------|-------|---------|
| CVE-2026-109518, `apt` and `libapt-pkg7.0` 3.2.0 (HIGH, fixed in 3.2.1ubuntu0.1): remote code execution by hard link reuse during package download | the `floor42` image's Ubuntu base layer (`eclipse-temurin:25-jre`, pinned in `Dockerfile:47`) | Real, low exposure: `apt` runs only during the image build (`apt-get install git ca-certificates curl`, `Dockerfile:55-56`, from the Ubuntu mirrors), never in the container, which runs as uid 1000. Fix: bump the base digest when Temurin republishes (Dependabot), or `apt-get install --only-upgrade apt` at the start of that `RUN`. It fails the release gate until then: fix it or list it in `.trivyignore` with an expiry. **For the next node.** |
| DS-0002 (HIGH), no `USER` in `iam/keycloak/Dockerfile:12` | that Dockerfile | False positive in effect (the base image sets uid 1000), fixed by stating it |
| Semgrep `missing-user-entrypoint` (ERROR), `iam/keycloak/Dockerfile:12` | same | Same, fixed |
| Semgrep `react-insecure-request` (ERROR), `tools/demo/hero.mjs:42` | a developer script fetching Chrome's debugging endpoint on `http://127.0.0.1` | False positive: loopback, never shipped. Could take a `nosemgrep` with this reason. |

### Semgrep, the other findings (WARNING)

- `unencrypted-socket`, `SocketOwners.java:30,31` and `DataChecks.java:179`: plain `ServerSocket` and `Socket` bound to
  the loopback address, to test port ownership. False positives (no data crosses them, and loopback is the design).
- `dependabot-missing-cooldown`, `.github/dependabot.yml:3,9,20,27`: real but minor supply-chain hygiene. A `cooldown`
  (for example 3 days for each ecosystem) delays a bump so a hijacked release is yanked before it is proposed. **For the
  next node.**

### Q7b: fixed, and what the scans show now

- **apt HIGH**: the base digest is still the latest Temurin 25 (apt 3.2.0), so the `floor42` stage runs `apt-get install --only-upgrade apt libapt-pkg7.0` first. Trivy on the rebuilt image: 0 HIGH, 0 CRITICAL (58 MEDIUM and 4 LOW remain, all Ubuntu packages without a fix in the base). Drop the step when the base image carries 3.2.1.
- **Headers** (`SecurityHeaders.java`, test `BrowserRequestsTest`): `Permissions-Policy` denies camera, microphone, geolocation, usb, payment, bluetooth, serial, hid, midi, display-capture and the motion sensors, and keeps `clipboard-read` and `clipboard-write` for self (the copy buttons and the terminal paste; `floor42-web/src` uses no camera, microphone, location or device API; Notifications are not governed by this header). `Cross-Origin-Opener-Policy: same-origin` (no window.open in the UI). `Cross-Origin-Resource-Policy: same-origin`, except on answers carrying their own sandbox policy (deliverables, file views), whose pages have an opaque origin and would lose their own images. All three are on every answer, refusals included (the handler now runs before the access guard).
- **COEP left out**: `require-corp` would bind the PDF viewer frame and the sandboxed deliverables, and nothing needs cross-origin isolation. I did not run the browser journeys (`make e2e`) against it, so I would not enable it unchecked. ZAP 90004 is ignored in the rules file for that reason.
- **Dependabot** `cooldown: default-days: 3` on the four ecosystems. **actionlint** 1.7.12 on all workflows: no finding (`make actionlint`, a CI job).
- **ZAP API scan** (`zap-api-scan.py` on `floor42-web/src/api/openapi.json`, signed in, 202 imported URLs): safe mode by default; `ZAP_API_ACTIVE=1` attacks (nightly, on the throwaway daemon). One active run of 8 minutes (1367 URLs): 0 FAIL, no finding on an `/api` route. Left over: 83 "unexpected Content-Type" on ZAP's own probe paths outside `/api` (the single-page application answers HTML there, as designed) and the 4xx of placeholder requests, both ignored in the rules file. The baseline now shows 0 warnings.

### Open

1. CodeQL and Scorecard: read their first results the day the repository goes public; CodeQL's Java 25 support is unchecked until then.
2. COEP, if cross-origin isolation is ever wanted: run `make e2e` with it first.

## Static scanners: OSV-Scanner, gitleaks, Bearer (Q8c)

Three more open-source scanners, run by `tools/quality/static-run.sh` (the single definition of the image pins) through
`make security-static` or `sh tools/quality/static-run.sh osv|gitleaks|bearer`, and by `.github/workflows/static-scan.yml`
(pull requests, nightly, by hand; read-only token, one job per tool, reports as artifacts). Images are pinned by tag and
digest; A new tag is a manual edit of the script. Reports go to `target/static/`.
Each tool fails on a finding that no exception file covers, and each exception names a reason.

```bash
make security-static                       # the three, one after the other (Bearer alone takes 20 minutes on a slow bind mount)
sh tools/quality/static-run.sh osv         # builds target/bom.json with the CycloneDX goal when it is missing (needs JAVA_HOME)
sh tools/quality/static-run.sh gitleaks    # in a git worktree set GITLEAKS_REPO to a real clone: the .git file points outside the mount
sh tools/quality/static-run.sh bearer
```

Gitleaks scans the history with `--redact=100`, so no report holds a secret value. A real finding is reported by file,
commit and rule; the history is never rewritten by the scan.

### Exception files

| File | Format | Rule |
|------|--------|------|
| `osv-scanner.toml` | `[[IgnoredVulns]]` with `id`, `ignoreUntil`, `reason` | one advisory per block, an expiry, the reason |
| `.gitleaks.toml` | `[[allowlists]]` with `paths` and `targetRules` | proven false positives only, single files and the rules they trip |
| `bearer.ignore` | JSON by fingerprint, `comment` and `false_positive` | each fingerprint carries its reason; a moved line changes it and the finding returns |

### Baseline, 2026-11-09 (the branch that became v1.0.0)

| Tool | Result |
|------|--------|
| OSV-Scanner 2.3.5 | 163 SBOM components, 231 npm packages, 8 packages of the Keycloak extension: 18 advisories, 14 fixed by a bump (below), 4 ignored with an expiry; 0 in the product jars and in npm. |
| gitleaks 8.30.1 | Working tree: 39 findings. All false positives, 0 real secret. After `.gitleaks.toml`: 0. |
| Bearer 1.51.0 | Security report: 87 findings (20 critical, 41 high, 10 medium, 16 low), 0 real, all in `bearer.ignore`. Privacy report: 4 data subjects detected (full name, password, physical address, unique identifier), 0 rule failures, 56 rules passed. |

**OSV-Scanner.** The `pom.xml` files alone give 22 packages (the Quarkus BOM versions are not resolved), so the Maven side
reads the CycloneDX SBOM instead: 163 components, no advisory. npm: no advisory. In `iam/keycloak-extensions` (test scope)
`jackson-databind` 2.18.2 had 13 advisories and `assertj-core` 3.27.3 one (GHSA-rqfh-9r24-8c9r): bumped to 2.18.11 and
3.27.7 in the pom, `test-compile` green. The four left are transitive
dependencies of that module, a `provided` Keycloak 26.8.1 or a `test` Testcontainers: Guava 32.1.2
(GHSA-xxph-c9ww-hj94), protobuf-java 3.25.1 (GHSA-735f-pc8j-v9w8), netty-codec-dns 4.1.135 (GHSA-mfg7-5gfp-c4w3),
opentelemetry-api 1.62.0 (GHSA-ccrj-gw8r-854m). The extension jar holds only our classes, so the libraries that run are
the ones in the Keycloak server image. Upgrading means bumping Keycloak (`iam/keycloak/Dockerfile` and the pom); not
done here because it is an identity-provider upgrade (see the `kc-upgrade` checklist), not a dependency bump. Ignored
until 2027-02-09. The Keycloak image itself is not scanned by OSV-Scanner; Trivy's `image` scan covers the `floor42`
image only.

**gitleaks.** All hits are test or documentation values: fake client secrets of the throwaway test realm
(`floor42-server/src/test/java/dev/floor42/server/iam/*`, `MappersIT`, the golden files), a fake JWT in
`JournalMaskingTest`, GitHub and AWS token lookalikes planted in `fake-claude/leak.txt` and `ReplayExportApiTest` for the
masking tests, `PET_SOUND_KEY` (a localStorage
key name, in `App.vue` and `useOfficeScene.ts`) and `iam/byo-keycloak-check.sh` (the `curl -u` secrets are shell variables
filled by `openssl rand`). The allowlist names single files, per rule (21 files: 18 for `generic-api-key`, 2 for
`aws-access-token`, one each for `jwt` and `curl-auth-user`), plus the untracked `target/` and `node_modules/`
folders that the working-tree scan would otherwise read. No folder of tests is allowlisted: a new test file with a
secret is flagged. Checked on a scratch copy: a realistic fake AWS key in a new file under `src/test` is reported, with
this config. Limit: a secret pasted into one of the 21 listed files is not. The rerun over
the tree gave 0.

**Bearer.** The security report is a pattern scanner and flags code that is fine here:

- `java_lang_jwt_verification_bypass` (7): `Jwts.parse` only decodes. The checks sit in `AgentTokenVerifier.verify` and
  `IdTokenCheck.claims` (signature, algorithm, audience, expiry). The other five sites read a token Floor42 got itself
  from Keycloak, or one already verified, and decide nothing from it.
- `*_hardcoded_secret` (5): constants and French labels containing the word "secret".
- `java_lang_socket_init` (1): the loopback port-ownership probe (same as the Semgrep triage).
- `python_lang_*` in `tools/` (42 of the 87): developer scripts run by hand, never shipped. In
  `sandbox-init.py` and `sandbox-mounts.py` (4): paths and the command come from the daemon, not from a request.
- `*_observable_timing` (10): comparisons of hashes of public rows, sets, ids and UI tokens, never of a secret by a remote caller.
- `javascript_lang_insufficiently_random_values` (15): scene animation and a toast key.
- `javascript_lang_dangerous_insert_html`, `manual_html_sanitization`, `logger_leak` (3): `createElement` plus
  `textContent`; `escapeHtml` first; a page count printed by a developer script.

No token or personal data reaches a log or a URL in the privacy report. Fingerprints change when the line moves; a
finding that comes back after a refactor is triaged again, not ignored by default.

### Not done here

- The `kc-upgrade` of Keycloak to remove the four ignores before 2027-02-09.
- OSV-Scanner on the Keycloak image and Bearer's `--exit-code` tuning on a faster runner: the first Linux CI run will
  tell how long Bearer takes there (20 minutes on the WSL bind mount).

## Code scanning triage (v1.0.0)

The first CodeQL and Scorecard runs on the public repository (2026-11-12, commit `0b685fc0`) opened 90 alerts: 80 from
CodeQL (`security-extended`) and 10 from Scorecard. Each one was read with its data flow (the SARIF of the analysis)
against the code. The numbers are the alert numbers of the Security tab. "Fixed" means the code changed and a test
proves it; "justified" means the code is safe as it is, for the reason given, and was not bent to please the tool.

Every `java/*` alert below starts at a REST resource. The API is the director's own: the daemon listens on loopback
only and every `/api` route needs the director's session or token ([threat-model.md](threat-model.md), `LocalAccessGuard`).
A value CodeQL calls "user-provided" is one the director sent to his own daemon. That bounds the impact; it is not
the reason given below, which is each time the check in the code.

### Fixed

| Rule | Alerts | Where | What changed | Proof |
|------|--------|-------|--------------|-------|
| `java/polynomial-redos` | 14, 15 | `runtime/Confinement.java` (`protectedName`, `plain`) | `".*~\d.*"` with `matches()` and `"[. ]+$"` with `replaceAll()` were quadratic on a long name an agent writes in a tool call. Now a `find()` on `~\d` and a loop. The short-name check no longer misses a name that holds a line break | `ConfinementTest.aPathologicalNameIsJudgedFast` (100 000 `~0`, 200 000 dots, 5 s limit) |
| `java/log-injection` | 77 to 90 | `GitProcess`, `WorkshopDiscard`, `ProofFolders`, `ProjectInit`, `MissionScheduler`, `MeetingVoices` (4), `DirectorTerminals`, `DeliverablesHome` (3), `ReplayExportResource` | `security/LogSafe.of` replaces every control character and line separator with `?` before the value is logged. Most of these values are ids already held to `[A-Za-z0-9._:-]` by `Identifier`; a folder path, a meeting id and a terminal id are not | `LogSafeTest` |
| `java/toctou-race-condition` | 58 | `app/MissionGovernor.java` (`started`) | The resumed session was looked up in one synchronized call and the continuation recorded in a second one. Both are now in one command. The journal never removes a session, so the old code could not act on a stale answer; the single command says so in code | `ResumeApiTest` (unchanged, still green) |
| `js/incomplete-sanitization` | 11 | `floor42-web/src/i18n/events.test.ts` | The test built a regex from a translation key and escaped only the dot. It now escapes every regex character | the test itself |
| `js/unvalidated-dynamic-method-call` | 12 | `tools/demo/hero.mjs` | The answer of the local headless Chrome named a callback in a `Map`; the script now calls it only when it is a function it registered | `node --check` |

### Justified: command lines

| Rule | Alerts | Where | Why it is safe |
|------|--------|-------|----------------|
| `java/command-line-injection` | 57 | `runtime/GitProcess.java:258` | Git is started with `ProcessBuilder` and a list of arguments: no shell reads the line. The flagged values are the project folder, which is the value of `-C`, and a file path of the workshop, which `Confinement.relative` confined and which always follows `--` under `--literal-pathspecs`. Every revision or range follows `--end-of-options`. Hardened in the same change: `Git.resolve` answers empty for a revision that starts with `-`, and the pull request's diff range got its `--end-of-options`. `GitTest.aValueIsNeverReadAsAnOptionOrByAShell` |
| `java/command-line-injection` | 56 | `runtime/ClaudeCodeRuntime.java:285` | The flagged value is not on the command line: it is the working directory of the session (`ProcessBuilder.directory`), the folder `<data>/workspaces/<mission>-research` that Floor42 made. The mission id matches `Identifier` and names an existing mission, whose ids Floor42 mints (`M-<n>`) |
| `java/relative-path-command` | 59 to 76 | test sources only (`git`, `cmd`, `python3`, `unshare`, `wsl.exe`) | Tests build a throwaway repository or probe the sandbox with the tools of the developer's `PATH`, as the product does on purpose: git, Claude Code and WSL are the director's own installations, and an absolute path would differ on every machine. No test code ships in the jar |
| `java/insufficient-key-size` | 55 | `iam/JwksTest.java:44` | The test makes a 1024-bit RSA key to prove that `Jwks` skips keys under 2048 bits (`Jwks.java:132`). The weak key is the test's subject |
| `js/file-access-to-http` | 13 | `tools/demo/record.mjs:26` | The script reads the director's token from the file he names and sends it as the bearer of his own daemon (`FLOOR42_URL`, loopback by default) to record the demo tape. That is its purpose; the token is never printed and the tape holds answers only |

### Justified: paths (`java/path-injection`, 16 to 54)

No path from a request reaches the file system without one of these checks. The rule does not see them because they
are a regex in a record's constructor, a lookup in a map, or `Confinement` itself.

| Alerts | Where | The value | The check that guards it |
|--------|-------|-----------|--------------------------|
| 44, 45 | `runtime/Confinement.java:156,159` | any path of a tool call or of the director | This is the confinement check: it resolves the real path to refuse what leaves the root. It only asks whether the path exists |
| 29 to 34, 37 | `Workshop` (207), `WorkshopFiles`, `WorkshopText`, `WorkshopDiscard`, `WorktreeScanner` | a file path inside a worktree or the project | `Confinement.relative(root, path, mode)` first (`Workshop.confined`, `WorkshopDiscard.discard`, `DirectorFileReader.entry`): inside the root on the real file system, no `.git`, no protected name. `ConfinementTest`, `ConfinedPathTest` |
| 17 to 24 | `DocumentWriter`, `DeliveryDestination` | the mission id, then each delivered file's path | The mission exists (ids are minted `M-<n>`); the folder is `<destination>/<mission>` and is refused when a link leads elsewhere (`prepare`); each file goes through `Confinement.relative` and a second link check after its folder is created (`refusal`) |
| 35, 36, 38 | `Workshop.java:366,367`, `Workspaces.java:144` | mission and agent ids | One file or folder name made of ids under Floor42's data directory (`patches/<mission>-<agent>-<time>.patch`, `workspaces/<mission>-research`). `Identifier` allows no separator, and since this triage neither `.` nor `..` (`IdentifierTest`) |
| 39 to 43 | `iam/SecretVault.java` | the vault entry name, built from an agent or mission id | `file(name)` refuses a name that is not `[a-z0-9-]{1,80}`; the entry is `<data>/vault/<name>.bin` |
| 25 to 27 | `app/MarketplaceStore.java:196-199` | a marketplace name | `Plugins.refresh` requires `MarketplaceStore.NAME` and a marketplace Claude Code already knows (`markets().get`), else 404 |
| 46 to 54 | `runtime/GitProcess.java` (`checkLinks`, `checkNoPendingOperation`, `checkNoPopulatedGitlink`) | the project folder the director chose | These reads are the guard that runs before every git: they open `.git`, `commondir` and `gitdir` of the folder git is about to run in, to refuse a tampered one |
| 16, 28 | `ProjectsResource.java:303`, `ProjectToolchain.java:185` | an absolute folder of the director's machine | By design: the folder picker and the readiness check read the folder the director names on his own machine. They list folder names and read fixed toolchain files under a size cap, write nothing, and answer the director only |

### Scorecard

| Check | Alert | Status |
|-------|-------|--------|
| Pinned-Dependencies | 3, 4 | Fixed: `iam/keycloak/Dockerfile` names the digest of `quay.io/keycloak/keycloak:26.8.1`, and Dependabot watches that folder |
| Pinned-Dependencies | 6 | Fixed, though a false positive: `bin/floor42` piped the daemon's JSON answer into an inline `python3 -c` parser, which Scorecard read as download-then-run. The answer now goes through a variable |
| Pinned-Dependencies | 5 | Justified: `Dockerfile:40` installs Claude Code with npm in the `claude` stage, at the version whoever builds passes (`CLAUDE_CODE_VERSION`). A global tool has no lock file to hash, and that stage is only in the image built on the director's machine: the published image (`--target floor42`) holds no Claude Code |
| Security-Policy | 2 | Fixed: `SECURITY.md` was found but held relative links only; it now gives the absolute link of the private report form. A response time would raise the score further; it is a commitment for the maintainer to make |
| Branch-Protection | 1 | Repository setting (below) |
| Code-Review | 8 | Inherent to one maintainer: nobody else can approve a change. It moves once a second reviewer exists |
| Maintained | 9 | Inherent: the repository is younger than 90 days. Scorecard scores it by itself after that |
| CII-Best-Practices | 10 | A registration at bestpractices.dev by the maintainer, not a file |
| Fuzzing | 7 | Justified: the parsers and guards are fuzzed with Jazzer every night ([quality.md](quality.md)) and the API with Schemathesis (below). Scorecard counts OSS-Fuzz, ClusterFuzzLite and a few language-native fuzzers, not Jazzer under JUnit |

Repository settings, for the maintainer (nothing in the tree can set them):

- protect `main`: require a pull request and the CI checks, forbid force pushes and deletion, include administrators;
- enable private vulnerability reporting, which `SECURITY.md` points to;
- optionally register the project at bestpractices.dev and add its badge.

## API contract fuzzing (Schemathesis)

`make api-fuzz` (`tools/security/api-fuzz.sh`) starts the packaged daemon itself, the way `zap-scan.sh` does, and runs the
official `schemathesis/schemathesis` image (4.33.1, pinned by tag and digest) twice on `floor42-web/src/api/openapi.json`.
The script tests only the daemon it started: there is no URL override.

- **The daemon is confined to a temp folder.** `-Duser.home`, `HOME`, the working directory, the data folder, the backups
  and the Claude home are all under one `mktemp -d`, removed at the end. The port is checked free (a range that holds
  neither the installed daemon's 7420 nor the dev daemon's 7421).
- **Signed in**: every built-in check, in the examples, coverage, fuzzing and stateful phases. The session cookie reaches
  the image through its environment and a generated config file, never the command line (`--generation-with-security-
  parameters false`, otherwise Schemathesis builds its own cookie from the security scheme). Left out of this run
  (`SKIP` in the script): `/api/auth/sign-out*` and `DELETE /api/auth/sessions/{id}` (they end the session the run needs),
  `/hooks/*` and `/mcp` (the agent sessions' own door, which refuses a director's cookie with 401), and the routes that
  write at a path the client names: `/api/projects/folders`, `/api/projects/init`, `/api/terminals`, `open-folder`. A
  fuzzer should not be what proves they stay inside the temp home.
- **Without a session**, with the custom check `tools/security/schemathesis_noauth.py`: every operation but `/api/status`
  must answer 401 or 403, never a 2xx and never a 5xx. Only these others pass, because the HTTP layer answers before any
  handler runs: a 405, and a 400 that is the daemon's `PATH_REFUSED`, the framework's bare HTML 400 page, an empty 400, or
  (on `/hooks/*` and `/mcp`) the framework's `BAD_REQUEST` for a body that is not JSON. The body is checked, not only the
  status. All the excluded routes above are in this run.

Reports in `target/api-fuzz/` (`signed-in/`, `no-session/`: JUnit, NDJSON, text output). Knobs: `API_FUZZ_EXAMPLES` (cases
per operation and phase, default 25), `API_FUZZ_SEED` (default 42), `API_FUZZ_ARGS`. In WSL without Docker on the Windows
side, run it from the WSL shell (Java 25 and Docker there). The run takes about 4 minutes.

### Result, 2026-11-10 (branch `q8b-dast`)

| Run | Operations | Cases | New failures |
|-----|-----------|-------|--------------|
| signed in | 170 of 185 (15 left out, see above) | 7245 | 0 (3 known entries in the baseline) |
| without a session | 185 of 185 | 4673 | 0 |

The first run (10 examples) found 613 failures signed in and 27 without a session.

Fixed in the daemon:

| Failure | Cause | Fix |
|---------|-------|-----|
| 500 on 5 workshop and pull-request routes with a path id outside the id format | `new MissionId(id)` threw `IllegalArgumentException`, which only some routes wrapped in `DirectorRequests.parse` | `Identifier.check` throws `InvalidIdentifierException`; `ErrorMappers` answers 404 for it on every route. `/api/missions/{id}/costs` answered 400 for it and now follows the same rule |
| 500 on `POST /api/sandbox/readiness/probe?project=%00` | `Path.of` on a NUL character | `ErrorMappers` maps `InvalidPathException` to 400 `INVALID_ARGUMENT` |
| 500 on a `null` inside a list of a body (`PUT /api/tickets/{id}` with `acceptanceCriteria: [null]`, `POST /api/committee/roles`, `POST /api/missions`, `PUT /api/iam/resources`) | `.map(String::strip)` and `List.copyOf` on a null item | the annotation `@NoNullItems` (Jackson `contentNulls = FAIL`) on every list of a request record: 400 `BAD_REQUEST` before the route runs. `NullItemsApiTest` drives it over HTTP. Three server-side guards stay for the code paths that do not come from JSON |
| `POST /api/missions/archive {"missions":[null]}`, meeting attendees and speakers, ticket selections answered 404 "Invalid mission id: null" | the same lists | the same annotation: 400 |
| 500 on `POST /api/missions/{id}/proof/verdict` without a `verdict` | `Set.of(...).contains(null)` throws | `Proofs.verdict` and `Proofs.acceptUnproven` test for null first |
| 400 with an empty body and no Content-Type when a body is JSON of the wrong shape (34 routes) | Quarkus's own mapper for `MismatchedInputException` | `ErrorMappers` maps it first: 400 `BAD_REQUEST` with the project's error shape |
| 405 without an `Allow` header (RFC 9110), about 140 routes | the framework adds none | `ErrorMappers` fills `Allow` from the served OpenAPI document (`AllowedMethods`); `unsupported_method` now passes and stays on |
| 335 response schema violations: a field the view leaves null was declared a plain string | the generated schemas make no field nullable | `ApiDocs.nullable` marks every optional property that is not a list as nullable (`anyOf` with `null` for a reference) |
| undocumented statuses: 201, 202, 204, 405, 404, 409, 415 | the documentation listed 200 only, and 404, 409 only on some routes | `api-docs.json` `responses` and `without` per operation; `ApiContract` adds 404 and 409 to every protected route and 415 to every route with a body |

`openapi.json` was regenerated (`-Dfloor42.openapi.write=true`); `contract.ts` still type-checks (`vue-tsc -b`) because it
compares field names, and nullability changes none.

Accepted deviations. Three checks are left out of the signed-in run (`ACCEPTED` in the script). They are not scoped per
route: the stateful phase reaches different routes from one run to the next, so a per-route list or a baseline of their
failures flaps (a first attempt at the baseline held 85 entries and 7 more appeared in the next run). `unsupported_method`
stays on, so a 2xx on a method a route does not declare still fails the run.

| Check | Why it is accepted |
|-------|--------------------|
| `positive_data_acceptance` | The routes validate more than the schema says (`MISSING_FIELDS`, `INVALID_ARGUMENT`, `INVALID_COMMIT`, `PATH_REFUSED` for `;` in a path...) and answer a documented 400 with the usual body. A schema is a shape, not every business rule. |
| `negative_data_rejection` | The daemon reads leniently: a scalar of the wrong type is coerced (`"name": false` becomes the name `"false"`), an unknown header or query parameter is ignored, and a body is optional where the route has defaults (`PUT /api/missions/{id}/assets`, `POST /api/notes/{id}/close`) although the schema marks it required. Narrowing that is a behaviour change, not a fix. |
| `allow_header_conformance` | The framework answers `OPTIONS` with one `Allow` list on every path (HEAD, DELETE, POST, GET, OPTIONS, PUT). Nothing in Floor42 sends `OPTIONS`. |

Accepted in `tools/security/api-fuzz-baseline.json` (Schemathesis's own baseline, so a new failure still fails the run), all
answers of the HTTP server before any route runs: `POST /api/workflows/import` with `Content-Length: -9223372036854775808`
(an empty 400, no JSON body or Content-Type) and a query string long enough for a 414 on `/api/sessions/{id}/context`.
Regenerate the file with `API_FUZZ_ARGS="--baseline /w/baseline.json --baseline-update"` on a run that has no baseline, and
copy `target/api-fuzz/baseline.json` over it after reading each entry.

Open: the agent door (`/hooks/*`, `/mcp`) is fuzzed without a session only; with a real agent token it needs a launched
session, which the fake Claude Code does not give. The module tests cover it with the right credential.

## Decisions

- **Gate only where the result is certain.** Trivy fails the release because a known vulnerable version is a fact and
  an exception needs a reason and an expiry. Semgrep and ZAP report: a pattern match is a lead, and a blocking
  false positive on a release day trains people to ignore the gate.
- **One definition per command.** The scripts in `tools/quality/` hold the image pins and flags; the Makefile and the
  workflows call them, so a laptop run and CI run the same thing.
- **Docker images, not marketplace actions, for the scanners** (except CodeQL and Scorecard, which have no other form):
  one pinned artifact per tool, the same on a laptop and in CI, and one less third party in the workflow's token scope.
- **Schemathesis is nightly only and does not gate.** Its failures that are real defects were fixed first; what is left is
  an accepted deviation with a reason. The signed-in run exits 1 on any new failure so the nightly job turns red.
- **ZAP is nightly only.** It needs the packaged daemon and adds minutes; the release is already gated by the browser
  journeys and Trivy.
- **The release's `security` job also runs Semgrep and the `fs` scan.** They are fast and their artifacts travel with
  the release run; only Trivy can fail it.
