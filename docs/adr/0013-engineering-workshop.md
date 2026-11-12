# ADR-0013: The engineering workshop

Date: 2026-10-20 · Status: Accepted

## Context
Since ADR-0012, agents change code in their worktree. The director is a developer too (spec section 11): they must see what an agent changed, intervene in the code, direct the agent, and accept or reject a patch, with every intervention traced and never silently overwritten.

## Decision
- **Office does the file work, the journal records it.** The workshop is served by the daemon: Office runs git in the agent's worktree on the director's behalf, then records a fact. New events: `WorkspacePrepared` (path, branch, base commit), `DirectorEditedFile` (path, note, the diff git measured), `DirectiveGiven`, `PatchAccepted` (commit, files), `PatchRejected` (reason, archive, files). The domain keeps them as the mission's trace of interventions.
- **Same confinement as agents.** Every workshop path goes through the confinement of ADR-0012: the workshop cannot reach the director's checkout or write `.git`, `.claude` or `.mcp.json` either.
- **Human edits are protected by code.** After the director edits a file, any agent write to that path asks the director every time, whatever the table says (a forbidden write stays forbidden). Briefs list the edited files and every directive.
- **Directives resume the mission.** A directive is recorded on the ticket, reopens it if it was done or in review, and Office restarts the producer, then a new independent review.
- **Accept commits on the agent's branch; reject archives then restores.** Accepting commits the pending changes on `office/<mission>-<agent>` with the director's git identity, or a neutral one naming Office if none is configured. The director merges the branch whenever they choose. Rejecting first archives the changes as a patch under the data directory, then restores the worktree to its last commit. Nothing is ever lost.
- **Never under a running agent's feet.** Edits, directives, accepting and rejecting are refused while an agent session of the mission is open.
- **Line endings are kept.** An edit keeps the file's existing line endings. A browser sends LF; without this, every line would show as changed.

## Consequences
- The director sees and steers the work without leaving Office, and every intervention has an author, files, a diff, a date and its effect in the journal.
- Worktrees created before this ADR are recorded once at startup, with their current commit as base.
- Not yet covered (later increments): asking for several approaches, running tests from the workshop (I8, verification), requesting a review without a directive.

## Reopen if
Several producers share a worktree, or the director wants Office to merge into their checkout.
