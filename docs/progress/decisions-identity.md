# Progress: decisions on agent identity

Part of the progress notes: see [`../PROGRESS.md`](../PROGRESS.md). A branch adds or edits lines here; the State and Next step of PROGRESS.md change only at merge to main.

### Agent identity (1.17.0, ADR-0064)

- IAM mode is opt-in (Settings, IAM section; a daemon restart turns it on). Off, nothing changes and no Keycloak is needed. Simulated sessions use a local fake issuer (`urn:floor42:simulated`), never accepted for a REAL session.
- Delegation is per mission: a Keycloak step-up re-authentication at each plan approval gives one offline session per mission. Measured: offline tokens of one SSO session share one offline session, so revoking one kills the others. The token broker and the agents' session tokens come from that offline token.
- The session token carries `floor42.{launch,mission,ticket}`, not Floor42's session id in `sid`, because Keycloak sets `sid` itself. Keycloak V2 emits no `act`: the mapper SPI in `iam/keycloak-extensions/` writes it. The requester must be in the subject token's `aud`. A mapper keeps the long lifespan only on a token whose audience is exactly `floor42-api` (the mapper cap rule).
- Introspection works only as a client in the token's `aud`: the 60 s check introspects as `floor42-api`.
- Process binding uses the in-memory PID from `builder.start()`, never `process.txt`. Break-glass: the console link still signs the director in but mints no delegation.
- Committee and auto-approved missions are blocked in IAM mode until a Keycloak approval. Broker catalogue and grants are files, edited through the API; the screen has no editor for them.
- Session time (v1.1): the screen warns 30 minutes before a delegation ends and 10 minutes before an agent token ends, with no journal event. Nothing is renewed: a token is minted once at launch and an expired one is refused (`TokenRefusal.EXPIRED`), as `IamSessionsTest` records.
