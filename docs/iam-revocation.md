# IAM mode: what stops working, when, and how

Measured by `floor42-server/src/test/java/dev/floor42/server/iam/RevocationGuaranteesTest.java` against the fake
Keycloak (`FakeKeycloak`), not against a real Keycloak. A "pass" is one run of the background check
(`IdentityWatch`): it starts 60 s after the previous one ended (`scheduleWithFixedDelay`), so the gap is 60 s plus the
time the pass took. A "call" is one hook or MCP call of the agent. Nothing below is instant except where the bound is
"0 passes": that means the very next call is refused by a flag in memory, with no network and no wait.

## The table

| # | Case | Mechanism | Bound (test) | What the agent can still do inside the window |
|---|---|---|---|---|
| 1a | Director revokes the mission's delegation (UI), or the mission is cancelled, archived or completed without a retrospective | `Delegations.revokeLocally` flips a flag in memory; hooks check it (`IamSessions.revocation`), the broker checks it | 0 passes, 0 calls: next hook refused `REVOKED`, next resource call refused (`IDENTITY_REFUSED`, Floor42's own decision; `IDENTITY_UNAVAILABLE` is only a provider failure). Holds with Keycloak down (`revokedDelegationRefusesTheNextHook...`) | Nothing new. A tool call already past its hook finishes. A session that makes no call is stopped by the next pass (`IamSessions.stopRevoked`, first step of a pass): at most one gap |
| 1b | Mission completed, retrospective due | `Delegations.closeToWork`: running sessions cut, a later retrospective session allowed | 0 passes (`aMissionThatEndedOrCompleted...`) | As 1a |
| 1c | Keycloak's side of 1a | `revokeRemote` (RFC 7009), retried by every good pass and at start | Keycloak down: the offline token stays valid at Keycloak until the first good pass (`keycloaksSideOfTheRevocation...`). Floor42 cannot use it meanwhile: the vault entry is unreachable through `Delegations` | Nothing through Floor42. Anyone who held the offline refresh token outside Floor42 is outside this model (it lives only in the vault) |
| 2a | Director revokes an agent in the UI, or dismisses it | `AgentClients.revokeLocally` (memory), then the client is disabled at Keycloak | 0 passes for hooks and broker (`theDirectorsRevokeOfAnAgent...`) | Nothing new |
| 2b | Client disabled in Keycloak directly (admin console) | The next pass introspects: `active:false` | Hooks: 1 pass. Resource broker: 0 calls (the exchange itself is refused) (`aClientDisabledInKeycloak...`) | Hook and MCP calls with the token it holds, until the pass |
| 2c | Client secret rotated in Keycloak | Nothing revokes a token. The old secret fails the next exchange | Broker: 0 calls once the vault secret is stale. Hooks: not revoked at all, the token stays active until it expires, at most `maxTokenHours` (default and cap 8 h) (`aRotatedSecretRevokesNothing...`) | Everything its session token allows, up to its expiry. Rotation is not a revocation: disable the client |
| 3a | Token already issued, Keycloak says `active:false` (client disabled, delegation revoked at Keycloak, session ended there) | Introspection, every pass, `floor42-api` credential | 1 pass, then refused for that launch for good (`anInactiveAnswerEnds...`) | Calls until the pass |
| 3b | Token expiry | Local check, no network | `exp` plus 30 s skew; refused from the first second after (`aTokenIsAcceptedLocally...`). Cap on a session token: 8 h, enforced locally too: a token whose `exp - iat` (`exp - now` without `iat`) exceeds `maxTokenHours` plus 30 s is refused, whatever Keycloak issued (`aTokenLivingLongerThanTheConfiguredMaximum...`) | Calls until `exp + 30 s` |
| 3c | Introspection refused for one token (400) | Counted as a failed pass, never a verdict on the token | Never ends the session by itself; 2 such passes refuse running sessions as in case 4 (`aTokenSpecificRefusal...`) | Calls, for 2 passes |
| 4 | Keycloak unreachable | Passes fail: key refresh or introspection gets no answer | New sessions: blocked after pass 1. Running sessions: refused `KEYCLOAK_UNREACHABLE` after pass 2, so about 2 gaps (about 2 minutes, more if a pass hangs: see windows). Broker: refused on the next call. Local revocations (1, 2a): still 0 passes. Resume: the first good pass clears both, nothing sooner (`anUnreachableKeycloakBlocks...`, `theFirstGoodPassAfterAnOutage...`) | Hook calls with a cached signing key, until pass 2. Any unknown signing key (rotated) is refused at once |
| 5 | Broker tokens for external resources | One Token Exchange per call, never cached, never shown to the agent, scrubbed from echoes | Life at most 300 s (checked: 301 s is refused) (`everyResourceCall...`, `aTokenOfMoreThanFiveMinutes...`). Revoked delegation: the next call is refused with no pass, even when only Keycloak was told (`afterTheDelegationIsRevoked...`) | The resource may accept a token already sent for up to 300 s if it checks signature and expiry only; a resource that introspects stops at the revoke. Floor42 cannot introspect it (not in the audience) so this part is not measured |
| 6 | Floor42's own `floor42-api` credential refused by Keycloak (wrong or rotated secret) | Every pass probes the credential itself (`KeycloakClient.probeIntrospection`), with or without a session | Status `IAM_API_CREDENTIAL_REFUSED` after pass 1, new sessions blocked; running sessions refused after pass 2 (same limit as case 4). Stays refused across passes, across an outage and when Keycloak is back with the secret still wrong; only a passing probe returns to UP and sends the recovery nudge (`aRefusedApiCredentialStaysRefused...`, `aRefusedApiCredentialFollowedByAnOutage...`) | Hook calls until pass 2. Nothing clears the refusal while no session exists |
| 7 | Director applies a proposed quarantine (To do row), ADR-0066 | `revokeLocally`, then suspend, then stop the process, on one click | 0 passes: as 1a; the process is stopped at once. Test: not written yet | Nothing new |

