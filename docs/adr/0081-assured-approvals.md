# ADR-0081: Assured approvals, a second factor on a phone and four eyes

Date: 2026-11-16 · Status: Accepted (1.3.0) · Amends ADR-0064 (point 2, step-up), ADR-0073 (the director's MCP endpoint)

## Context

Outside plan approval, an approval in Floor42 is a click in a browser that holds the director's cookie. That proves a
browser session, not a person and not a second person: whatever drives that browser (the director, a colleague at the
desk, a model given computer use) approves with the same weight. Plan approval already asks for the password again
(`StepUpApprovals`, ADR-0064 point 2). The roadmap asks for more on risky acts: a stronger proof, on the phone, and for
some acts two different humans.

Measured on Keycloak 26.8.1 (M0, 2026-11-16) and kept as the facts of this decision:

- `acr_values=floor42-strong` is a request. With a LoA-2 sub-flow (OTP) Keycloak returns `acr=floor42-strong` only
  after the second factor, and the existing `acr` mapper plus a realm `acr.loa.map` gives the name, not a number.
- A plain request in a browser whose SSO session reached the strong level returns `acr=floor42-strong` again with no
  page. So `acr` alone does not prove a fresh approval: `prompt=login`, `max_age=0` and the `auth_time` check stay.
- A user with no second factor is offered TOTP enrolment inside the step-up, with just their password, and then gets
  `floor42-strong`. The strong level is only as strong as the enrolment.
- `realm_access.roles` is in the access token only (not the ID token, not userinfo).
- A second user's `prompt=login` in the director's browser ends the director's Keycloak online session (the offline
  sessions of missions survive), and shows the previous user's name with a "Restart login" button.
- A phone passkey (WebAuthn hybrid transport) on `localhost` was not measured (it needs a phone and a headed browser).

## Decision

1. **A fixed table in code, `AssurancePolicy`** (pure domain). BASIC is today's click, STRONG one fresh signature at the
   strong level, FOUR_EYES two signatures by two people.

   | Act | Four eyes off | Four eyes on |
   |---|---|---|
   | Plan approval (already a password step-up) | STRONG | STRONG |
   | ALLOW to a NETWORK call that is not a plain read, in practice every `call_mcp` | STRONG | STRONG |
   | A permission rule that sets ALLOW on EXECUTE or NETWORK | STRONG | FOUR_EYES |
   | A grant, a delegation change outside plan approval, an MCP tick | STRONG | FOUR_EYES |
   | Opening a pull request or pushing to a remote | STRONG | FOUR_EYES |
   | Raising an agent's trust level | STRONG | FOUR_EYES |
   | Issuing a bound script token | STRONG | STRONG |
   | Turning four eyes off | FOUR_EYES | FOUR_EYES |

   Everything else keeps the click. Floor42 has no HTTP method in its tool model: WebFetch and WebSearch are the only
   plain reads (GET), so any other NETWORK tool is "not GET or HEAD". Turning four eyes off is itself a four-eyes act:
   a switch that one person can turn off is no control. This node enforces the question and setting rows end to end;
   the other rows are in the table and in `AssurancePolicy.verify`, which their facades call (grants, trust and tokens
   belong to nodes N2, N4 and N5; the rule and pull request rows are left to a follow-up, see Limits).
