# ADR-0077: Commits signed by the agent's identity

Date: 2026-11-16 · Status: Accepted (1.2.0)

## Context

When the director accepts a workshop patch, Floor42 commits on the agent's branch (ADR-0013). The commit carries the
director's repository identity, so nothing in git says which agent wrote the change. The proof dossier (ADR-0071) lists
the accepted commits but cannot show who stands behind them.

## Decision

- **One SSH key per agent**, Ed25519, made by `ssh-keygen` at the agent's first accepted patch and kept under
  `<data>/signing/<agent>/current/` (the folders owner-only through `OwnerOnly`: mode 700, or one ACL entry on NTFS; the key file as `ssh-keygen` writes
  it, mode 600). Never in a worktree, never in the repository's config. A key needs both files: a private key without
  its public part is deleted and made again.
- **Git SSH signing, passed per command.** The accept commit runs with `-c gpg.format=ssh -c user.signingkey=<file>
  -c gpg.ssh.program=<ssh-keygen> -c commit.gpgsign=true`. Nothing is written to `.git/config`.
- **Floor42's allowed-signers file**, `<data>/signing/allowed_signers`, rebuilt whenever a key is made or retired, and at every start, written to a temporary
  file then moved over the name (git never reads half a list): one line per public key, principal `<agent>@floor42.invalid`, `namespaces="git"`. Verification (`git log
  --show-signature`, and the dossier) passes it with `-c gpg.ssh.allowedSignersFile=`, and reads with
  `--no-replace-objects` so a `refs/replace` entry cannot lend a signed commit's signature to an unsigned one.
- **The dossier shows the result**, read from git now (`%G?`, `%GS`), on the `PATCH_ACCEPTED` line:
  `VERIFIED` (signed by the agent), `NOT_SIGNED`, `BAD_SIGNATURE`, `UNKNOWN_KEY`, `UNCHECKABLE` (git or `ssh-keygen`
  could not run, or the worktree is gone). The server sends the code, the UI words it (EN and FR). No journal event is
  added: the line is derived from git, source `GIT`.
- **Revoking or dismissing an agent retires its key.** The private key is deleted; the public key stays in the
  allowed-signers file under the principal `<agent>-retired@floor42.invalid`, so old commits still verify and the
  dossier says "key retired". A restored agent gets a new key at its next accepted patch.
- **A signing failure never loses the director's work.** If `ssh-keygen` is missing or fails, or if git refuses the
  signed commit (git before 2.34, a key the tool refuses, a key retired between the two steps), the accept retries the
  commit once without the signing arguments; the commit is unsigned, the dossier shows `NOT_SIGNED`, the reason is
  logged.

## Rejected

- **A key in the repository config** (`git config user.signingkey`): the agent writes the worktree and its git
  directory; a key path there is a path an agent could read or swap.
- **JDK-only signing**: `ssh-keygen -Y sign` is what git calls anyway, so the tool is needed regardless; writing the
  OpenSSH private-key format by hand adds code for no gain.
- **A single Floor42 key**: it would prove Floor42 committed, which the journal already says. The point is the agent.
- **A new journal event "CommitSigned"**: the signature is in the commit; an event would be a second, weaker record.

## Limits

- The signature proves that Floor42 held the agent's key when the commit was made, not that the agent wrote the code:
  the director accepted it. It is not a statement by the agent.
- The key sits on the same machine as the daemon: whoever owns the data folder can sign as any agent.
- The key is out of an agent's reach only when the agent runs in the sandbox. An agent that runs unconfined has the
  OS user's rights: it can read `<data>/signing/` and sign as any agent. The sandbox (ADR-0044 and the sandbox mode)
  is what keeps the folders away from it; without it this decision proves little.
- Sigstore keyless signing through Keycloak (short-lived certificates from the agent's token) is the next step. It is
  not built.
- A retired principal is found by its name suffix; an agent whose id ends in `-retired` would be misread. Agent ids are
  made by Floor42, so this is accepted.
