# Floor42 in Docker (WSL 2 or Linux)

Floor42 runs in one container: the daemon, its UI, git and, in the image you build yourself, Claude Code. The container is also a confinement for agents:
their shell commands run inside it and reach only the folder you mount, never the rest of your machine. Inside the
container they run as the daemon's own user: see "What the container changes" for what they can still reach there.

This guide uses Docker Engine inside a WSL 2 distribution (Debian or Ubuntu); Docker Desktop is not needed. On Linux,
skip step 1's WSL specifics. Fedora: install Docker Engine from Docker's repository ([docs.docker.com/engine/install/fedora](https://docs.docker.com/engine/install/fedora/)) instead of step 1. `make` and `git` are needed only for `make docker-up` and to clone the repository (`sudo dnf install make git`); `docker build` and `docker run` need neither.

## 1. Docker Engine in WSL

WSL must run systemd. In the distribution, `/etc/wsl.conf` should hold:

```ini
[boot]
systemd=true
```

(after changing it, run `wsl --shutdown` from Windows and open the distribution again).

Then install Docker Engine from Docker's repository (Debian shown; for Ubuntu replace `debian` by `ubuntu`):

```bash
sudo apt-get update && sudo apt-get install -y ca-certificates curl make
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update && sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER"      # then close and reopen the WSL terminal
docker run --rm hello-world          # checks the installation
```

## 2. Start Floor42

Clone the repository inside WSL (the build is much faster on the WSL file system than under `/mnt/c`), then name the
folder holding your git repositories. It is mounted at the same path in the container, so paths read the same in the
office and in your terminal:

```bash
export FLOOR42_PROJECTS=$HOME/code    # your repositories live under this folder
make docker-up                       # or: bin/floor42-docker up
```

The first build takes about 12 minutes on a 2-core machine (measured: 700 s for the `floor42` target, without Claude Code). The office then opens in your Windows browser on `http://127.0.0.1:7420`
(WSL forwards the port to Windows). The daemon is published on the loopback interface only.

Without signing in to Claude Code, everything works except real agents, which cannot work until you sign in
(step 3); the simulated demo mission (press **D**, then *Start a demo mission*) shows a full mission with every
event marked simulated.

**Keep a WSL terminal open while you use Floor42.** WSL stops the distribution a few seconds after its last terminal
closes, and Docker with it; the container comes back (`restart: unless-stopped`) the next time the distribution starts.

## 3. Sign Claude Code in (for real agents)

Once:

```bash
make docker-login                    # runs claude inside the container: follow its sign-in, then /exit
```

The sign-in stays in the `office-home` volume with Floor42's data, across restarts and upgrades. Floor42 uses your own
Claude account; nothing is sent anywhere else.

The volume and the container's home (`/home/office`) kept their names when Office became Floor42 (before v1.0.0): an image
built before keeps its sign-in, and the worktrees in the volume keep the absolute paths git knows them by. The data
folder inside becomes `/home/office/.floor42`; on its first start the new image copies the journal from
`/home/office/.office`, which stays as it was. The service is now called `floor42` (`docker compose logs floor42`) and
`FLOOR42_PROJECTS` replaces `OFFICE_PROJECTS` (`bin/floor42-docker` still reads the old name).

## Everyday commands

| Command | What it does |
|---|---|
| `make docker-up` | build if needed, start, open the office |
| `make docker-open` | open the office in the browser |
| `make docker-logs` | follow the daemon's log |
| `make docker-down` | stop; data and sign-in are kept |
| `FLOOR42_IMAGE=floor42:local docker compose build --pull` | rebuild with fresh base images |

`FLOOR42_PORT` publishes on another port than 7420 (when Floor42 also runs outside Docker); `FLOOR42_NO_BROWSER=1` starts
without opening a browser. With Keycloak sign-in (IAM mode), keep the published port equal to the daemon's (7420, or
another port registered in the realm): the daemon builds the `redirect_uri` from the port it listens on, and the realm
lists only `127.0.0.1` and `localhost` on 7420 and 7421 (`[::1]` is not listed). A remapped `FLOOR42_PORT` fails at the
sign-in unless `http://127.0.0.1:<port>/auth/callback` and the `localhost` twin are added to the `floor42-desk` client's valid
redirect URIs. The director's sign-in lives in the daemon's memory: after each start, `make docker-open` signs the browser in again with a single-use link the daemon prints on the container's output, read from the host. Agents inside the container cannot read that output: the daemon makes itself non-dumpable at start, so its `/proc/1/fd` is refused to them (ADR-0044).

## What is in the image

About 340 MB to download and 770 MB unpacked, measured on 2026-10-30 (`docker images` shows about 1.1 GB: Docker also
keeps the compressed layers):

| Part | Unpacked | Why |
|---|---|---|
| Claude Code | 285 MB | a native binary; runs the agents |
| Java 25 runtime | 192 MB | runs the daemon |
| Ubuntu base, git, curl | about 230 MB | git for worktrees, curl for the health check |
| Floor42 | 58 MB | the daemon and its UI |

Node is used to build the UI and to fetch Claude Code, not at run time. The image holds no build tool for your projects
(no JDK, Maven or Node): agents that must build or test a project need them, so extend the image, for example
`FROM floor42:local` then install your toolchain as root and switch back to `USER floor42`. Keep the working directory
`/opt/floor42` and keep what you add root-owned: a working directory or a program uid 1000 can write is one an agent can
change before the daemon's next start.

**The image with Claude Code is built on your machine, never published.** Claude Code is Anthropic's software under
its own terms: each user's build downloads it from npm. Floor42's code is Apache-2.0 (ADR-0043), but an image holding
Claude Code is not redistributable.

**The published image** `ghcr.io/padashtan/floor42` is the Dockerfile's `floor42` target: the daemon, its UI, git,
and no Claude Code. It runs the office and the simulated demo; real missions are refused there until Claude Code is
present, so for real agents build your own (`make docker-up`, which names it `floor42:local`):

```bash
docker run --rm -p 127.0.0.1:7420:7420 ghcr.io/padashtan/floor42:latest
```

Open `http://127.0.0.1:7420` and ask to sign in: the single-use link is printed on that terminal. `docker compose up`
without `make` uses the published image too (`FLOOR42_IMAGE` names another one), and builds it locally only when it
cannot be pulled.

To build the published image yourself from a source checkout, while `ghcr.io/padashtan/floor42` cannot be pulled (it
answers `denied` until the repository is public): `docker build --target floor42 -t floor42:local .`, then
`docker run -p 127.0.0.1:7420:7420 floor42:local` (measured, 700 s on 2 vCPU).

**SELinux (Fedora, RHEL).** Docker CE does not enable SELinux for containers by default, so the mounted projects folder
needs no label: measured on Fedora 45 with Docker 29.9.3 (`docker info` lists no `selinux` security option; reading and
creating files under the mount worked, no denial). With a daemon started with `--selinux-enabled`, or with Podman, the
mount most likely needs `:z` (`compose.yaml` mounts without it); not measured.

## What the container changes

- The container is the confinement for what an agent's shell can reach: files outside `FLOOR42_PROJECTS` and the
  container are out of its reach. The shell sandbox (WSL on Windows, native on Linux) does not run in Docker, so under
  [ADR-0063](adr/0063-sandbox-mandatory-for-real-missions.md) a real mission does not start in the container
  (`SANDBOX_REQUIRED`, said on the mission) until *unsafe mode* is switched on in the settings, with a reason, a
  journaled choice shown in red. Simulated missions and the demo need neither. The `SHELL_SANDBOX` setting does not
  change that: the sandbox is required for a real mission whatever it says, unless unsafe mode is on.
- Inside `FLOOR42_PROJECTS` they act with your user's rights (the container runs as user 1000, the usual WSL user), so
  the permission table and the director's approvals still matter.
- The network is not confined: agents can reach the Internet, as Claude Code needs to.
- To remove everything: `docker compose down -v` deletes the volume, hence Floor42's journal and the Claude sign-in.

### What agents can reach inside the container (checked on 2026-10-31)

Agents run as uid 1000, the daemon's own user (it is PID 1), with no shell sandbox (unsafe mode, ADR-0063). Checked by building this image
and running permission checks as uid 1000 beside the daemon (Docker Engine 29.9.2, WSL Debian), and by the server test
`ClaudeCodeRuntimeTest.anAgentsShellRunningAsTheDaemonsUserCanWriteTheJournalAndReadCredentials` (`[fake:forge]`):

| What | Reachable by an agent's shell | Why |
|---|---|---|
| The sign-in link on the daemon's output (`/proc/1/fd/1`) and its environment (`/proc/1/environ`) | **No** | The daemon calls `prctl(PR_SET_DUMPABLE, 0)` at start: its `/proc` entries belong to root. `FLOOR42_PROCESS_NON_DUMPABLE=false` turns it off (for `jcmd`) and gives them back |
| Floor42's journal (`/home/office/.floor42/floor42.db`) | **Yes, writable** | Same uid. A row written there takes effect at the next start. Floor42 rebuilds no credential from it (script tokens live in memory), but forged permissions or approvals would count |
| Claude Code's sign-in (`/home/office/.claude`) | **Yes, readable** | Agents are Claude Code: they need it |
| The director's token, browser sessions, script tokens | No | In the daemon's memory only; the journal holds only a token's SHA-256 |
| The daemon's files (`/opt/floor42`, the jar and its libraries) | **No** | Owned by root, directories 0755 and files 0644; only the `office-home` volume is writable by uid 1000 |
| The daemon's configuration at its next start | **No** | The daemon runs from `/opt/floor42`, root-owned; `/home/office/.env` and `/home/office/config/` are not read. A `.env` or `config/` found in the working directory refuses the start (`ConfigGuard`) |
| A restore marker read at the next start | **No** | A start never restores; a planted `restore-pending.properties` is refused and shown in a red banner (docs/backups.md) |
| Rolling the journal back by moving an older copy over `floor42.db`, then a restart | **Yes, but detected** | The file is theirs to replace (D16). The stop keeps the journal it still held as `displaced-*`; the next start finds fewer events than the last run saw, or a different last event, and shows a red banner until you acknowledge it. Detection, not prevention: the record it compares with (`journal-continuity.properties`) is a file they could rewrite too |
| Loading code into the running daemon through the JVM's attach mechanism | **No** | `-XX:+DisableAttachMechanism` in the image's entry point: `/tmp/.attach_pid1` plus `SIGQUIT` no longer opens `/tmp/.java_pid1`. `/api/status` reports `process.attachDisabled` as the JVM says it |

### What an agent could change at the daemon's next start, without these defences (checked on 2026-11-01)

Agents can stop the daemon: `kill -TERM 1` as uid 1000, and `restart: unless-stopped` starts it again. Checked on an
image of a private build that predates v1.0.0 and had none of the defences in the table above, run with a throwaway volume (Docker Engine 29.9.2, WSL Debian), each
step run as uid 1000 beside the daemon:

- **Journal rollback.** `restore-pending.properties` written in `/home/office/.floor42` naming an older startup copy,
  then `kill -TERM 1`: the next start restored it ("Journal restored from the backup startup-…") and the journal's
  SHA-256 became the copy's.
- **Configuration.** `/home/office` was the working directory and writable. Quarkus reads `.env` and
  `config/application.properties` from there. `FLOOR42_PROCESS_NON_DUMPABLE=false` in either, then `kill -TERM 1`: the
  restarted daemon was dumpable again (`ls /proc/1/fd` worked as uid 1000). A JDBC URL turning SQLite's extension
  loading on would have been read the same way.
- **The application.** `/opt/floor42` was copied `--chown=floor42`: uid 1000 could overwrite `quarkus-run.jar` and add a
  jar to `lib/main`.

The same checks on the first image built with these defences: the planted marker is refused, the journal unchanged and the banner shown; a planted
`.env` or `config/` in `/home/office` is not read (the daemon stays non-dumpable, `/api/status` says
`"process":{"nonDumpableRequested":true,"nonDumpable":true}`); a container forced to run from `/home/office`
(`-w /home/office`) with a planted `.env` exits with "Floor42 refuses to start"; nothing under `/opt/floor42` is writable
by uid 1000; a restore prepared from Settings is applied by `docker restart` ("Journal restored" from the shutdown
thread) and reported at the next start.

A counter-check of that first hardened image (2026-11-01) found four more, each shown in a container, then closed or made
visible:

- **Attach.** As uid 1000, `touch /tmp/.attach_pid1; kill -QUIT 1` made the daemon create `/tmp/.java_pid1`, the JVM's
  attach socket: any same-uid process could load an agent into the daemon (non-dumpable does not cover it). Now the
  entry point runs `java -XX:+DisableAttachMechanism`: no socket is created, and `/api/status` says
  `"attachDisabled":true`.
- **File swap.** `cp backups/manual-….db x.new && mv -f x.new floor42.db && kill -TERM 1`: the next start replayed the
  old journal (67 events, then 35) with no word. Now the stop keeps the journal its connections still held as
  `displaced-<time>.db` (67 events), and the next start shows "The journal went back in time: the last run saw 67
  events, this start finds 35" until acknowledged. It is detected, not prevented.
- **Forged outcome.** A `restore-outcome.properties` saying "not applied: Disk failure: call support at evil.example"
  was shown as verified. Now every outcome is shown as reported by a file, never as checked, and its text is bounded.
- **Backup swapped after preparation.** A copy prepared in Settings, then overwritten with an older one before the stop,
  was restored as is. Now its SHA-256 is taken at preparation and the stop refuses a copy that changed ("changed since
  it was prepared").

A second counter-check (2026-11-01, ADR-0044 amendment 3) found that the file swap went unseen again when the daemon
stayed idle about five minutes after it: the pool closed its idle connections, the next one opened the forged file, and
the stop measured that one. Now the daemon measures the journal through a read-only connection it holds on the file it
opened, from start to stop. A missing or unreadable `journal-continuity.properties` beside a journal holding events
shows the banner too, and the restore no longer goes through `/tmp`: the stop reads the prepared copy once into memory
and restores those bytes. These are proven by tests (`JournalContinuityTest`, with a real `rename` over the file on
Linux, and `BackupStoreTest`), not yet re-checked in a rebuilt container.

### Which commit is this image?

The build context has no `.git`, so the builder passes the commit: `make docker-build`, `bin/floor42-docker up` and the
release workflow set the build arguments `FLOOR42_COMMIT`, `FLOOR42_COMMIT_TIME` and `FLOOR42_COMMIT_DIRTY`, and
`bin/build-commit-properties` writes them into the jar. A plain `docker build` without them gives `build.commit: null`,
never a guess. To check a running image:

```
curl -fs -H 'Host: 127.0.0.1' http://127.0.0.1:7420/api/status | jq .build
# {"commit":"1a2b3c4","commitFull":"1a2b3c4…","commitTime":"2026-11-01T…","dirty":false}
git rev-parse HEAD   # the same full id, where the image was built
```

Checked on 2026-11-01: an image built with these arguments (`--target floor42`, Docker Engine in WSL Debian) answered
`build.commit` with the commit it was built from and `dirty: false`; a fresh volume showed no continuity banner.

`make docker-login` runs `claude` as uid 1000 with `HOME=/home/office` and in `/home/office`
(`docker compose exec -u 1000 -e HOME=/home/office -w /home/office`), so the sign-in still lands in the volume.

What is still open: agents can write the journal itself and replace its file (D16; a plain rollback is detected, a
careful forger who also rewrites the continuity record is not), and they can stop the daemon. A journal another
program holds blocks appends (docs/backups.md). `QUARKUS_CONFIG_LOCATIONS` is yours to set: a configuration file read
from outside Floor42's own files is logged and shown in a banner, so you can check no agent can write it.

Running agents under a second uid would close the journal. It is not done: switching uid needs root inside the
container, and agents and the daemon must both write the same repository metadata of your mounted repositories, which
belongs to your own uid with mode 0755. So keep the permission table on "ask" for shell commands you do not trust, and
review what you accept.

To run the daemon on another machine, see [remote.md](remote.md): an SSH tunnel, never an exposed port or a proxy.

The base images (the eclipse-temurin JRE, the Ubuntu packages) and Claude Code carry their own licences, not Floor42's.
