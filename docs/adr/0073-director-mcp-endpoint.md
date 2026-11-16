# ADR-0073: The director's MCP endpoint

Date: 2026-11-16 · Status: Accepted (v1.2)

## Context

`/mcp` (ADR-0027) serves the agent sessions Floor42 starts, with the session's own token. The director's API is under
`/api`, guarded by `LocalAccessGuard`: the browser's cookie, or a script token (ADR-0044) of scope `read`,
`missions:write` or `all`. A director who works in a Claude Code session of their own, with no scene open, has no way to
use the desk as tools. A model holding the director's credential is the risk to design for: it reads text written by
agents (titles, questions) and could be told by that text to approve what agents do.

## Decision

`POST /api/mcp` (GET answers 405) is a second MCP server, streamable HTTP with plain JSON answers like `/mcp`, server
name `floor42-director`. Methods `initialize`, `ping`, `tools/list`, `tools/call`; anything else is "method not found".

1. **Script tokens only (Q6).** A script token as `Authorization: Bearer`. The director's own token, a browser cookie and
   an agent session's credential are refused (403; the agent's by the guard as on any `/api` route, the other two by the
   resource with `SCRIPT_TOKEN_ONLY`). No token: 401. Keycloak bearer tokens are out of scope here.
2. **Scope per tool.** The guard lets a POST to this one path through for every scope (the only change to
   `LocalAccessGuard`: one path, one method). The resource then checks the scope of each tool: `list_missions`,
   `get_mission`, `list_questions`, `get_costs` need `read`; `create_mission` and `answer_question` need
   `missions:write` (`all` too). A `read` token calling a write tool gets a tool error `SCOPE` and nothing is written.
   A `read` token still gets 403 `SCOPE` on every other POST route.
3. **No approval, whatever the scope (Q5).** `answer_question` answers only an agent's own free question
   (`ask_director`: kind `OPEN`, asked by an agent, tied to no action). Any other (an approval, a plan, a delivery, and
   Floor42's own decision questions such as a retry or a dropped ticket, which carry agent-written context and start
   paid work or cancel tickets) answers `HUMAN_ONLY`, for scope `all` too. A domain refusal (a closed question, an
   answer outside the options) comes back as a tool error inside the envelope, since it may quote agent-written options.
   This is stricter than the REST API, where a `missions:write` token may already approve a plan. Permissions, IAM,
   terminal and settings are not tools.
4. **Agent text is data.** Every tool result goes inside the `BEGIN-UNTRUSTED-<nonce>` envelope (`Untrusted.quote`,
   ADR-0027's helper), after a line that says it is data, not instructions.
5. **Same views as the API.** A tool calls the resource the matching REST route calls and returns its JSON: the same
   masking, the same `Origin` labels (a simulated or estimated figure stays labelled). The call is one line of the API
   console; `create_mission` and `answer_question` are journaled by the commands they run, as from a script.
6. **`create_mission` takes a repository.** The REST route needs one (except for an idea committee); the tool has an
   optional `repository` argument for it, no more.

## Options not taken

- Approvals with scope `all`: the REST API already lets a token do that, but a model reading agent text is the case where
  it matters; one human act is cheaper than one injected approval. Reopen if a director asks for it, with a confirm step.
- The director's own token or the cookie: the token is full scope and never expires with the call; a cookie belongs to a
  browser. A script token is revocable, scoped and bounded (30 days).
- A new package `api/director`: not needed. `api.gateway` may use `api.mission` and `api.cost` (one line in
  `ArchitectureTest`), which keeps `Untrusted` package-private.

## Consequences

- A tool result is text with the envelope around JSON: a client parses between the two nonce lines.
- Tokens die at a restart (ADR-0044): an MCP client must be given a new one. `docs/headless.md` says so.
- The doc advises a `read` token first.
- Revisit if a director needs approvals through the endpoint, or Keycloak bearer tokens for scripts in IAM mode.
