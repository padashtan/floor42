# ADR-0075: The human oversight report

Date: 2026-11-16 · Status: Accepted (1.2.0)

## Context

A person who must answer for an agent system (a customer, an auditor, a works council) asks four questions in the
manner of EU AI Act article 14 on human oversight: who supervised, what could the agents do, what did they do, and what
did a human override. The proof dossier (ADR-0071) answers "what did the mission prove"; it does not group the
journal by those four questions, and it holds no permission table, no quarantine, no objection, no director edit.

The rule of the project holds: nothing is shown that the journal does not hold, every line names its events, every text
is masked. No new event type is allowed in this node.

## Decision

`GET /api/missions/{id}/oversight-report` (director only, read only, journals nothing, `Cache-Control: no-store`,
unstable route) returns one JSON document, or Markdown with `?format=md`. `download=true` adds `Content-Disposition`.
It is built by `OversightComposer`, a pure function of the mission's events and the sanitizer; `OversightReports` reads
the journal and builds the sanitizer; `OversightResource` is the route.

Four sections, each a list of lines `{at, kind, actor, scope, text, code, basis, origin, events}` capped at 200 with the
full `count`:

- **supervision**: break-glass sign-ins, plan decisions, approvals (a question answered about an action carries the
  tool and the summary of that action as its scope), approvals forgotten, ticket approvals, proof verdicts,
  quarantines, mission and agent suspensions, cancellations, resumptions.
- **capabilities**: the mission's permission table as approved, replaying `PermissionRuleAdded` and
  `PermissionRuleRemoved`; each rule with its decision, its scope and the event that approved it.
- **actions**: one line per requested action with its outcome code (`EXECUTED`, `DENIED_<reason>`, `FAILED`,
  `AUTHORIZED`, `REQUESTED`) and the basis of its authorization, and one line per agent session.
- **overrides**: what a human did against or beyond the agents: a refusal by the director, objections at acceptance
  (events whose cause is `objections-<n>`), directives, reopened tickets, rejected patches, the director's own file
  edits (path and note only, never the content), acceptance without proof, acceptance of uncommitted work, unsafe mode.

- **Every line names its events.** `events` holds the event id, the type and the number among the mission's events
  (from 1, as in the replay export). A company-wide event (a break-glass sign-in, unsafe mode) has no number; it is
  shown only when it falls between the mission's first and last event, and its scope says `COMPANY`.
- **A missing fact is a limit, not a guess.** `limits` holds codes the page translates: regular director sign-ins are
  not journaled (only the break-glass path is); the permission table is empty when no rule was journaled; some actions
  have no recorded outcome; names in free text are not detected. A claim about oversight that no event backs is not
  written.
- **Always masked.** Every text goes through `ReplaySanitizer`, with the folder placeholders of the replay export. The
  JSON is checked for the session token before it leaves (empty 500 rather than a leak). No parameter unmasks.
- **Markdown is inert for agent text.** The Markdown writer turns agent text into text only: no link, image, table
  cell, HTML or code span can be formed, a bare address does not autolink (a Java twin of the dossier's `inert`).
- **The shell guard refuses a command naming `oversight-report`**; Floor42's MCP server offers agents no such tool.
- **A Work menu entry** `oversight`, next to the proof dossier. The panel reads the JSON, writes the sentences in the
  page's language from the codes, and links the two downloads.

## Alternatives not taken

- *Extend the proof dossier.* It would become two documents in one, and the dossier's format is fixed (ADR-0071). The
  two stay separate; the oversight report uses none of the dossier's internals, which sit in another API context.
- *Markdown localised on the server.* The server renders no label (ADR-0021). The Markdown of `?format=md` uses fixed
  English words and the JSON codes; the panel shows the page's language.
- *Count the sign-ins from the session registry.* Not in the journal: it would be a claim the journal does not hold.
- *A new `DirectorSignedIn` event.* Needs the director's approval; a candidate, not built.

## Consequences

- One route, one panel, no new event, no schema change.
- Not a compliance certificate: it organises the journal's facts under the four questions and names what is absent.
- `openapi.json` must be regenerated for the route; then the `OversightReport` line is enabled in `contract.ts`.
- Reopen when regular sign-ins or the director's per-action approvals are journaled with their scope, or when a
  reader needs to verify the report without trusting the daemon (the hash chain of ADR-0079 would give that).
