# ADR-0056: Docker for a mission, by the director's switch (rejected)

Date: 2026-11-03 · Status: Rejected on 2026-11-06 (the socket gives an agent root on the machine; a rootless Podman socket would give it the whole account). Replaced by ADR-0065, the container broker.

## Context
Agents cannot use Docker in the shell sandbox (ADR-0028), and until now Floor42 did not say so. Checked by hand on the
director's machine:

- `run.sh` mounts an empty tmpfs over `/run`, so `/run/docker.sock` (and `/var/run`, a link to it on Debian) is not
  there.
- `unshare -Urm` maps the director's user to root in a new user namespace and drops the supplementary groups: even with
  the socket visible, the `docker` group that grants it would be gone.
- The tools probe only ran `command -v docker`: a present client read as Docker available.

What ships with this ADR, without widening anything:

- The probe asks the daemon itself from inside the sandbox (`docker version`, 5 s at most, in the same single run).
  `DockerAccess` reads `CLIENT_MISSING`, `DAEMON_UNREACHABLE_IN_SANDBOX`, `DAEMON_REACHABLE` or `UNKNOWN`.
- That state is kept in memory only, refreshed by every probe, and shown with the time it was measured. It is not
  journaled: the probe's line about the daemon is never among the tools of `SandboxToolsProbed`, which 1.13.0 reads as
  it always did. After a restart the state is `UNKNOWN` until the next probe.
- The sandbox panel, the machine checklist and `floor42 doctor` show a Docker row with the director's step and no
  install command.
- A plan whose project needs Docker (a Dockerfile or a compose file at its root, or a ticket that NEEDS it) measures the
  daemon then, and asks one question that says only what the measure says: `dockerUnreachable`, `dockerMissing`, or
  `dockerUnmeasured` when the probe could not run. The options are "I do the step myself" and "Go on without Docker"
  (`WITHOUT_DOCKER`, its own code: the dependency question's "Go on without it" keeps its meaning). The director's step
  names only the tickets that NEED docker; when none does, it is the mission's (said in the briefs), and no review
  escalates for it.
- A step blocked on Docker says the real reason, with no install and no exception offered.
- The daemon's own state on the WSL side, outside the sandbox, is not measured: it would take a second `wsl.exe` per
  probe or per checklist read.

Rollback to 1.13.0: nothing of this is in the journal but answered questions with new phrase codes
(`dockerUnreachable`, `dockerMissing`, `dockerUnmeasured`, `environmentBlockedDocker*`) and the option code
`WITHOUT_DOCKER`. 1.13.0 shows such a question by its English subject and context (its catalogue lacks the codes) and
reads `WITHOUT_DOCKER` as an option it does not know (no effect); no state is lost.

The question left open: should a mission be able to use Docker from inside the sandbox?

## Proposal
A per-mission switch, "Allow Docker for this mission", off by default:

- Set by the director only (full scope, never a script token, never an agent), at creation or later, on one mission;
  never a company setting, never inherited by another mission.
- When on, `run.sh` binds the daemon's socket into the sandbox (`/run/docker.sock` bound back after the tmpfs) and
  keeps the docker group for that command. Everything else stays as ADR-0028 has it.
- The brief says Docker is open for this mission and what it costs; the permission view shows it as a grant.
- Journaled: one event when the director turns it on or off (mission, on or off, the director as actor, `Origin.REAL`),
  and the probe's measure of the daemon taken just after. A new event type, so it needs the director's approval and is
  a point of no return for the release that ships it.

## What it costs
The Docker socket is root on the WSL virtual machine. Whoever can write to it can start a container with `--privileged`
or with `/` of the VM mounted, and from there:

- read and change every file of the distribution, the daemon's other containers and images included;
- reach the Windows drives through the VM's `/mnt` mounts, the very ones the sandbox hides, so the isolation of
  ADR-0028 is gone for that mission;
- start programs on Windows through WSL's interop if it is on in the distribution;
- reach the network even when `SANDBOX_NETWORK` is off: containers get the daemon's network, not the sandbox's.

So with the switch on, an agent of that mission has, in practice, the director's rights on the VM and on the Windows
files the VM mounts. The switch cannot narrow that: a socket is all or nothing. It is an escape from the sandbox the
director grants with open eyes, not a permission Floor42 can enforce.

## Alternatives
| Option | What it costs | Verdict |
|---|---|---|
| A. The director runs the Docker steps (shipped now) | The director's time; the mission waits for those steps | Default: no risk added |
| B. Socket bound into the sandbox per mission (above) | Root on the VM for that mission's agents; the sandbox's isolation lost | Proposed, opt-in only |
| C. Rootless Docker (or Podman) inside the distribution, socket in a folder bound into the sandbox | A second engine to install and keep; images built twice; user namespaces nested inside ours, to be measured | Worth a spike before B |
| D. A remote builder (a build service or a separate VM reached over the network) | A machine or a service to run and pay; the sandbox's network must be open for it | For teams, not for one machine |
| E. Floor42 runs a fixed list of Docker commands for the agent (build, run tests) | A command parser and a policy to keep exact; any `docker run` argument can mount the host | Rejected: the same escape, hidden behind a list |

## Reopen if
The director wants agents to build or run containers themselves, rootless Docker is measured to work in nested user
namespaces on WSL, or WSL changes how it mounts `/run`.
