# ADR-0003: Three modules, framework-free domain

Date: 2026-10-20 · Status: Accepted (module names and package list updated 2026-11-06)

## Context
The spec requires separating rendering, interactions, domain, orchestration, runtimes, permissions, persistence, events and reports, without artificial microservices.

## Decision
- `floor42-domain`: entities, state machines, invariants. JDK only.
- `floor42-server`: one Quarkus application, organised in packages (`api`, `app`, `config`, `iam`, `journal`, `runtime`, `security`).
- `floor42-web`: UI; consumes projections only. Not a Maven module of the root reactor.

Package dependency rules are enforced by ArchUnit tests, not by convention.

## Consequences
Domain tests run in milliseconds and boundaries are checked on every build. Splitting further later does not require moving code across repositories.

## Reopen if
A package needs an independent release cycle.
