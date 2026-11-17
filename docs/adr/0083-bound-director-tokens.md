# ADR-0083: The director's bearer in IAM mode: script tokens bound to a Keycloak session

Date: 2026-11-16 · Status: Proposed (v1.3) · Amends ADR-0044, ADR-0064 (1.2.0 note) and ADR-0073

## Context

1.2.0 made every script token default-deny in IAM mode (`LocalAccessGuard`, `DirectorMcpResource`, ADR-0064 note): it
reads, proposes a mission and calls the MCP read tools, and answers 403 `IAM_SCRIPT_TOKEN_REFUSED` on every route that
starts or steers agent work. That closed the 1.1 gap (a `missions:write` token approving tickets on a live delegation
with no human present), and it ended headless steering (ADR-0073, `docs/headless.md`) in IAM mode. A script token is an
opaque secret Floor42 minted: nothing in Keycloak stands behind it, so nothing ties its acts to a subject and Keycloak
cannot end it. v1.2 Q6 deferred "a Keycloak token for the director on `/api`" to this release.

## Decision

Two kinds of script token in IAM mode.

- **Unbound** (issued as before, or before 1.3.0): the 1.2.0 default deny, unchanged.
- **Bound**: issued from the desk after a password step-up. It may also steer the routes below, and nothing else the
  default deny refuses.

### The step-up

`POST /api/auth/tokens/bound` (scope, days, label as `POST /api/auth/tokens`) is open to the browser session a plan
approval is open to (`StepUpApprovals.mayApprove`: a Keycloak sign-in with live tokens, not a script, not break-glass).
It records a pending request (5 minutes, single use, bound to that browser session and to the director's `sub`) and
answers 202 `{redirect}`. The redirect is `GET /auth/keycloak/bind`, then Keycloak with `prompt=login`, `max_age=0`,
`acr_values=floor42-strong` and `offline_access` on `floor42-desk`, PKCE S256 and a nonce, as the plan approval (ADR-0064 point 2) and an assured act (ADR-0081, `ActKind.SCRIPT_TOKEN`, level STRONG, no four eyes) do. The callback
is the sign-in's `/auth/callback`: `KeycloakSignIn` hands it the states it does not know, after `StepUpApprovals`. The
class is `BoundTokenIssuance`; `StepUpApprovals` is not changed.

The token is issued only if the id token is valid for the nonce, `acr` is `floor42-strong`, `auth_time` is not older than the redirect
(`StepUpApprovals.freshLogin`: M0 measured that a strong SSO session keeps its `acr` for a later plain request, so
freshness is checked and not inferred from `acr`) and its `sub` is the director's. Otherwise nothing is issued and the
offline session the step-up opened is revoked. Issue order: registry, journal (`ApprovalSigned` for the act, then `ApiTokenIssued` with `subject` and
`binding`), vault; a failure at any step leaves nothing working. The secret is shown once, in the page that answers the
callback (`no-store`, no referrer), never in a URL.

### The binding

The offline refresh token is kept encrypted in the vault under the script token's id (`scriptbind-<id>`) and a vault
index. The script never sees it and no API returns it. As measured for delegations (ADR-0064 point 2), a `prompt=login`
sign-in opens a separate Keycloak session, so each bound token has its own offline session, revocable alone.

On each use, `ScriptTokenBindings`: when the last answer of Keycloak is under 60 s old the token is live without a
call; otherwise one refresh of the offline token (rotation handled as `Delegations.subjectTokenFor` does), off the
event loop, concurrent uses waiting for the one call. A refused refresh (session revoked, user disabled, offline
lifetime reached) marks the token dead for good: 401 `SCRIPT_TOKEN_UNBOUND` on every route, no further call. Keycloak
unreachable: 503 `IDP_UNAVAILABLE`, never allowed, asked again at the next use. The access token a refresh returns is
dropped at once. Staleness is at most 60 s, the bound of the identity watch (ADR-0064 point 6).

Revocation from the desk, "sign out everywhere", expiry: RFC 7009 revocation of the offline token, the vault entry
removed, a miss retried each minute. After a restart the tokens are gone and the offline sessions a previous run left
(listed in the vault index) are revoked by the first sweep.

### What a bound token may do

| Route | Unbound | Bound |
|---|---|---|
| reads, `POST /api/missions` (plain fields), `POST /api/mcp` read tools and `create_mission` | allowed | allowed |
| `tickets/approve`, `nudge`, `workshop/directive`, `questions/{id}/answer`, `missions/{id}/resume`, `agents/{id}/resume` | 403 `IAM_SCRIPT_TOKEN_REFUSED` | allowed, within its scope |
| MCP `answer_question` | refused | allowed, for an agent's own free question only |
| plan approval `missions/{id}/approve` | refused | 403 `HUMAN_ONLY` |
| terminals, websockets, files, settings, secrets, new tokens, every other route | refused | refused |

The list is `LocalAccessGuard.STEERING`; the scope still applies on top (a `missions:write` bound token is refused a
resume with 403 `SCOPE`). Downstream the desk sees a bound token as at most `missions:write`: a permission question
(an approval) and lifting a remembered refusal stay the director's, whatever the scope. `SteeringRoutesTest` reads the
served routes and fails if the list lets through a route other than these six, or a renamed one no longer matches.

### Journal

`ApiTokenIssued` gains optional `subject` (the Keycloak `sub` of the step-up) and `binding`
(`KEYCLOAK_OFFLINE_SESSION`), written only for a bound token (`WireDefaults.TokenBinding`), so an old row and an unbound
token are stored exactly as before and an older binary reads them (ADR-0042, ADR-0009). An act of a bound token keeps
its actor and `authorization_ref`; it joins to the issuing row. Exports show the first 8 characters of the subject, as
ADR-0074.

## Rejected

| Option | Cost | Why not |
|---|---|---|
| Refuse every work-starting write by any script token (1.2.0) | S | kept for unbound tokens; alone it ends headless steering in IAM mode |
| A JWT per script by RFC 8693 exchange of the director's access token | M, a verifier on `/api` | an access token lives minutes: the script would hold a refresh token, a credential in a file |
| RFC 8628 device authorization for scripts | M, a new public client | same: the script holds Keycloak tokens and Floor42 loses the one revocation point |
| Introspect the offline token every 60 s in the identity watch | none per use | introspection needs a confidential client in `aud`; a refresh on use proves the same thing in one call |

## Limits

- Up to 60 s between a revocation in Keycloak and the refusal; a revocation from the desk is immediate.
- A bound token is still a bearer secret: whoever copies it acts as the director until it is revoked or ends. The binding
  makes it revocable from Keycloak and attributable to a subject; it is not sender-constrained.
- Each bound token is one more offline session in Keycloak. The lifetime is the shorter of the days chosen and the
  realm's offline session lifetime; the desk does not yet show the earlier date.
- A token that expired in the registry keeps its offline session until the next sweep (a minute).
- Measured on Keycloak 26.8.1 (2026-11-17, a throwaway realm, two rounds): revoking one offline refresh token (RFC 7009,
  no hint, as `KeycloakClient.revoke` sends it) ends that offline session only; the director's other bound tokens
  still refresh. A desk logout ends the online session only. `logout` must never be sent with a vaulted offline
  token: it ends that token's session. Not measured: two code-flow issues sharing one SSO session.

## Rollback

A 1.2.0 binary reads a bound row's two extra fields as unknown and sets that row aside (`NEWER_VERSION`, ADR-0042); the
tokens themselves live in memory and do not survive the downgrade. The offline sessions left in Keycloak expire with the
realm's offline idle timeout, or are ended in the Keycloak console.