## Known windows, plainly

- Known gap, decided for v1 (decision D1, ADR-0066): in IAM mode a quarantined or suspended mission whose plan is
  already approved cannot be re-delegated. No route does it; resume is refused "delegation revoked" until the director
  cancels and replans. The director kept this out of scope before v1.0.0.
- A delegation revoked while a session is starting (after the start checks, before the session is registered) still
  ends it: the revocation is compared with the instant the checks began, not with the registration
  (`aRevokeBetweenTheCheckAndTheRegistrationStopsTheSession`).

- Hooks are not instant against Keycloak-side changes. A client disabled or a token revoked only in Keycloak reaches
  the hooks after the next pass: up to 60 s plus the pass.
- Rotating a secret does not end a session. Only expiry (at most 8 h) or a disable does.
- The gap between passes is 60 s plus the pass duration. A pass asks Keycloak once per live session (10 s
  timeout each) and stops asking at a 50 s deadline (`IdentityWatch.DEADLINE`), so a pass lasts at most 60 s and the gap
  at most 120 s. The sessions it did not reach are not ended: the pass counts as failed (so two such passes in a row
  refuse running sessions, as in case 4) and the next pass asks those sessions first.
- No `floor42-api` secret in the vault is a status failure `IAM_API_SECRET_MISSING` (new sessions blocked at once,
  running ones refused `KEYCLOAK_REFUSED` after the second pass), never UP; saving the secret clears it at the next
  pass.
- An outage refuses running sessions after the second failed pass, not the first.
- A running tool call (a shell command, a long edit) is not interrupted by a revocation; the next hook is refused.
- Resource tokens are valid at the resource until they expire (at most 300 s) unless the resource introspects.
- Not measured: a real Keycloak 26.8.1. The fake mirrors what was measured of it (`DelegationsTest`) but token
  revocation by family and `active:false` answers are the fake's.

## Findings

- F1 (fixed). A refused `floor42-api` credential used to hide an outage from the failure count (`IamStatus.checkFailed`). It now counts, so running sessions are refused after the second failed pass in that state too (`aRefusedApiCredentialFollowedByAnOutage...`).
- F2 (fixed, was D33). The introspection loop was serial with a 10 s timeout per request, so the gap between passes
  grew with the number of live sessions. A pass now has a 50 s deadline; unreached sessions are asked first next time
  (`aPassStopsAtItsDeadline...`).
