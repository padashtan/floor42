# ADR-0036: Pull requests on GitHub, on the director's click, with the director's own gh

Date: 2026-10-31 · Status: Accepted

## Context
Work accepted in the workshop is a commit on an agent's branch (ADR-0013); until now the director pushed it and wrote the pull request by hand, copying the objective, the reviews and the checks from Office. The director asked for a button: "Open a pull request", with a description composed from the mission. It is the first action of Office that leaves the machine: a push to a remote and a write on GitHub, which cannot be taken back by Office.

## Options
| Option | What it costs | Verdict |
|---|---|---|
| A. Office calls GitHub's REST API with a token it stores | A secret in Office's data directory, an OAuth or token flow to build, a new credential an agent's shell could read (D12) | Rejected: Office never holds a GitHub credential |
| B. Office runs the director's own `gh` (already signed in), behind a seam | One external tool to install; gh's output to parse; nothing stored | **Taken** |
| C. Office composes the text and shows the commands to copy | No outward action at all; the director types `git push` and `gh pr create` | Rejected: the director asked for the click; kept as what the preview shows |
| D. Agents open pull requests themselves (an MCP tool, a brief) | An agent acting on GitHub with the director's login | Rejected: outward actions are the director's only |

## Decision
- **Director only.** Routes under `/api/missions/{id}/pull-requests` (the director's session token); no MCP tool, nothing in briefs. The domain refuses any actor but the director (`FORBIDDEN`, Office's own SYSTEM actor included) and any simulated mission (`SIMULATED_MISSION`). `ShellGuard` refuses an agent's `git push` and any `gh` call, whatever the permission table says (a text check, like the rest of it: a tripwire for plain commands, not a boundary; with the sandbox off, `git --git-dir .git push`, quoting, aliases, variables or a PowerShell no-break space pass it. The boundaries are the sandbox and the permission table, and the push itself goes to the URL the preview showed, with a repository whose own settings redirect a push (`pushurl`, `core.sshCommand`, `insteadOf`...) refused by `NetworkConfigGuard`).
- **Preview, then confirmation.** `GET …/preview?workspace=` pushes nothing: remote (the branch's upstream, else `origin`, else the only remote) with its configured URL, the GitHub repository it names (`host/owner/name`), branch and commit, base (the repository's default branch, `gh repo view`), title and body, gh's state (`gh auth status --hostname`). The dialog shows all of it; title and body are editable. `POST` carries what the director confirmed; Office refuses `PREVIEW_STALE` when remote, branch or base differ from what it would push now.
- **Never forced, never the default branch.** `git push --porcelain -- <remote's url> refs/heads/<b>:refs/heads/<b>`: no `+`, no `--force`; a remote branch that moved elsewhere is refused by git itself. Refused heads: `main`, `master`, the repository's default branch, a detached HEAD, a name outside `[A-Za-z0-9._/-]` or starting with `-`. A workspace's branch needs work the director accepted (`NOTHING_ACCEPTED`); uncommitted changes are not pushed and the dialog says so.
- **No token, no login.** gh missing: `GH_MISSING`, with where to get it. Not signed in: `GH_NOT_AUTHENTICATED` and the command to run (`gh auth login --hostname <host>`); Office never runs it. gh runs with `GH_PROMPT_DISABLED=1`, `GIT_TERMINAL_PROMPT=0`, a time limit (`office.gh.timeout-seconds`, 60 s), the body in a temporary file.
- **A failed request is not proof that nothing happened.** Before pushing, Office looks for a pull request already open for the branch (`gh pr list --head`): found, it is recorded and nothing is pushed or opened (`FOUND`). After a failed or timed-out push, `git ls-remote` tells whether the branch arrived. After a failed or timed-out `gh pr create`, Office looks again: found → `FOUND`; none → `FAILED` with `retry` (safe: the next attempt looks first); the lookup itself failed → `UNKNOWN`, no retry offered, "check the repository first".
- **Journaled.** `PullRequestOpened(mission, workspace, url, number, branch, base, remote, found)` and `PullRequestCheckState(mission, number, checks)`, Origin REAL, actor the director. Checks are read only on the director's click (`POST …/{number}/checks`, `gh pr checks --json name,bucket,state`); no background polling.
- **The body says who wrote what.** `PullRequestText` composes it from the journal and git, with no model: objective, tickets done (producer, last verdict), each review's verdict and the first line of its findings (quoted as the agent's words), the checks Office measured with their dates, decision records changed on the branch and the committee's ruling, the branch's commits, then "Written by AI agents", "The director's own" (files edited by hand, commits accepted) and "Composed by Office".
- **Seam.** `GhCli` (`GhCli.Process` runs `office.gh.command`). In tests the command is `office-no-gh-in-tests` (missing) unless a test swaps in `src/test/resources/fake-gh/FakeGh.java`; the remote is a local bare repository reached through git's `url.<bare>.insteadOf` rewrite of the GitHub URL. No test reaches GitHub.

## Consequences
- The only outward action of Office; SECURITY.md says why it is safe and what it cannot prevent (gh's own scope, the director's hooks running on push).
- GitHub only (GitHub Enterprise hosts work through `--hostname` and `--repo host/owner/name`); GitLab would need another seam.
- The ADR number was taken while other increments numbered theirs in parallel: renumber on merge if 0034 is already used.

## Signal to reopen
A pull request opened twice, a push that was not the branch confirmed, or a gh version whose `--json` fields change; or the director asking for GitLab, or for the director's own branch of the project (the API accepts it, the UI offers only workspaces).
