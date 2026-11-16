# ADR-0072: Governed external MCP servers, with the agent's own token

Date: 2026-11-16 · Status: Accepted (v1.2). The director answered Q2, Q3 and Q4 as recommended. Amends ADR-0027 and
ADR-0064 point 11. No new event type. The wire details (session header, event-stream answers) come from the public
MCP specification and from a local stub; one real remote server is still to be measured before the release. Revised
after the review of 2026-11-16: points 2, 4, 6 and 9 and the approval scope changed as written below.

## Context
ADR-0027 gave an agent one MCP server, Floor42's own. ADR-0064 added `call_resource`: an agent reaches an external HTTP
resource without holding a credential, through one Token Exchange per call. A remote MCP server is an HTTP resource
that speaks JSON-RPC. Agents should reach the useful ones (search, tickets, documents) with their own identity, and the
director should decide which tools they may run.

Without a limit, MCP is the easy way to widen an agent's reach: a server can change a tool's description to steer the
agent, and a tool name can hide what it does. So the director's allow-list has to be about the tool as it was read, not
about its name.

## Decisions

1. **A remote MCP server is a catalogue resource plus an endpoint path.** `McpServer(resource, path, tools[])`, one
   per resource, named by it. The resource must allow `POST` and have a path prefix that covers `path`
   (`MCP_RESOURCE_UNFIT` otherwise). All the rights stay where ADR-0064 put them: the agent's grant on the resource
   and the mission's delegation. Remote MCP needs IAM mode (Q4): without it there is no identity to exchange, the
   tools answer `IDENTITY_UNAVAILABLE`, and no static API key is accepted.
2. **Tools are pinned by hash.** The director refreshes the list (`initialize`, `tools/list`, with a chosen agent's
   token for a chosen mission, on the director's behalf, Q3). Each tool is stored with its name, description, input
   schema and the SHA-256 of those three (keys sorted). A new tool arrives unticked. A ticked tool whose definition
   changed is unticked and flagged `changed` until the director ticks it again. A tool name must be one line of
   `[A-Za-z0-9_.-]` up to 64 characters, else it is not pinned; a name the server lists twice is not pinned either
   (which definition did the director read?).
   - **Tied to the endpoint.** The server record keeps the resource's base URL and audience at the time of the pin.
     The catalogue and this file are separate, so a resource renamed to another address would otherwise keep its
     ticks. When the catalogue's values differ, every tool is unticked and flagged `changed` (at the next call, listing
     or refresh), ticking is refused (`MCP_ENDPOINT_CHANGED`) until a refresh has read the tools at the new address.
   - **A tick carries the hash the director saw** (`PUT .../tools` takes `ticks: [{name, sha256}]`); a tool whose
     pinned hash moved on since is refused (`MCP_TOOL_CHANGED`).
   - A damaged file is refused by every write (`MCP_CATALOGUE_DAMAGED`), never replaced by an empty one.
3. **Two Floor42 tools, no change to `--strict-mcp-config`.** The agent still talks to Floor42 alone.
   - `list_mcp_tools(server?)`: the ticked tools of the servers this session may use, read from the file. No network
     call. Category FLOOR42. Its answer is inside the untrusted envelope, because descriptions come from the remote.
   - `call_mcp(server, tool, arguments)`: category **NETWORK**, built-in line **ASK**, in the permission table and the
     brief through `ToolCatalog.GOVERNED_TOOLS`. A missing table line means DENY, as for any NETWORK tool. A mission
     whose table was filled before this release has no line for it and is refused until the director sets one.
4. **A call is the broker's.** The hook authorizes once; an IAM session is required; the server is declared and the
   tool ticked; then one token exchange per HTTP request (`initialize`, `notifications/initialized`, then the
   request), each through
   `ResourceBroker`: SSRF guard and address pinning, no redirect, no cookie, a size cap, a deadline, the token checked
   (audience, life, actor) and scrubbed from anything the server echoes. The broker gained a call that sends only
   `Accept`, `Mcp-Session-Id` and `MCP-Protocol-Version`, and keeps the session id of an answer, which is never shown.
   The hook's authorization is checked for this tool (`authorized(toolUseId, tool)`): an id the hook gave to another
   tool buys nothing. The token is removed again from the text decoded out of the JSON, so an answer that writes it
   with unicode escapes does not carry it either.
5. **The answer.** The first line is the only part the journal keeps (the stream parser cuts it, as for
   `call_resource`): `MCP <server> <tool> -> ok|error (exchanged token <jti> for <audience>)`. The rest is the
   server's text inside the `BEGIN-UNTRUSTED-<nonce>` envelope. A tool error the server reports is an answer
   (`-> error`), not a Floor42 refusal, so that it is not journaled whole. `InjectionMarkers` raises a session
   signal as for `call_resource`.
6. **Refusals** are tool errors with their code: `NOT_AUTHORIZED`, `IDENTITY_UNAVAILABLE`, `MCP_SERVER_UNKNOWN`,
   `MCP_TOOL_NOT_ALLOWED`, `MCP_PROTOCOL`, `INVALID_ARGUMENTS`, and the broker's own (`RESOURCE_NOT_GRANTED`,
   `HOST_NOT_ALLOWED`, `SCHEME_NOT_ALLOWED`, `RESOURCE_UNREACHABLE`, `IDENTITY_REFUSED`). The order is: server unknown,
   not granted, tool not ticked, then the broker. An agent without the grant learns nothing about a server's tools.
   The message of `MCP_SERVER_UNKNOWN` is fixed: what the agent typed is not echoed into the journal's error text. The
   four codes of Floor42's own decisions (`MCP_SERVER_UNKNOWN`, `MCP_TOOL_NOT_ALLOWED`, `INVALID_ARGUMENTS`,
   `NOT_AUTHORIZED`) count as refusals in the journal's reading (`Action.refused()`, whatever the category);
   `MCP_PROTOCOL` stays a failure.
7. **No secret in a brief or a journal row.** The summary of a `call_mcp` request is `<server> <tool> <arguments>` with
   the arguments masked by `SecretMasker`. The brief holds the tool's name and its decision, never an address, an
   audience or a remote description.
8. **The catalogue is not journaled (Q2).** Declared servers and ticked tools are files, like the resource catalogue
   (ADR-0064 point 12). Every agent call is journaled as an action that names the server and the tool; who ticked
   which tool is not in the journal. That is a known limit. The event (`McpToolsAllowed`) goes with the chained
   journal in v1.3.

## Shortcuts, stated in the code
- One handshake per call, three exchanges, no session reuse. Upgrade when a measured call is too slow.
- The session is never closed with a DELETE. Upgrade if a real server limits open sessions.
- Only the first page of `tools/list` is read.

## Approval scope (the director's decision, review of 2026-11-16)
The table's ASK is asked once per approval scope. For `call_mcp` the scope is the agent, the tool, **the server and
the remote tool** (the first two words of the action's summary): a yes for `search` on one server does not cover
`delete` there, nor `search` on another server. A refusal is remembered the same way. The default line stays ASK; a
line "ask every time" still gives a question per call.

## Out of scope
Stdio (local process) servers; MCP resources, prompts, sampling and notifications; a remote server without IAM mode; a
static API key for a server; a permission rule per server or per tool; session reuse; the RAG server (v1.3).

## Consequences
- The director sees what an agent may run, and a changed tool stops being allowed without anyone remembering to check.
- One more way out of the sandbox exists, through the broker only; every refusal above has its test.
- Two exchanges per call load Keycloak a little more.
