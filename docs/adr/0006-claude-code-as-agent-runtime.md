# ADR-0006: Agents are Claude Code sessions, not API calls

Date: 2026-10-20 · Status: Accepted. Runtime spike (I1b, branch `spike/runtime`) proved the critical path on 2026-10-20; every governance hook must set `"onFailure": "block"` and sessions run with `--restricted`.

## Context
The director wants agents to be real Claude Code sessions driven from the game, not a home-grown loop over a model API. Sources checked on 2026-10-20: https://code.claude.com/docs/en/headless, https://code.claude.com/docs/en/legal-and-compliance, and `claude --help` (v2.1.304).

## Decision
- Port `AgentRuntime` (start, send directive, interrupt, resume, event stream). First adapters: `ClaudeCodeRuntime`, and `ReplayRuntime` (recorded transcripts, used by simulation and tests).
- Java starts `claude -p --input-format stream-json --output-format stream-json` directly. No Node sidecar: the Agent SDK only exists for TypeScript and Python and drives the same binary.
- Governance through `PreToolUse` / `PermissionRequest` hooks answered by the Office policy, and an Office MCP server for structured outputs (tickets, questions, reports, reviews). No approval tool is ever exposed to agents.
- One git worktree per agent session. Session settings are generated outside the worktree, and project setting sources are excluded, so an agent cannot rewrite its own rules.

## Consequences
- Office never handles Claude credentials. Each user authenticates the unmodified binary with their own subscription or API key. Office must not pay for or resell usage, and must not use Anthropic names or logos in its own branding.
- Pro/Max usage limits assume ordinary individual use; parallel agents consume them quickly. Usage capacity becomes a visible game resource.
- `total_cost_usd` is a client-side estimate and is displayed as `ESTIMATED`.
- `--bare` does not read subscription credentials, so isolation from the user's own `~/.claude` setup must come from explicit flags.
- The stream-json protocol evolves: it is isolated in one adapter, contract-tested against recorded transcripts, with a minimum version check (`claude --version`). Since 2026-10-22 a line or block the adapter does not know is reported, not dropped (`Unfamiliar`, shown in `/api/status`).
- Local models later means a gateway behind Claude Code, or another `AgentRuntime` adapter.

## Reopen if
Anthropic's terms change for this usage, or the CLI protocol cannot carry what the governance layer needs.
