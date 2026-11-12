# Floor42 threat model

State described: Floor42 on 2026-11-06, shortly before v1.0.0. It lists what the code enforces today, with the file or ADR that
enforces it. Planned work is not listed as a control. [ADR-0065](adr/0065-container-broker.md) (containers for
missions) is only proposed and appears nowhere below as a defence.

Companion documents: [SECURITY.md](../SECURITY.md) (what is and is not enforced, how to report),
[iam/README.md](../iam/README.md) (the Keycloak setup and the protocol facts measured on Keycloak 26.8.1),
[docs/iam-revocation.md](iam-revocation.md) (revocation delays, measured).

Paths below are relative to `floor42-server/src/main/java/dev/floor42/server/` unless they start with `docs/`.

## 1. Assets

| Asset | Where it lives | Why it matters |
|---|---|---|
| The director's session and token | Daemon memory only; browser cookie (HttpOnly, SameSite=Strict) (ADR-0034, ADR-0044) | Whoever holds it is the director for the whole API: approve plans, change permissions, accept patches. |
| Agent credentials | Per session: `FLOOR42_HOOK_TOKEN` in the process environment, or in IAM mode a Keycloak access token (ADR-0064) | Opens that session's hooks and MCP tools. |
| Agent identities and delegations | One Keycloak client per agent; one offline refresh token per mission, encrypted in the data folder (ADR-0064) | A delegation lets an agent act for the director on external resources. |
| Client secrets | `SecretVault`: AES-256-GCM, key file `vault.key` outside any worktree (`iam/SecretVault.java`) | Mint tokens for an agent identity. |
| Repositories and worktrees | The director's checkouts (not touched by agents); one git worktree and branch per agent | Source code and its history. |
| The host machine | The director's OS account | Agents run as that account unless sandboxed. |
| The journal | SQLite file `floor42.db` in the data folder | The audit trail and the source of permissions and approvals at the next start. |
| External resources | Catalogued HTTP APIs reached through `call_resource` | Real data and real side effects. |

## 2. Trust boundaries

```
 Director's browser ----(1)----+
                               v
 +--------------- Daemon, one process (127.0.0.1) ----------------+
 |  orchestrator | hooks + MCP | token broker | vault | journal     |
 +--+-------------+---------------------+---------------+----------+
    |(2)          |(3)                  |(4)            |(5)
    v             v                     v               v
 Agent sessions   WSL sandbox         Keycloak        External
 (Claude Code     (shell of an        (identity,      resources
 processes, own   agent session)      revocation)     (HTTPS)
 worktree)
```

| Boundary | What crosses it | What guards it |
|---|---|---|
| (1) Browser to daemon | API and WebSocket calls | Loopback bind, Host and Origin checks, request header, single-use sign-in, CSP (`security/LocalAccessGuard.java`, ADR-0007, ADR-0034, ADR-0044). |
| (2) Daemon to agent session | A launched Claude Code process; its hook and MCP calls come back | Per-session credential, fail-closed hooks, in IAM mode the Keycloak token and the process binding (`runtime/HookRegistry.java`, `iam/IamSessions.java`). |
| (3) Agent session to its shell | Shell commands | The WSL sandbox, mandatory for real missions (ADR-0028, ADR-0063). Without it: text checks only (`runtime/ShellGuard.java`). |
| (4) Daemon to Keycloak | Token exchange, introspection, JWKS | Local verification on every call; a check every 60 s (`iam/IdentityWatch.java`). |
| (5) Daemon to external resources | One brokered HTTP call | Catalogue, grants, address pinning (`iam/ResourceBroker.java`, `iam/SafeUrls.java`, `iam/PinnedClients.java`). |

The daemon is orchestrator and security authority in one process. A flaw that gives code execution in the daemon
gives everything it holds (section 4).

## 3. Threats

Residual risk is what remains with the control in place.

