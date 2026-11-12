# ADR-0027: Office's own MCP server; agents ask the director mid-session

Date: 2026-10-29 · Status: Accepted

## Context
An agent that needed the director's decision mid-session had no way to ask (D4). It could only request a tool call that Office would put to the director, or guess and say so in its report.

The protocol was measured before building, with a real Claude Code 2.1.312 session (Haiku, 0.001 USD) against a stand-in MCP server and hook:
- `--mcp-config` with an `http` server: the header `Bearer ${VAR}` is expanded from the session's environment;
- the client sends `server/discover`, then `initialize`, `notifications/initialized`, a GET for a stream, `tools/list`, then `tools/call`;
- the tool is named `mcp__office__ask_director`. It reaches the PreToolUse hook before the server, and `--tools` does not hide it;
- `tools/call` carries the tool-use id in `_meta.claudecode/toolUseId`.

## Decision
- **The server.** Office serves MCP at `/mcp` on loopback: streamable HTTP, plain JSON answers, no stream (a GET is refused). It is authenticated by the session's hook token, like the hook; the director's token is never given to agents. `server/discover` and any unknown method get "method not found".
- **The configuration.** Every live session gets Office's server through `--mcp-config`, and `--strict-mcp-config` keeps every other server away.
- **One tool, `ask_director(question, options?, recommendation?)`.**
  - It puts a blocking `OPEN` question on the director's desk, asked by the agent, about its ticket. The agent shows as waiting for the director.
  - The session waits for the answer, up to `office.claude.question-timeout-seconds` (30 minutes). Claude Code's own `MCP_TOOL_TIMEOUT` is set a minute longer.
  - With no answer in time, the agent withdraws its question and is told to go on with its own judgment and say what it assumed.
- **Governance unchanged.** The call is an action of category OFFICE: the hook records it, and Office tools are allowed unless the director sets a rule. The question itself is a journaled fact.
- **The brief.** Each brief names the tool and says when to use it: only for a decision that is the director's.

## Verified end to end
On a throwaway Office instance (its own data directory and port), a real mission with a Haiku developer: the agent called `ask_director` with the options it was told, the question appeared on the desk, the answer "bonjour" came back into the session, and the agent wrote `GREETING.txt` with "bonjour" after its Write request was approved. Developer session: 0.0039 USD estimated.

## Consequences
- A session waiting for an answer counts as alive for the watchdog, and keeps its process (and its plan slot) open while it waits.
- The fake Claude Code plays the same exchange (`[fake:ask=...]`). Server tests cover an answer, a withdrawal on timeout, and a caller without a token.

## Reopen if
Claude Code changes its MCP handshake, starts requiring the server-sent stream, or agents ask too often (then a per-session limit, or ASK as the default rule for `ask_director`).
