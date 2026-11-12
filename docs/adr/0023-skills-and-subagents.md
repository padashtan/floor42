# ADR-0023: Agents may load skills and start subagents

Date: 2026-10-29 · Status: Accepted

## Context
Agent sessions saw the names of the director's skills and of Claude Code's subagents in their `init` line, but could use neither: Office offered only file, shell and web tools. The director asked for both, fully open, rather than an allow-list per agent.

The risk was that a subagent would escape governance. It was measured before deciding, with a real session on Claude Code 2.1.312 (Haiku, 0.004 USD estimated; recorded, redacted, in `transcripts/claude-2.1.312-subagent.jsonl`). The session ran under `--restricted` with a `PreToolUse` hook on every tool, and started an Explore subagent. The subagent's Glob and Read reached the hook with the same session id and its own `agent_id` and `agent_type`, and its calls came in the session's stream.

## Decision
- `Skill` and `Agent` are two more lines of the mission's permission table (category OTHER), so the director can set them like any tool. A new mission starts with both allowed. Missions created before have no line for them, so they stay forbidden (no rule means deny).
- A subagent's calls are the agent's calls: the same hook, the same table, the same worktree confinement, the same per-agent denials. A reviewer's subagent cannot write, because the reviewer cannot.
- Reviewers and committee members are offered both too: reading a skill or delegating a search changes nothing they review.
- The brief says what each one does. A call is shown as the skill's name, or as `<subagent type>: <description>`.
- Claude Code lists the subagent tool as `Task` and names it `Agent` in its hook: both map to the same line.

## Consequences
- A session can cost more: each subagent spends tokens from the same plan. The mission budget still holds the next session, not a running one.
- Skills are the director's own (`~/.claude/skills`): what they say reaches agents as instructions. Each action they lead to still goes through the table.
- Claude Code 2.1.312 sends two new stream lines with subagents, `system/task_progress` and `system/task_updated`. The drift detection (`Unfamiliar`) would have reported them; they are now known.

## Reopen if
A subagent's call is seen that did not reach the hook, Claude Code adds a way for a subagent to run under other settings, or the cost of subagents makes the director want an allow-list per agent.
