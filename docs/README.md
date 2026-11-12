# What to read

Floor42 was built by one director and the Claude Code agents he directed. The documents here are of two kinds: the
ones written for readers, and the working notes the agents keep for each other.

## For readers

| Document | What it is |
|---|---|
| [`adr/`](adr/README.md) | **Start here.** 64 architecture decision records, each with its context, the options and their cost, the decision, and the signal that would reopen it. They show how the project reasons: governance by code, honest origins, the security boundaries and what they do not cover. |
| [`architecture/`](architecture/README.md) | Ten diagrams of what Floor42 is made of, drawn from the code (draw.io files and their generator). |
| [`threat-model.md`](threat-model.md) | What is protected, against whom, and what is not. |
| [`../iam/README.md`](../iam/README.md) | Agent identity with Keycloak (IAM mode): the stack, the realm, the flows and the facts measured on Keycloak. |
| [`iam-bring-your-own-keycloak.md`](iam-bring-your-own-keycloak.md) | Pointing Floor42 at an existing Keycloak. |
| [`iam-revocation.md`](iam-revocation.md) | How fast each way of revoking an agent takes effect, and the tests that time it. |
| [`docker.md`](docker.md) | Running Floor42 in Docker (WSL 2 or Linux): the published image, the one you build with Claude Code, and what the container confines. |
| [`remote.md`](remote.md) | Using a daemon on another machine: the supported way is an SSH tunnel to your own loopback. |
| [`backups.md`](backups.md) | The journal's automatic backups and how to restore one. |
| [`releases.md`](releases.md) | What a release publishes (zip, image, SBOMs, signed provenance) and how to verify it with `gh attestation verify`. |
| [`quality.md`](quality.md), [`security-scanning.md`](security-scanning.md), [`oidc-conformance.md`](oidc-conformance.md) | The assurance chain: static analysis, coverage, mutation, fuzzing, scanners, and the OpenID conformance run. |
| [`migration-from-office.md`](migration-from-office.md) | What happens on the first start after an upgrade from Office (the project's name before its public release). |
| [`../SECURITY.md`](../SECURITY.md) | What Floor42 enforces, what it does not, and how to report a vulnerability. |
| [`../CHANGELOG.md`](../CHANGELOG.md) | What changed in each release, one line per change, with links to the ADRs. |

## Working notes

| Document | What it is |
|---|---|
| [`PROGRESS.md`](PROGRESS.md) | The resume point for coding agents: how to work, the decisions and gotchas still true today. |
| [`../CLAUDE.md`](../CLAUDE.md) | The rules every coding agent (and every contributor) follows in this repository. |
| [`DEBT.md`](DEBT.md) | The technical debt register. Above 5 open items the build fails (`DebtGateTest`). |
| [`V2_IDEAS.md`](V2_IDEAS.md) | Ideas after v1 and known loose ends. Not a roadmap. |

Records written before the rename say "Office", and some cite `spike/*` branches, throwaway experiments that are not in this
repository: they are kept as written.
