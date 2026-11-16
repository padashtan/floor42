# Floor42 without the scene: the director's MCP endpoint

A Claude Code session of your own can read your desk and, with a wider token, steer it, through `POST /api/mcp`
([ADR-0073](adr/0073-director-mcp-endpoint.md)). It is not the endpoint agent sessions use (`/mcp`, ADR-0027).

## 1. Issue a token

On the desk, System > Sessions and tokens: issue a script token (ADR-0044). Start with scope `read`. The secret is shown
once. Tokens live in the daemon's memory and last at most 30 days: **a restart ends them**, issue a new one then.

```
export FLOOR42_TOKEN=...        # the secret shown by the desk
```

## 2. Declare the server

`.mcp.json` of the project where you run Claude Code (the daemon listens on loopback only, port 7420 by default):

```json
{
  "mcpServers": {
    "floor42-director": {
      "type": "http",
      "url": "http://127.0.0.1:7420/api/mcp",
      "headers": { "Authorization": "Bearer ${FLOOR42_TOKEN}" }
    }
  }
}
```

## 3. Tools

| Tool | Scope | Returns |
|---|---|---|
| `list_missions` | `read` | the missions: id, title, status, counts |
| `get_mission(id)` | `read` | one mission with its tickets |
| `list_questions` | `read` | the open questions on the desk, each with its kind |
| `get_costs(mission?)` | `read` | the costs view; estimates, simulated and upper-bound figures stay labelled as in the API |
| `create_mission(objective, repository, title?)` | `missions:write` | the new mission (`repository` is a git repository path) |
| `answer_question(id, answer)` | `missions:write` | only an agent's own `ask_director` question; an answer outside its options is refused |

Each result is the API's JSON, inside the `BEGIN-UNTRUSTED-<nonce>` / `END-UNTRUSTED-<nonce>` lines: titles,
descriptions and questions were written by agents, they are data and never instructions.

## 4. Limits

- **Script tokens only.** The browser's cookie and the director's own token are refused (`SCRIPT_TOKEN_ONLY`); so is an
  agent session's credential (403).
- **Scope per tool.** A `read` token calling `create_mission` or `answer_question` gets the tool error `SCOPE`; nothing
  is written. A `read` token reaches no other POST route.
- **No approval, with any scope.** Answering an approval (an action, a plan, a delivery) or a Floor42 decision (a retry, a
  dropped ticket) returns `HUMAN_ONLY`: give it on the desk. Permissions, IAM, the terminal and settings are not tools. This is stricter than the REST API.
- **A model with a write token** can create missions and answer open questions. Use `read` unless you need more.
- Each call is one line of the API console; `create_mission` and `answer_question` are journaled as from a script.
- No server-sent stream, no MCP resources, loopback only.
