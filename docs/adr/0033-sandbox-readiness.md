# ADR-0033: Sandbox readiness: what a project needs, what the sandbox has, how to install the rest

Date: 2026-10-31 · Status: Accepted

## Context
ADR-0028 runs shell commands and checks in a WSL2 sandbox that only finds the tools installed in the distribution. A fresh Debian has neither a JDK nor Maven nor Node, and the director only learned it when an agent's command failed. The director asked for a panel: when a project is chosen, which tools it needs, which ones the sandbox really has, and how to install the missing ones.

## Options
| Option | What it costs | Verdict |
|---|---|---|
| A. Office installs the missing tools (`wsl -u root apt-get …`) | Root in the distribution from the daemon: an agent-reachable path to system changes | Rejected by the director: Office never installs anything |
| B. Office measures, shows the commands to copy and a Re-measure button | One `wsl.exe` per click; the director types the install | **Taken** |
| C. Guess from the distribution's defaults, without measuring | Nothing to run | Rejected: a guess shown as a state |

## Decision
- `ProjectToolchain` reads the project's root files only: `pom.xml` (java at `maven.compiler.release` or `<release>`, and mvn), `mvnw` (java; the wrapper brings Maven), `build.gradle`/`gradlew`, `package.json` (`engines.node`, npm) and `.nvmrc`, `pyproject.toml` (`requires-python`) and `requirements.txt` (pip3), `go.mod`, `Cargo.toml` with `rust-toolchain[.toml]`, `Makefile`, `Dockerfile`, and the first word of the checks command. Git is always needed. Nothing runs.
- `SandboxProbe` runs ONE `wsl.exe` through `Sandbox.argv`, so the same namespaces and PATH as real work, in a scratch folder of Office's (no project path reaches it): `/etc/os-release`, then per tool `command -v` and the first line of `--version` (`java -version 2>&1`, `go version`), printed `tool|path|version`, then an end marker; 30 s at most. Not Windows, no `wsl.exe`, no distribution, or the limit: nothing is measured and every tool is UNKNOWN. A cut output keeps only complete lines; tools after them stay UNKNOWN. A tool is MISSING only when a complete line said so.
- The measure is journaled as `SandboxToolsProbed` (Origin REAL, actor SYSTEM, no mission, no project path): the journal is the cache. Reading the readiness never measures. Turning `SHELL_SANDBOX` to `wsl` forgets the last measure (the director may just have installed or repaired something).
- `InstallRecipes`: apt packages for Debian, Ubuntu and distributions built on them (ID or ID_LIKE). Java at a release: `openjdk-<N>-jdk-headless`. Node newer than the distribution's (known: Debian 12 → 18, 13 → 20, Ubuntu 22.04 → 12, 24.04 → 18; unknown counts as older): the official tarball of `latest-v<N>.x` from nodejs.org, checked against its `SHASUMS256.txt`, unpacked into `/usr/local` (on the sandbox's PATH); never nvm, whose home folder the sandbox cannot see. No recipe (docker, gradle, other distributions, an older Python or Go than wanted): "install it yourself"; nothing is invented.
- `GET /api/sandbox/readiness?project=&check=` and `POST /api/sandbox/readiness/probe` (tag Sandbox), under `/api`, so the director's token only. No route runs a recipe.
- The panel (`SandboxReadiness.vue`): need, measured state and version per tool, "measured at", Re-measure, the commands in a block with Copy; under the project picker before Propose, before the plan's approval, and from a Sandbox button in the top bar. It says that HOME is empty in the sandbox (`~/.m2`, `~/.npm`, `~/.nvm` invisible: a Node from nvm does not exist there, caches are lost per command), what the network off or on means for mvn, npm and pip, and for Docker: client present, daemon unreachable (`/run` is masked).
- Agents' briefs (sandbox on) list the tools last measured present, and those measured missing.

## Verified
- Tests on recorded outputs through the `ProbeRunner` seam (`SandboxProbeTest`, `SandboxApiTest`): truncated output, timeout, no distribution (UTF-16 message), off Windows (no process starts), one run per click with a scratch folder and a script without apt or sudo, the journaled event without the project path. No test starts `wsl.exe`.
- Not yet measured on the director's machine through the real `wsl.exe` at the time of writing.

## Consequences
- The director sees a missing tool before a mission starts, not from a failed command.
- Version comparison is numeric on the wanted precision (">=20.11" against "v20.19.2"); a channel name ("stable") or an upper bound only is not compared.
- The Node majors of each distribution are written in the code: a new distribution release needs a line, else its Node counts as older and the tarball is offered.

## Reopen if
The director wants Office to install tools, the sandbox gets a persistent HOME, or a distribution other than Debian or Ubuntu is used.
