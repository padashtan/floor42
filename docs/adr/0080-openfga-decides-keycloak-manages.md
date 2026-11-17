# ADR-0080: OpenFGA decides, Keycloak manages, Floor42 enforces

Date: 2026-11-16 · Status: Accepted (1.3.0) · Supersedes the "Later" section of ADR-0065 · Amends ADR-0064 (point 12), ADR-0072 (points 2 and 8)

## Context

ADR-0065 wrote the direction and left it for later. The decisions that are relationships (this agent may call this
resource, this mission's delegation names it, this MCP tool is allowed) are answered today by reading JSON files
(`ResourceCatalogue.allowed`, `McpServers`). They work, but the policy cannot be tested apart from the code that reads
the files, and nobody can see who changed what. This record lays the ground (node N1 of 1.3.0): the model as code, its
tests, a client, the container, fail closed. Moving the decisions of the brokers onto it is the next node (N2), which
completes this record with its own section.

## Decision

1. **Keycloak manages** identities, roles and the strength of a sign-in. **OpenFGA decides** relationships.
   **Floor42 enforces**: it asks before the domain, records the answer in the decision basis, and never falls back to
   the files when OpenFGA does not answer.
2. **The model is code**: `iam/openfga/model.fga` (schema 1.1), its tests `model.fga.yaml` (one per row of the policy,
   `fga model test`) and `model.json` (what Floor42 loads, produced by `fga model transform`; Floor42 has no DSL
   parser). A Linux CI job (`.github/workflows/model.yml`, `openfga/cli` pinned by digest, fga v0.9.0) runs the tests
   and fails if `model.json` differs from the transform of `model.fga`.
3. **Two relations are never stored**, they are contextual tuples sent on each Check: `mission#runner` (the calling
   session's mission only, which is what makes "grant AND this mission's delegation" exact: a stored runner would let an
   agent staffed on two missions pass on what only the first delegated; the test `why-runner-is-never-stored` shows it)
   and `role#member` (from the roles of a Keycloak token Floor42 just verified). The client refuses to write them.
4. **The pinned hash is in the object id** of an MCP tool (`mcp_tool:<server>/<tool>/<sha256>`): a tool whose definition
   changed is another object with no tuple, so it is refused by construction, not by a second check.
5. **`countersigner: reviewer but not director`**: the director cannot be the second pair of eyes whatever roles their
   account holds. Code also refuses the same subject twice (N3).
6. **Client**: the JDK `HttpClient` and Jackson, no OpenFGA SDK (one dependency and its transitive ones for three
   endpoints). `FgaClient` has `check`, `write` and `read`; every check pins the model id and sends
   `consistency: HIGHER_CONSISTENCY`; no cache; 2 s for the whole answer; a write is at most 100 tuples, one
   transaction, `on_duplicate` and `on_missing` set to `ignore` (no read before write).
7. **Fail closed.** Anything that is not a 200 with a boolean is `FGA_UNAVAILABLE` (a 401 is the same code with its own
   message, "OpenFGA refused Floor42's key"); a 400 or 404 on a check is `FGA_MODEL_MISMATCH`, logged once; a write
   OpenFGA or the client refuses as input is `FGA_INVALID`; IAM mode on without OpenFGA settings is `IAM_NEEDS_FGA`.
8. **Store and model** (`FgaStore`): at the start of IAM mode find or create the store `floor42`, compare the SHA-256 of
   the shipped `model.json` with the one in `<data>/iam/fga.json`, write the model when it differs (append-only in
   OpenFGA) and record its id. Floor42 only checks against the recorded id. A recorded id the store does not know is
   `FGA_MODEL_MISMATCH`, never a silent rewrite. Any of these codes makes `IamStatus` `REFUSED_TO_START`: no new session
   starts, and the background Keycloak check cannot clear it. Running sessions keep their hook path (no OpenFGA there);
   their broker calls are refused until OpenFGA is back.
9. **Container**: `iam/compose.yaml` gains `openfga-migrate` (one-shot) and `openfga`, the image pinned by digest
   (v1.22.1), the Postgres datastore in a database `openfga` that `iam/bootstrap.sh` creates idempotently, the preshared
   key, HTTP on `127.0.0.1:8181` only.

## Measured (M0, 2026-11-16)

- OpenFGA v1.22.1, `sha256:b85207...`; `fga model transform` accepts the model as written and the store stores it.
- The server accepts an unknown `consistency` value silently. Only a run with its caches on (stale default, fresh with
  `HIGHER_CONSISTENCY`) proves the field works, so a test checks the request body sends it.
- `on_duplicate` and `on_missing` set to `ignore` are accepted; strict mode answers 400.
- The preshared key refuses a call without it (401), `/healthz` needs none.
- One Check on loopback: p50 1.8 ms, p99 2.8 ms (Python client in WSL; to measure once from the JDK client).
- Against the real container (in-memory datastore, `FgaIT`): grant and the session's mission allow, another mission's
  runner refuses, a deleted tuple refuses the next call.

## Consequences

- One new container and a database in the existing Postgres; no new Java dependency; one new Linux CI job.
- The model tests run on Linux only (Docker image); the Java refusal tests run on both systems.
- One store per data folder, one model line, no multi-tenant (stated in `FgaSettings`).
- The key lives in the vault entry `fga-key` or the property `floor42.iam.fga.key`; a screen to enter it comes with N2.
- A recovery from OpenFGA being down needs a restart in this node (the status is decided at start). Reopen if that
  proves a nuisance.

## Rejected, with their cost

| Option | Cost | Why not |
|---|---|---|
| OpenFGA Java SDK | a dependency and its transitive ones for three endpoints | the JDK client is a few dozen lines |
| In-memory datastore in production | none to run | every tuple lost at a restart: every resource call refused until re-ticked |
| OpenFGA's SQLite datastore | no Postgres coupling | maturity not measured; Keycloak's Postgres is already there |
| An evaluator of the model in Java | no container | re-implementing a decision engine is the code never to write |
| OPA or Cerbos | one container | attribute and policy engines, not relationships |
| A check cache in Floor42 | fewer calls | a revoked grant would still allow until expiry; 1 to 3 ms per check does not need it |

## Signal to reopen

A check p99 above 20 ms on the real path, a second store needed (two offices on one OpenFGA), or OpenFGA's defaults
changing the meaning of `HIGHER_CONSISTENCY`.
