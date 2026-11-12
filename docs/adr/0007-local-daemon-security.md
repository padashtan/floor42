# ADR-0007: Loopback-only daemon with session token

Date: 2026-10-20 · Status: Accepted, amended by [ADR-0034](0034-director-credential-out-of-agents-reach.md) and [ADR-0044](0044-sessions-tokens-and-exact-origin.md); in IAM mode the director also signs in through Keycloak ([ADR-0064](0064-agent-identity-keycloak.md)), the console link staying the break-glass

## Context
A local HTTP server that can start agents is an attractive target for any web page (CSRF, DNS rebinding) and for any local process.

## Decision
- Bind to `127.0.0.1` only.
- A random token is generated at startup and handed to the browser by the CLI; every API and WebSocket call requires it.
- `Host` and `Origin` checks reject anything that is not the daemon's own origin. Strict CSP.
- Director-only actions require the director token, which agent environments never receive.

## Consequences
No anonymous local access. Remote or multi-user access later needs real authentication (a natural fit for Keycloak).

## Reopen if
Office is offered as a shared or hosted instance.
