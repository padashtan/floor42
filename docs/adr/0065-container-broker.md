# ADR-0065: Containers for missions through a per-mission broker, reached with an exchanged token

Date: 2026-11-06 · Status: Proposed (not implemented; designed after the public v1.0.0)

## Context

Some missions need containers: a project with a `compose.yaml`, tests that use Testcontainers. Agents run in the shell
sandbox (ADR-0028, mandatory for real missions since ADR-0063), which sees no container engine. ADR-0056 proposed to
open the Docker socket to the sandbox on the director's switch. It is rejected: whoever holds that socket is root on
the machine. A rootless Podman socket is no answer either: its containers run with the director's own rights and can
mount the home folder, the vault key (`~/.floor42/iam/vault.key`) and SSH keys included.

Options weighed (2026-11-06):

| Option | What a cheating agent reaches | Platforms | Cost |
|--------|-------------------------------|-----------|------|
| Host Docker socket (ADR-0056) | root on the machine | all | low |
| The director's rootless Podman socket | the whole user account | all | low |
| One throwaway VM per mission | the VM | heavy on Linux; WSL distributions share one kernel | high, slow |
| Podman nested in the sandbox | the sandbox | fragile (nested user namespaces, `newuidmap` blocked) | medium |
| **A broker in Floor42** | the containers its policy allows, no host mount | all, one Java code path | medium |

## Decision (proposed)

1. Floor42 serves each agent session an endpoint compatible with the Docker Engine API (`DOCKER_HOST`), so the
   `docker` CLI, `docker compose` and Testcontainers work unchanged.
2. Every request goes through a deny-by-default policy written in Java: allowed endpoints (containers create, start,
   stop, logs, wait, exec, remove; images pulled from listed registries; the mission's networks and volumes); refused:
   bind mounts outside the worktree, `privileged`, added capabilities, host network or PID namespace, devices, any
   container socket mount, other registries. Each refusal is journaled with its reason.
3. Access is a token, as for the resource broker (ADR-0064): the agent's session exchanges its token for one with the
   audience `floor42-containers`, bound to the mission, short-lived. The broker checks signature, audience, expiry, a
   live mission and a delegation not revoked before the policy. A revoked delegation or a closed mission stops and
   removes every container labelled with the mission. CPU, memory and container count are capped per mission.
4. Behind the broker the engine is rootless Podman where possible (Linux), or the Docker or Podman of the WSL
   distribution on Windows. Floor42 sees only the Docker API either way.
5. New event types (container started, refused, stopped) need the director's approval before the code.

## Later: rights managed in Keycloak, decided by OpenFGA

Resources, grants and permissions leave Floor42's files (`resources.json`, `grants.json`): the director manages
agents, groups, roles and grants in Keycloak; a Keycloak event listener (SPI) publishes each change to OpenFGA as
relationship tuples; the brokers (resources and containers) ask OpenFGA `Check` on each request. Keycloak manages,
OpenFGA decides, Floor42 enforces. The existing community event publisher is to be assessed before writing our own.

## Consequences

- Residual risk, named: escaping a container with no host mount and no privilege needs a kernel flaw; with rootless
  Podman that escape lands on an unprivileged account, not root.
- Until this is built, agents have no containers, and the README says so.
- The director installs Podman (or keeps the WSL Docker) when the work starts; Floor42 installs nothing.
