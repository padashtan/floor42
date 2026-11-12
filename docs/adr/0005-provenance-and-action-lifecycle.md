# ADR-0005: Mandatory provenance and explicit action lifecycle

Date: 2026-10-20 · Status: Accepted

## Context
The spec forbids confusing simulated and real, proposed and executed, estimated and measured.

## Decision
- Every event carries `origin` (`SIMULATED` or `REAL`), actor, mission, authorization reference and causation id.
- Agent actions follow `PROPOSED -> AUTHORIZED | DENIED -> REQUESTED -> EXECUTED -> CONFIRMED | FAILED`, each step backed by an observed signal.
- Metrics carry a quality: `MEASURED`, `PROVIDED`, `COMPUTED`, `ESTIMATED`, `UNAVAILABLE`.
- The scene animates an execution only from `EXECUTED` onwards.

## Consequences
Provenance is part of the types from the first commit; retrofitting it would be very expensive.

## Reopen if
Never silently. Adding a new origin or quality is a governance change.
