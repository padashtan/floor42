# Floor42 on another machine

Floor42 is a local daemon (ADR-0007): it listens on `127.0.0.1` and trusts only requests that name a loopback Host
and, from a browser, its own exact Origin (ADR-0044). To use a daemon that runs on another machine (a server, a
workstation you reach over the network), bring its port to your own loopback through SSH. Nothing else is supported.

## The supported way: an SSH tunnel

On the remote machine, start Floor42 (`floor42`, or `bin/floor42`). With no browser to open, the launcher writes the
single-use sign-in link to `~/.floor42/sign-in-link` (valid 60 seconds; the file is gone once it expired, and running
`floor42` again writes a new one). Measured on Ubuntu 24.04.

With `FLOOR42_SIGN_IN_DELIVERY=stdout` no file is written and the launcher prints no link: the daemon's output goes to
`~/.floor42/daemon.log`, so read the link there with `grep 'auth?code' ~/.floor42/daemon.log | tail -1`. With Docker,
see below.

On your own machine, forward your local port 7420 to the remote daemon's loopback:

```bash
ssh -N -L 7420:127.0.0.1:7420 you@remote-host
```

Then open the link from `~/.floor42/sign-in-link` (read it on the remote host with `cat`), or ask for a new one
(`curl -X POST http://127.0.0.1:7420/auth/open` through the tunnel answers `202`, or the login panel's button) and open
the link the remote daemon wrote, in your browser, as `http://127.0.0.1:7420/auth?code=...`. The
link is single use and valid 60 seconds. Your browser talks to `127.0.0.1:7420`: Host and Origin are loopback names, the
cookie stays `HttpOnly` and `SameSite=Strict`, and the traffic between the machines is SSH's.

If port 7420 is taken on your machine, pick another local port (`-L 7430:127.0.0.1:7420`) and open
`http://127.0.0.1:7430/...`: the daemon compares the Origin with the Host your browser sent, so any local port works for the console link and script tokens.

Signing in through Keycloak (IAM mode) is stricter. The daemon builds the `redirect_uri` from the port it really listens on
(7420), never from the Host your browser sent, and the realm only lists `http://127.0.0.1` and `http://localhost` on ports
7420 and 7421 (`iam/realm/floor42-realm.json`; `[::1]` is not listed). With a tunnel to another local port the browser
comes back to a port nothing listens on and the sign-in fails. Use `-L 7420:127.0.0.1:7420`, or, for another daemon port,
add `http://127.0.0.1:<port>/auth/callback` and `http://localhost:<port>/auth/callback` to the `floor42-desk` client's
valid redirect URIs (Keycloak admin console > Clients > floor42-desk) and open the desk on that same port.

For scripts on the remote side, issue a script token from the desk (System > Sessions and tokens): scoped, at most 30
days, shown once, gone when the daemon restarts.

### Confine the agents: Docker

Agents run commands as the daemon's OS user. On a shared or exposed machine, run Floor42 in Docker
([docker.md](docker.md)): it prints the sign-in link on the container's output (`FLOOR42_SIGN_IN_DELIVERY=stdout` is
the image's default), read on the remote host with `docker compose logs floor42`, and agents see only the folders you
mount. Read docker.md's "What the container changes" for what that confinement does and does not prove.

## Not supported

- **Exposing the port.** Publishing 7420 on another interface than loopback (`0.0.0.0:7420`, a cloud security group, a
  router rule): the daemon refuses any Host that is not a loopback name, so this does not work, and must not be worked
  around. Floor42 has one user and no authentication meant for a network.
- **A reverse proxy** (nginx, Caddy, Traefik, a tunnel service) in front of the daemon. A proxy rewrites or forwards
  `Host`, adds `X-Forwarded-For`, `X-Forwarded-Host` or `Forwarded`, and puts another origin in front of the cookie.
  The daemon refuses every request that carries one of those headers with `403 FORWARDED` and a message pointing here,
  rather than guess which hop to trust. Tested by `BrowserRequestsTest.aRequestAProxyForwardedIsRefusedWithAClearError`.
- **TLS on the daemon.** There is none: the tunnel provides it.
- **Several people on one daemon.** There is one director; every session is theirs.
