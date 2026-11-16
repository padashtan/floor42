# ADR-0074: Identity claims in the journal

Date: 2026-11-16 · Status: Accepted (v1.2) · Amends ADR-0064 point 10 and ADR-0071

## Context

`AgentTokenIssued` holds the `jti`, the issuer and the expiry, never the token (ADR-0064 point 10). The proof dossier
(ADR-0071) therefore could not show who the token spoke for: `sub`, `azp`, `act` and the scopes were a listed limit
(`identity.claimsNotJournaled`), and the dossier could only mark "for the director" as derived. The director approved
the change of this one event for v1.2 (questions Q1 and Q7).

## Decision

`AgentTokenIssued` gains four optional fields, read by Floor42 from the token its verifier accepted, never from a
parameter:

| Field | Claim |
|---|---|
| `subject` | `sub`, an opaque id |
| `authorizedParty` | `azp` |
| `actor` | `act.client_id` |
| `scopes` | `scope`, split on spaces, sorted, no duplicate; absent when the token has none |

- **Never the token.** The fields hold ids and scope names. A test fails if a recorded text looks like a JWT. No name and
  no e-mail address.
- **Old rows read with nulls.** The four fields are written only when set (`WireDefaults.TokenClaims`, ADR-0009): a row
  written before v1.2, and a token that lacked a claim, are stored exactly as before. Nothing is rewritten.
- **Simulated tokens** record the claims of the simulated issuer (`simulated-director`, `simulated-<agent>`) under
  Origin `SIMULATED`, as before. Their event line keeps the "(simulated)" mark.
- **The journal holds `subject` in full** (a local file, owner only). Every export shows its first 8 characters
  followed by an ellipsis: the proof dossier, the replay export and the identity audit export (`subject` column).
  There is still no unmask switch (ADR-0071).
- **Dossier.** The identity rows show the four claims as `JOURNAL` facts for a session of v1.2. A session of an older
  version shows "Not recorded: this session ran before v1.2" and the limit line stays. The limit leaves the dossier of
  a mission only when every one of its sessions recorded its claims.
- **Event line.** A `claims` variant (and `simulated.claims`) adds "on behalf of {subject}, acting as {actor}, scopes
  {scopes}" after the old sentence; a row without claims renders the old sentence.

## Rollback

A journal is read by a version that knows its fields (ADR-0042: an unknown field sets the row aside as
`NEWER_VERSION`, it is never misread). After a downgrade to a version before v1.2:

- every row written before v1.2, and every row of a mission run with IAM mode off, reads as before;
- an `AgentTokenIssued` row written by v1.2 carries the new fields, so the older binary sets that one row aside. The
  other rows of the mission are read. The identity facts of those sessions are missing from the older dossier and
  audit until Floor42 is upgraded again; nothing is lost. The release note lists this under limits.

`RollbackEventTypesTest` is unchanged: no event type is added, only fields.

## Alternatives not taken

- *A new event `TokenClaimsRecorded`.* Does not change an existing row, but a v1.1 reader would set aside every one of
  them as an unknown type, and the dossier would join two events per session. v1.2 allows one changed event and no new
  event type.
- *Reading the claims from the live token when the dossier is built.* The claims exist in memory only while a session
  lives (ADR-0071 rejected it): a later dossier would lack them or show them as recorded.
- *The whole subject in exports.* An opaque id still identifies a person to whoever knows the Keycloak realm; 8
  characters tell two subjects apart in a review without handing out the id.
- *The claims on every broker call.* A call already journals the exchanged `jti` and audience; out of scope.

## Consequences

- `openapi.json` is regenerated (the dossier's identity rows and the audit line gain fields), `types.dossier.ts` is
  aligned. The event goldens (`event-codes.json`, `event-views.txt`, the domain scenario) are re-recorded once at
  integration.
- ADR-0064 point 10 and ADR-0071 carry a dated note pointing here.
- Reopen when a delegation becomes an event (`DelegationGranted`), or when claims of the exchanged resource tokens
  must be recorded.
