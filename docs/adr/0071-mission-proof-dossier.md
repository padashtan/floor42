# ADR-0071: The mission proof dossier

Date: 2026-11-13 · Status: Accepted

## Context

A director who shows a mission to someone else (a reviewer, a customer, an auditor) needs one file that says what the
mission did, with the proof behind each line. The replay export (ADR-0035) shows the mission as it happened; it is
not a summary and it proves nothing by itself. The end-of-mission report (ADR-0039) summarises, but for the director's
screen only.

Floor42's rule is that nothing estimated, simulated or derived is shown as a measured fact, and that every event
carries an `Origin`. Identity is the hard case: `AgentTokenIssued` holds the jti, the issuer and the expiry, but no
`sub`, `azp`, `act`, scope or audience, and the delegation lives in `<data>/iam/delegations.json`, not in the journal.
No new event is allowed in v1.1.

## Decision

`GET /api/missions/{id}/proof-dossier` (director only, read only, journals nothing, `Cache-Control: no-store`, unstable
route) returns one JSON document: identities, refused actions, approvals, costs, checks, deliverables, a summary of
changes, then what was masked and the limits. It is built by `ProofDossiers.compose`, a pure function of the mission's
events, the mission report, the delegation and the sanitizer.

- **Every fact names its source.** `JOURNAL` (with the numbers of its events, as in the replay export, and their
  `Origin`), `GIT` (read now, with the read time), `STATE` (Floor42's state at export time, a delegation) or `DERIVED`
  (computed by Floor42: the agent client name `floor42-agent-<id>`, "for the director", the token audience constant, the
  cost total). A derived field is listed by name in the fact. A figure carries its basis (`MEASURED`, `ESTIMATED`,
  `REPORTED`, `SIMULATED`, `UPPER_BOUND`); a simulated session costs 0 and says so; an upper bound is shown apart and
  never added to the total; no cost reported is `null`, not 0.
- **No claim the journal does not hold.** `sub`, `act` and the scopes are not shown, not reconstructed from the live
  token, and the dossier says so as a limit (`identity.claimsNotJournaled`). The demo takes them from the Identities
  panel while a session lives. Recording a claims summary in the journal is a v1.2 candidate, with its own approval of
  the event.
- **Always masked.** Every text goes through `ReplaySanitizer` (secrets, the session token, local paths, clipping), the
  same one as the replay export, with the same folder placeholders (`ReplayExports.roots`). There is no unmask switch
  and no query parameter that changes the output. The JSON is checked for the session token before it leaves: the
  response is an empty 500 rather than a leak. Not exported at all: tool outputs beyond the first line of a broker
  result, the contents of the director's file edits, setting values, git author names, the vault entry name of the
  delegation, the proof token of an unproven acceptance. Commit ids are cut to 12 characters so the secret masker does
  not take them for a key.
- **The page writes the Markdown.** The server renders no label: the page builds the Markdown from the masked JSON in
  its own language, so a file is exactly what the panel shows. Agent text is made inert (no link, image, table cell or
  HTML can be formed from it, bare addresses do not autolink): a viewer opening the file loads nothing.
- **The summary of changes counts what git says now**: commits beyond the project branch and uncommitted paths per
  worktree, source `GIT`. No line counts, no per-status counts (the report holds paths, not statuses).
- **The shell guard refuses a command naming `proof-dossier`**; Floor42's MCP server offers agents no such tool.

## Alternatives not taken

- *Show decoded claims from `IamSessions`.* They exist in memory only while a session lives: a dossier read later
  would be missing them, or worse show them as recorded. Rejected: it breaks the Origin rule.
- *A new event holding the claims or the delegation.* Needs the director's approval and a version of the event
  catalogue; deferred to v1.2.
- *Markdown served by the API.* Needs a label round trip or a second catalogue on the server. Scripts get the JSON.
- *An unmask switch.* A masked-by-default file with a switch is one click from a leak. Decided: never in v1.1; a later
  one would need a step-up.
- *A signed or hashed dossier.* Out of scope for v1.1.

## Consequences

- One extra route and one panel, no new event, no change to the replay export (`roots` and `absolute` become
  package-private; `IdentityAudit.Line` gains the event id).
- E-mail addresses and names written in free text are not detected by the sanitizer; the dossier lists that as a limit
  and leaves out git author names. An e-mail pattern added to `ReplaySanitizer` also applies here, because the dossier
  uses the same sanitizer.
- `openapi.json` must be regenerated for the route, then the `ProofDossier` line enabled in `contract.ts`.
- Reopen when claims enter the journal (identity rows become `JOURNAL` facts), or when someone needs to verify a
  dossier without trusting the daemon that wrote it (signing).
