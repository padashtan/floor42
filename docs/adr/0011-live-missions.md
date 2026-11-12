# ADR-0011: How live missions run

Date: 2026-10-20 · Status: Accepted

## Context
I4 turns the proven runtime spike into product: real Claude Code sessions doing real work on the director's repositories, governed by Office, without losing the thread when a session's context runs out, and testable without spending tokens.

## Decision
- **Workspace:** agents never work in the director's checkout. Each mission and producing agent gets a git worktree under the data directory, on a branch `office/<mission>-<agent>` created from the repository's HEAD. The reviewer works in the producer's worktree. Worktrees are kept as evidence.
- **Plan:** Office proposes a plan (one investigation ticket) with a conservative permission table: Read/Glob/Grep auto, Bash ask once, Write/Edit and network forbidden until isolation exists (I6). Superseded for Write/Edit by ADR-0012: ask once, confined to the worktree. Nothing runs before the director approves; any line can be changed before or after.
- **Briefs from the journal:** each session receives a brief rebuilt from persisted facts (objective, ticket, criteria, permissions in force, the director's answers, previous reviews), never a chat history. A session may lose its context; the company does not.
- **Independent review:** a second agent, in its own session with read-only tools, verifies the deliverable against the files and ends with a `VERDICT:` line. A missing or unrecognized verdict is UNVERIFIABLE and escalated to the director. A rejection sends the ticket back once with the findings; a second rejection is escalated.
- **Failures:** a failed session asks the director whether to retry; nothing is retried silently.
- **Hooks:** answered by the mission policy on a virtual thread; a pending decision holds the call until the director answers or Claude Code's hook timeout is near, then the call is refused (fail closed).
- **Tests:** a protocol-faithful fake of the Claude Code CLI (`office-server/src/test/resources/fake-claude/FakeClaude.java`) plays scripted sessions through the real hook endpoint. No test spends tokens.

## Consequences
- Verified on 2026-10-20 with a real mission on a sandbox Java repository: about 0.054 USD estimated for producer and reviewer; the reviewer found a real issue the producer had only flagged as a risk.
- The director's repository gains one branch per producing agent and mission.
- One producer and one reviewer per mission for now; multi-ticket plans and the committee come later.

## Reopen if
Agents need to change files outside their worktree, or missions need several producers in parallel.