| Threat | Control | Where enforced | Residual risk |
|---|---|---|---|
| Prompt injection steers an agent | Identity does not stop it: an injected agent acts with its real identity. What bounds it is the permission table (auto, ask once, ask every time, forbidden), fail-closed hooks, its own worktree, and the shell sandbox. | `runtime/ClaudeCodeRuntime.java` (hooks, fail-closed), `api/HookResource.java`, ADR-0064 consequences | Within what the table allows, the injected agent does it. The answer of a brokered call is enclosed in code (`McpResource.answer`: a fixed preamble, then the body between `BEGIN-UNTRUSTED-<nonce>` and `END-UNTRUSTED-<nonce>` with a random nonce per call, so the body cannot close it); a model may still obey text inside it. Session risk v1 (ADR-0066: `app/SessionRiskWatch.java`, `iam/InjectionMarkers.java`) raises an alert on injection markers in a brokered answer and on a refusal that follows; its rules read text and counts, so a patient agent under the thresholds is not seen, and nothing acts until the director clicks. |
| Misuse inside the allowed scope (probing, drift) | Refusals by policy, paths outside the workspace, denied commands and broker refusals by code are scored per session (ADR-0066). A strict threshold journals `SessionRiskRaised` and puts a To do row before the director with a proposed quarantine (revoke, suspend, stop), applied only on his click. | `domain/SessionRisk.java`, `app/SessionRiskWatch.java`, ADR-0066 | Rules are fixed code, not a model: obfuscation, tool bypass and runaway sessions are not covered in v1. The false-positive rate and the cost on the allow path are not measured yet. |
| An agent reaches beyond its ticket | Tokens carry `floor42 = {launch, mission, ticket}`; Floor42 compares them with its own session record and refuses a mismatch (`WRONG_MISSION`, `WRONG_TICKET`, `WRONG_AGENT`, `WRONG_AUDIENCE`). File tools cannot leave the worktree. A shell command naming Floor42's data folder, `git push` or `gh` is denied. | `iam/IamSessions.java` (`compared`), `runtime/ShellGuard.java`, SECURITY.md | The shell check reads text (ADR-0034): it stops mistakes, not a hidden command. Sub-agents share their session's process and token (ADR-0064, known limits). Without the sandbox, a shell runs with the director's rights. |
| Token theft or replay | Lifetime at most 8 h (`floor42.iam.max-token-hours`, cap 8). Verification: RS256 signature, issuer, audience exactly `floor42-api`, expiry (30 s skew), `act`, `floor42` claim, live session. Ending a session refuses its token at the next call. Token shown only to the session's process. | `iam/AgentTokenVerifier.java`, `iam/Jwks.java`, `iam/IamSessions.java` | Expiry is not revocation. A token copied inside the owning process (a sub-agent, or code in Claude Code itself) is accepted. |
| A token copied to another process | Process binding: the connecting socket's owner PID must be the PID Floor42 launched (`WRONG_PROCESS`). The expected PID is held in memory, never read from a file the session can write. Platform that cannot resolve owners: IAM mode refuses to start. | `iam/IamSessions.java` (`process`), `iam/SocketOwners.java`, `iam/WindowsSocketOwners.java`, `iam/LinuxSocketOwners.java`, ADR-0064 decision 4 | The owner is resolved when the request is handled, not when the connection is accepted (a reset in between fails closed). Only the native `claude` install was measured, not a `claude.cmd` install. Outside IAM mode there is no binding. |
| Secret leakage into logs, journal, console | The director's token is not on disk; the sign-in code is printed with `System.out`, not the logger, and the API console masks `code=` and `token=`. Script tokens: the journal keeps only the SHA-256. `AgentTokenIssued` records the `jti`, never the token. Brokered tokens are scrubbed from responses before the size cut, and a prefix of 8 characters or more left at the cut is masked. A hook authorisation buys one broker call: replaying a `toolUseId` is refused. `floor42.sandbox.assume-available` stops the start outside the test launch mode. Replay export and parsed stream lines are masked with one pattern set. | `api/ConsoleMask.java`, `security/SecretMasker.java` (used by `runtime/StreamJsonParser.java` and `security/ReplaySanitizer.java`), `iam/ResourceBroker.java` (`scrub`), ADR-0034, ADR-0064 decision 10 | Masking is pattern based: a false negative on an unusual secret format is possible, and a secret under a harmless key with no recognisable shape is kept. Event payloads are not masked when appended to the journal: a secret an agent puts in a tool input or a command line is stored as written (masking the source of truth by pattern corrupted ordinary text and paths on replay; to be redesigned field by field). |
| Privilege escalation through the broker | An agent never holds a resource credential. One Token Exchange per call, one audience, at most 300 s, checked after issue. The URL is the catalogue base plus an allowed path prefix. Both the agent's and the mission's grant are needed. The address is resolved once, checked (no private, link-local, carrier-grade NAT, reserved ranges) and pinned; SNI and certificate host checked; no redirect; no proxy; capped, time-limited response; HTTPS except resources marked local. | `iam/ResourceBroker.java` (`MAX_TOKEN_SECONDS`, `checked`), `iam/SafeUrls.java`, `iam/PinnedClients.java`, `iam/ResourceCatalogue.java` | A resource marked local gets no host pinning or SNI check (ADR-0064, known limits). The catalogue and grants are files in the data folder; a process that can write them can widen them (see "journal" below). A granted resource is trusted with what the grant allows. |
| A compromised dependency | Module boundaries enforced by test (domain is JDK only). Release builds attach build provenance and CycloneDX SBOMs for Java and web; Actions are pinned by commit SHA; Dependabot is configured. | `floor42-server/src/test/java/dev/floor42/server/ArchitectureTest.java`, `.github/workflows/release.yml`, `.github/dependabot.yml`, docs/releases.md | A malicious dependency runs inside the daemon with the daemon's rights. The SBOM and provenance let you check what shipped; they do not prevent a bad release. |
| The director's browser attacked by a local page (CSRF, DNS rebinding) | Loopback bind; Host must be loopback; forwarded requests refused; Origin must equal the daemon's own scheme, host and port; a change or a WebSocket from a browser must name its Origin; a change must carry `X-Floor42-Request: 1`; strict CSP, `nosniff`, `X-Frame-Options: DENY`; sign-in by a single-use 60 s link delivered by the daemon, a second use revokes the session it opened. | `security/LocalAccessGuard.java` (`REQUEST_HEADER`, `allowedOrigin`), `security/SessionToken.java`, `api/AuthResource.java`, ADR-0007, ADR-0034, ADR-0044 | A process of the same OS user can still read the browser's cookie store or catch the link on its way to the browser (ADR-0034, "the attack it does not stop"). Whoever drives the signed-in browser acts as the director. |
| The session cookie reaches a server on another loopback port | Browsers do not isolate cookies by port: the `floor42_session` cookie (HttpOnly, SameSite=Strict) is also sent to any other server on `127.0.0.1` or `localhost`, since a request to another port is same-site. The OIDC flows do not depend on it: `redirect_uri` is built from the daemon's real port, and each `state` is bound to the browser by a short-lived pre-sign-in cookie (`iam/PreSignIn.java`). | `iam/PreSignIn.java`, `security/LocalAccessGuard.java` (`signInBase`) | **Open.** A process that listens on another loopback port and is reached by the director's browser (a dev server, another OS account's service on a shared machine) can receive the cookie and replay it against the daemon until the session ends. Relevant on a machine shared by several OS accounts. Planned mitigation after v1.0.0: a second, non-cookie session factor (a per-request value the page holds in memory). |
| Revocation lag | Revoking a delegation or disabling an agent: the next hook or MCP call is refused (`REVOKED`) and the session is stopped; a background pass every 60 s also stops sessions without waiting for a call. | `iam/IamSessions.java` (`revocation`, `stopRevoked`), `iam/IdentityWatch.java` (`PERIOD`) | Up to one background period (60 s) plus the time of a call in flight. A brokered token already issued lives up to 300 s at the resource. The measured figures are in [docs/iam-revocation.md](iam-revocation.md). |
| Keycloak outage | Signature checks use cached keys, so a running session keeps working through a short outage. After two failed background checks in a row (about 2 minutes) a running session is refused (`KEYCLOAK_UNREACHABLE`). No new session starts. Floor42 does not fail open. | `iam/IamSessions.java` (`FAILED_CHECKS_LIMIT`), `iam/IdentityWatch.java`, `iam/Jwks.java`, ADR-0064 decision 6 | About 2 minutes in which a revocation made at Keycloak is not yet seen. All IAM-mode work stops while Keycloak is down. |
| An agent writes the journal (same OS user) | Mandatory sandbox for real missions: the journal, vault, settings and other worktrees are not mounted. Detection of a swapped or truncated journal at the next start (continuity record, red banner). `ConfigGuard` refuses a start with planted configuration. | `runtime/Sandbox.java` (tmpfs over `/mnt`, `/home`, `/run`, `/tmp`), `runtime/SandboxPolicy.java`, `security/ConfigGuard.java`, ADR-0063, SECURITY.md | In unsafe mode, and in Docker, the shell runs as the daemon's user and can write the journal (debt D16). Replacement is detected, not prevented. |
| Unsafe mode left on | `PUT /api/unsafe-mode` needs the director, a reason, and journals `UnsafeModeChanged`. Off by default. IAM mode refuses to start without the sandbox (`IAM_NEEDS_SANDBOX`). | ADR-0063, `iam/IamStatus.java` (`NEEDS_SANDBOX`), `floor42-domain/src/main/java/dev/floor42/domain/event/PreferenceEvent.java` | The director can switch it on. While on, section 4's "no confinement" applies. |

## 4. Out of scope

Stated plainly, these are not defended.

- **Kernel or hypervisor escapes.** The sandbox is a user namespace, a mount namespace, `no-new-privs`, a resource
  limit and a seccomp filter inside a WSL distribution (`runtime/Sandbox.java`, ADR-0063). A kernel flaw defeats it.
- **The model's intent.** Floor42 bounds what an agent may do. It does not decide whether what it does is right
  (ADR-0064: identity proves who acts, not what the model wants).
- **A machine already compromised.** Malware running as the director reads the browser's cookies, the daemon's memory
  (on Windows there is no non-dumpable equivalent) and the screen (SECURITY.md).
- **Native Linux confinement.** On Linux the shell sandbox is the same script run natively (ADR-0028, amended
  2026-11-09): an allowlist root, so the host outside the worktree is not visible, and network on only through
  `pasta` (never the host's network). The residual risks are those of WSL: a kernel flaw in user namespaces, and
  what the read-only system directories (`/usr`, a few `/etc` entries) show. The director's Maven local repository
  is shown read-only on both backends (amendment of 2026-11-11): its artefacts, including proprietary ones and the
  internal repository URLs in its metadata files, are readable by an agent and can leave when the network is open;
  `settings.xml` and the wrapper are never shown. Docker ([docs/docker.md](docker.md)) is
  not confined this way (agents run as the daemon's uid there). Containers for missions (ADR-0065) are not built:
  agents have no container engine. macOS has no sandbox.
- **The network, outside the WSL sandbox.** The sandbox cuts the network unless the director opens it
  (`runtime/Sandbox.java`, `policy.sandboxNetwork()`). Inside the Docker container the network is not confined
  (SECURITY.md). The permission table forbids network tools by default.
- **A case variant of `.claude` or `.mcp.json` on WSL.** The sandbox binds `.claude` and `.mcp.json` read-only, but the Windows
  file system ignores case: `.CLAUDE/settings.json` or `.MCP.json` can be written from inside the sandbox. It matters only
  for the director's own Claude Code opened on that folder: Floor42's sessions run `--restricted` (no project settings,
  skills or agents, measured 2026-11-11) and `--strict-mcp-config`. Delivery refuses any case variant (`PROTECTED_PATH`,
  every path segment, without case); a gitignored one is not part of any commit and is not delivered.
- **WSL with the network on shares the distribution's network namespace.** With the network off the sandbox has a
  network namespace of its own (empty). With it on, on WSL, commands run in the distribution's namespace: they reach
  its loopback services, and Windows' loopback too when WSL runs in mirrored networking mode. Native Linux does not
  have this gap (`pasta`, a namespace of its own). Open the network for a mission only knowing that.
- **The daemon being orchestrator and security authority.** Hooks, broker, vault and journal share one process and one
  OS account. A compromise of the daemon yields the vault key, every secret in it and the right to forge any event.
  The split is planned for after v1.0.0; it is not built.
- **Reviewing what you accept.** Another agent's review catches mistakes, not a determined attacker writing into
  your repository (SECURITY.md).

## 5. Known windows and limits

| Window or limit | Size | Source |
|---|---|---|
| Revocation seen by a running session | Next call, or at most 60 s by the background pass | `iam/IdentityWatch.java`; measured figures: [docs/iam-revocation.md](iam-revocation.md) |
| Keycloak unreachable, running session | About 2 minutes (two failed checks) | `iam/IamSessions.java`, ADR-0064 |
| Agent access token lifetime | At most 8 h, configured in Keycloak and enforced locally by the verifier (`exp - iat` over `maxTokenHours` + 30 s is refused); cannot be refreshed by Claude Code | ADR-0064 decision 7 |
| Brokered token lifetime | At most 300 s | `iam/ResourceBroker.java` |
| Browser session | 8 h unused, 24 h at most | SECURITY.md, ADR-0044 |
| Script token | At most 30 days, gone at restart | SECURITY.md, ADR-0044 |
| Sign-in link | Single use, 60 s | ADR-0034 |
| Committee and auto-approved missions | No plan approval by the director, so no delegation: blocked in IAM mode | ADR-0064 |
| Skill dynamic command (`!` line) | Closed: every session carries `disableSkillShellExecution: true` (measured 2026-11-11, Claude Code 2.1.322, ADR-0028), and with the sandbox on no plugin skill holding such a line is given. `--restricted` loads no project skills or agents (measured). Without the sandbox, `!` lines are still off | `runtime/ClaudeCodeRuntime.java` (`writeSettings`), `runtime/DynamicSkills.java` |
| Sub-agents | Share the session's process and token | ADR-0064 |
| File-tool path swapped after the check | The confinement check runs at the request and again right before an allow is answered (`HookResource`, `Governor.violationNow`), so a link that appeared while the director decided is refused. A few milliseconds remain between that last check and Claude Code's own file access: on native Linux a background sandboxed shell could win that race. On Windows with WSL the Windows side does not follow WSL links (measured) | `api/HookResource.java`, `runtime/Confinement.java` |
| Risk raise | Journaled after the batch that crossed the threshold, on a forked task; the To do row appears at the next progress poll. Not measured yet | ADR-0066 |
| JWKS refetch after an unknown key | Not more than once per 30 s | `iam/Jwks.java` (`MIN_REFETCH`) |
