# ADR-0064: Agents get a Keycloak identity, delegated per mission, with a token broker for external resources

Date: 2026-11-05 · Status: Accepted 2026-11-05 (the director's decisions after the builder check); implemented for v1.0.0.
One new event type, `AgentTokenIssued`. The
protocol facts below were measured on Keycloak 26.8.1 (2026-11-04); the full record is `iam/README.md`.

## Context
Until 1.16, an agent authenticated to the hook and MCP routes with a random token Floor42 minted. That token proves
nothing about who the agent is or on whose behalf it acts, and an agent that reads its environment can replay it.
Floor42 governs real Claude Code sessions, so their identity should be a standard one: issued by an identity provider,
checked on every call, revocable, and visible.

The mode is opt-in (IAM mode). Off, Floor42 behaves as before and has no new runtime dependency.

## Decisions

1. **Keycloak, Token Exchange V2, plus a protocol mapper SPI for `act`.** Each agent on staff has a confidential client
   in the `floor42` realm. The agent's own client performs the exchange; the director's delegated access token is the
   subject token. V2 emits no `act` claim (measured), so `iam/keycloak-extensions/` holds a mapper that writes
   `act = {sub, client_id}` from the client that authenticated the request, never from a parameter. A second mapper
   writes the Floor42 context claim. Two conditions found by measure: the requester client must be in the subject
   token's `aud` (Floor42 adds an audience mapper for each agent client on the director's client), and a mapper only
   keeps the long client lifespan on a token whose audience set is exactly `{floor42-api}`.
2. **Per-mission delegation by step-up re-authentication.** At each plan approval the director signs in again at
   Keycloak (password step-up, `auth_time` checked against the redirect, whole seconds). That sign-in yields an offline
   refresh token, kept encrypted in Floor42's data folder, which mints the access token each session exchange needs.
   Revoking it (RFC 7009) ends the mission's delegation.
   - **Why one sign-in per approval (measured).** Offline tokens obtained inside one SSO session share one offline
     session: revoking one makes the others fail with "Offline user session not found", and the SSO session goes too.
     A flow with `prompt=login` creates a separate user session, and revoking its token leaves the other working. So
     one offline session per mission needs one full authentication per approval.
   - An approval or mission start by a script token (ADR-0044) is refused in IAM mode: there is no director token to
     delegate.
3. **Session token claims.** The token carries `floor42 = {launch, mission, ticket}`, not Floor42's session id in `sid`:
   Keycloak sets `sid` to the director's SSO session and keeps it. `launch` is a UUID fresh per session, resumed
   sessions included. The claim is a self-assertion by the agent client; Floor42 binds it to its own session record
   and refuses any mismatch.
4. **Process binding.** All calls arrive on loopback, and Claude Code sends static headers only, so sender-constrained
   tokens (DPoP, mTLS) are impossible. On every hook and MCP call Floor42 resolves the owner of the connecting socket
   (`SocketOwners`: TCP table through FFM on Windows, `/proc` on Linux) and accepts only the process it launched. The
   expected PID is held in memory from `builder.start()`; `process.txt` is never read for this, because the session can
   write its own folder. Measured: Claude Code 2.1.318 makes its hook and MCP calls itself. A `curl` from the agent's
   Bash has another PID: refused `WRONG_PROCESS`. A platform that cannot resolve owners refuses IAM mode.
5. **Audience.** Exactly `floor42-api`. The agent client's own `client_credentials` token also has that audience, but no
   `act` and a service account as `sub`: the verifier requires `act` and a user `sub`.
6. **Verification.** Every call is checked locally: RS256 signature against a cached JWKS (an unknown `kid` refreshes
   it once, then refuses), issuer, audience, expiry (30 s skew), `act`, `floor42` claim, session liveness, process.
   No network call on the hook path. A background check every 60 s asks Keycloak, by introspection as `floor42-api`
   (the only client Keycloak lets introspect these tokens, measured), whether each live session's agent client is
   enabled and its delegation valid. Keycloak unreachable: no new session starts; a running session is refused after
   two failed checks in a row (about 2 minutes). Floor42 never fails open.