2. **Floor42 checks the token itself.** The first seat sends the director to `floor42-desk` with PKCE, `state`,
   `nonce`, `prompt=login`, `max_age=0` and `acr_values=floor42-strong` (no `offline_access`: that stays plan
   approval's own). On the callback `IdTokenCheck` verifies signature, `iss`, `aud`, `nonce`, time claims; then Floor42
   requires `acr` equal to `floor42-strong`, an `auth_time` at or after the redirect second, and, for the first seat,
   `sub` equal to the director's. An `acr` in the query string or a form is never read. Only then the act is carried
   out, with one `ApprovalSigned` row per signature in the same command.
3. **The domain enforces it, not only the route.** With an assured command context (IAM mode on), the answer to a
   question that `AssurancePolicy` marks STRONG is refused with `ASSURANCE_MISSING` unless it carries a signature for
   that question, seat FIRST, at the strong level (`ASSURANCE_TOO_LOW`), at most 120 s old (`STEP_UP_STALE`). A route
   that forgets the step-up fails a domain test. Turning four eyes off, in an assured context, needs two signatures
   (`SAME_PERSON` for one subject twice, `SIGNATURE_EXPIRED` for a first signature older than 30 minutes). With IAM
   mode off nothing changes: there is no second factor to ask for, and `CommandContext.assured` is false.
4. **Bearer callers cannot step up.** A script token, or the director's own bearer, asking for an assured act gets 403
   `HUMAN_ONLY` with `WWW-Authenticate: Bearer error="insufficient_user_authentication", acr_values="floor42-strong",
   max_age=0` (RFC 9470). A sign-in by the console link (break glass) holds no Keycloak tokens: 403
   `STEP_UP_NEEDS_SIGN_IN`. A browser gets 202 `{redirect}` and follows it, as plan approval does (the sheet said 401
   with the path: the UI treats a 401 as an ended session, so the plan approval's 202 was kept).
5. **Four eyes** is a company setting `FOUR_EYES`, off by default (it needs a second Keycloak user), a normal
   `SettingChanged`. When on, the first signature waits 30 minutes; the desk lists it ("needs a second pair of eyes,
   until ...") with an address to copy. The second person signs in with their own account in **a private window**: the
   countersign callback creates no Floor42 session, gives no cookie, and never relies on the director's online
   session afterwards (it would have ended, M0 point 11). Floor42 reads `realm_access.roles` from the access token of
   that exchange (verified like the ID token: signature, `iss`, `azp`, `sub` equal to the ID token's), refuses the
   same subject (`SAME_PERSON`), and asks the decision point `check(user:<sub>, countersigner, office:floor42)` with the
   contextual tuple `role:floor42-reviewer#member@user:<sub>` when the token holds the role: Keycloak manages who is
   a reviewer, OpenFGA decides (`reviewer but not director`, ADR-0080). Fail closed: no decision point wired or one
   that fails is 503 `FGA_UNAVAILABLE`; nobody can countersign.
5b. **The desk is the director's.** The desk sign-in (`/auth/callback`) opens a session only when the verified access
   token carries the realm role `floor42-director` (401 `DESK_DIRECTOR_ONLY`, no session, no tokens kept). Before that,
   any valid realm user could open the desk, and a second user (the reviewer) would have had director powers, plan
   approval and bound-token issuance included. Step-up and bound-token issuance compare the new `sub` with the desk
   session's, so they need nothing more. The countersign callback creates no session and is not affected: it asks for
   the reviewer role.
6. **The strong sub-flow refuses enrolment.** The realm file binds a browser flow `floor42-browser`: cookie, or forms;
   `floor42-loa1` (condition level 1, password) and `floor42-loa2` (condition level 2, `loa-max-age` 0, OTP form), with
   `acr.loa.map` = `{"floor42-basic":1,"floor42-strong":2}` and the realm role `floor42-reviewer`. The required action
   Configure OTP is **disabled** in the realm: a user without a factor then gets an error ("credential setup
   required"), not a code. People enrol beforehand, inside a window the director opens and closes with
   `iam/enrol-window.sh on|off`. This is the only way found that keeps the realm file self contained; it is not measured
   (see below), and Floor42 does not rely on it: a user who reached the page without a factor and somehow got a code
   would still need `acr` equal to the strong level, which only the second factor gives.
7. **On the phone means TOTP in 1.3.0**, worded "a code from your phone". A passkey on the phone (WebAuthn hybrid
   transport) is a documented option in `iam/README.md`, not shipped, until measured on Chrome and Edge with an Android
   phone and an iPhone and with rp id `localhost` (or `floor42.localhost`).
8. **CIBA is designed, not built.** Keycloak's CIBA needs `floor42-daemon` to hold the grant, a call to
   `/realms/floor42/protocol/openid-connect/ext/ciba/auth` with `login_hint`, a `binding_message` and `acr_values`,
   an authentication channel (HTTP) that reaches the phone, lets the human approve and posts `SUCCEED` with its bearer
   to `.../ext/ciba/auth/callback`, then Floor42 polls the token endpoint (`grant_type=urn:openid:params:grant-type:ciba`
   with the `auth_req_id`). Cost: L, a new external service, and a surface outside loopback.

New event type `ApprovalSigned(act {kind, ref}, seat FIRST|SECOND, subject, acr, authTime, idTokenId)`, approved on
2026-11-16 (Q5). Never a token: the ID token's `jti` only. `subject` is in full in the journal and 8 characters in every
export (ADR-0074). Journal compatibility (ADR-0042): an older binary sets the row aside and carries on; the signature is
recorded in the command of the act, before the act's own event, so no act depends on it to replay.

## Rejected options, with their cost

| Option | Cost | Why not |
|---|---|---|
| CIBA now | L, a phone-reachable channel, a new external service | Keycloak's channel authenticates nobody: its strength is the channel and the device app, which Floor42 would write; the phone must reach the channel (LAN bind breaks loopback only, a tunnel or push relay is a third party). Reopen with a remote access story. |
| Per-mission configurable assurance (a field on permission rules) | a changed `PermissionRuleAdded`; a downgrade drops an assured ASK rule and can widen to a broader ALLOW line | a fixed table is testable as code and has no rollback hole |
| Re-entering the password only (as plan approval) | none | same browser, same person: no second factor, no phone |
| Four eyes by a second Floor42 user account | a user model in Floor42 | Keycloak already manages users and roles |
| Trusting `acr_values` without checking `acr` | none | Keycloak may answer with a lower level, and a plain request reuses a strong SSO session |
| Leaving enrolment on the step-up path | none | a password alone would yield the strong level (measured) |
| Reading roles from userinfo | none | not there (measured): the access token only |
| One-click Countersign in the director's own browser | none | it ends the director's online session (measured) |

## Consequences

- Answering ALLOW to a remote MCP tool in IAM mode costs a sign-in and a code each time; the answer is remembered per
  server and tool as before (ADR-0072), so it is asked once per tool and mission.
- A step-up is a Keycloak online session more; the director's offline sessions are not touched.
- Four eyes needs both people at the machine (Floor42 listens on loopback). The honest scope in a local tool.
- The phone factor proves a human with the phone, now. It does not show what is approved on the phone (CIBA's binding
  message would): the desk shows it before the redirect.
- The pending acts are in memory (5 minutes for a first signature, 30 minutes for the wait). A restart loses them; the
  act is started again. Nothing is journaled before the act itself.

## Limits and what was not measured

- Not measured: what Keycloak returns for a user without a factor once Configure OTP is disabled (expected: the
  `CREDENTIAL_SETUP_REQUIRED` error page and no code). The integration run must try it with a real realm: a user with
  a password and no factor, `acr_values=floor42-strong`, expected no code and never `floor42-strong`.
- Not measured: a phone passkey; `jti` presence in Keycloak's ID tokens (the field is optional in the event).
- Not enforced by this node: the rows RULE and PULL_REQUEST of the table (their facades are not touched), nor GRANT,
  TRUST and SCRIPT_TOKEN (nodes N2, N4, N5 call `AssurancePolicy` from their facades). Until then the click stands for
  those acts, as in 1.2.0.
- The decision point is a port, `Countersigners`, with no decider wired by this node: nobody can countersign until the
  integration wires N1's `Authorizer`.
