# How v1.0.0 was tested

What was run before the first public release, on 11 November 2026, what it found and what it did not cover. Every
figure below was measured on that day; nothing here is an estimate unless it says so.

## The gate, on two systems

The full gate runs on the developer machine before every release (`docs/releases.md`), once on Windows 11 and once on
Linux (WSL 2, Debian 13), from a fresh clone of the release commit:

- `./mvnw verify`: every Java test of the domain and the server (about 1,600 server tests, with the module boundaries
  checked by ArchUnit, and the size, catch and debt gates).
- The `slow` profile: the shell sandbox's escape tests, run against the real sandbox (WSL on Windows, native on Linux).
  They try to write outside the worktree, read the director's files, reach the host's network and loopback, and read
  the Maven settings.
- The package, then 69 browser journeys (Playwright, on a packaged daemon, with a fake Claude Code): 66 to 67 passed,
  the rest are measurements skipped by design.
- The UI's unit tests (Vitest, 913 tests) and its type check.

The CI on GitHub's Linux and Windows runners passed on the same code. Two browser journeys failed once each while the
machine also ran two virtual machines and two gates at the same time, and passed when run again alone; they are listed
as unstable under load in `docs/V2_IDEAS.md`.

## A bug hunt and a release review

An AI model (Claude Fable 5.2) read the code looking for defects: 40 findings in the sandbox, the API, identity and the
journal. A second model (Claude Opus 5.5) tried to refute each one; the confirmed major and medium ones were fixed. A
second pass reviewed the release itself (first install, documentation, release mechanics, publication hygiene): 3
blockers, 4 major and 2 medium findings, all fixed before this release. The minor ones are listed in
`docs/V2_IDEAS.md`.

## Two real missions

Two missions ran with real Claude Code agents (a developer and a reviewer) on throwaway repositories, through the API,
with a spending cap of 3 USD each:

| | Windows 11 | Linux (WSL 2, native sandbox) |
|---|---|---|
| Repository | a small Java command-line tool (Maven wrapper) | a small Python HTTP API |
| Objective | a missing-file error in one command | a DELETE route |
| Outcome | completed, delivery reviewed, sent back once, accepted | completed, delivery reviewed, sent back once, accepted |
| Cost | 0.25 USD, as estimated by Claude Code | 0.32 USD, as estimated by Claude Code |

They found defects that the automated tests did not: the agents' brief described the shell as unconfined while the
mandatory sandbox confined it; the sandbox showed no Maven cache, so a Maven project could not be built offline; the
sandbox's private loopback was down, so a test that starts a local server could not run; two messages were wrong. All
were fixed and are covered by tests now. The two missions were not run again after these fixes.

## A stranger's install on clean machines

A model playing a stranger followed only the README on two fresh VirtualBox machines:

- **Ubuntu 24.04.5**, stock settings (`kernel.apparmor_restrict_unprivileged_userns=1`): Java 25 from the distribution,
  `install.sh` from a locally built release archive, `floor42 doctor`, sign-in over SSH, the simulated demo and the
  guided first mission, end to end. The sandbox failed until the AppArmor profile that the README now documents was
  loaded; then it worked.
- **Fedora 45**, SELinux enforcing, Docker 29.9: the image built from the source (`docker build --target floor42`),
  started as documented, the demo and the guided mission end to end. No volume label was needed with Docker CE.

The documentation and product defects they found (15 and 7) were fixed or listed. The machines ran on VirtualBox over
Hyper-V, where the guests stalled for minutes at times: the durations measured there are not reliable.

## Not tested

- macOS.
- A real mission in IAM mode (Keycloak): IAM is covered by the automated tests and the browser journeys only.
- The public download itself: the archive, `install.sh` from GitHub and the `ghcr.io` image were exercised from local
  builds, not from the published release.
- Podman, other Linux distributions, a real (not virtual) Linux machine.