7. **Lifetime.** At most 8 hours (`floor42.iam.max-token-hours`, cap 8). Claude Code reads its header once and cannot
   refresh it. Expiry is not revocation: ending a session refuses it at the next call.
8. **Director sign-in.** Authorization Code with PKCE. The single-use console link stays as break-glass, is shown and
   journaled as such, and cannot mint a delegation (sessions then wait for a Keycloak sign-in).
9. **IAM mode requires the sandbox** (ADR-0063). Without it, the agent's shell could read the token from its
   environment. IAM mode refuses to start otherwise (`IAM_NEEDS_SANDBOX`).
10. **Event.** `AgentTokenIssued(session, agent, mission, ticket, issuer, expiresAt, tokenId)`: the `jti` and a summary,
    never the token. Refusals use the existing denial path with a reason code. Reader and writer ship together in 1.17.
11. **Token broker, `call_resource`.** An agent never holds a credential for an external resource. The MCP tool
    `call_resource(name, method, path, body)` goes through the PreToolUse hook like any Floor42 tool.
    - One Token Exchange per call, for one audience, narrowest scope, at most 300 s. The token is never returned,
      logged or journaled.
    - Floor42 resolves the host once, checks the address, then connects to that address (IP pinning against DNS
      rebinding), with SNI and the certificate's host name checked against the catalogue host. This sets the `Host`
      header, so the JVM runs with `-Djdk.httpclient.allowRestrictedHeaders=host`.
    - The URL is the catalogue base plus an allowed path prefix. No redirect to another host, no cookies, a size cap, a
      timeout. HTTPS required except for resources the director marks local.
    - The answer is the status, filtered headers and the capped body, quoted as untrusted data: enclosed by code between `BEGIN-UNTRUSTED-<nonce>` and
      `END-UNTRUSTED-<nonce>` (random nonce per call) after a fixed preamble, so the body cannot forge the end. The call is an action
      (`ActionRequested`, `ActionAuthorized`, `ActionExecuted`) naming the resource, method, path, the exchanged
      token's `jti` and audience, and the HTTP status.
12. **Catalogue and grants are files** for v1.0.0: resources, and per agent and per mission what may be used. A call
    needs both grants. They need no new event type.
13. **Secrets.** Client secrets are hex (`openssl rand -hex 32`): Keycloak URL-decodes HTTP Basic credentials, so other
    characters break authentication. Secrets and delegations are encrypted in the data folder with a key outside any
    worktree, masked from the sandbox.

## Consequences
- In IAM mode a hook or MCP call is accepted only with a valid Keycloak token for that agent, that session, that
  mission and ticket, from that process. The screen shows the chain director, agent, resource, and a trust chain view
  per session. A mission's identity events export as JSON lines.
- The director signs in at Keycloak at each plan approval. The consent shown before the click says what is delegated
  and until when.
- Identity only restricts. It proves which session acts and for whom, not what the model intends: a prompt-injected
  agent acts with its real identity. The permission table stays in force. See ADR-0066 for the session risk watch.

## Known limits
- Committee and auto-approved missions have no plan approval by the director, so no delegation: in IAM mode they are
  blocked until a Keycloak approval.
- A local broker resource (marked local, plain HTTP on loopback) gets no `Host` pinning and no SNI check.
- Resources are edited through the API only; the screen has no editor for them.
- Process binding resolves the owner when the request is handled, not when the connection is accepted; a connection
  reset in between fails closed. Only the native `claude` install was measured; a `claude.cmd` install (a `node.exe`
  under `cmd.exe`) was not.
- Sub-agents of a session share its process and token: the journal shows "session X and its sub-agents".
- Relationship-based authorization (OpenFGA) is the next increment and has its own design.

See also: [revocation guarantees, measured](../iam-revocation.md).
