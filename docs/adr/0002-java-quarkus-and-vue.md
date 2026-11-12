# ADR-0002: Java 25 + Quarkus daemon, Vue 3 + TypeScript UI

Date: 2026-10-20 · Status: Accepted

## Context
The maintainer is fluent in Java/Quarkus; maintainability over years matters more than a single-language stack. The browser only runs JavaScript, so the UI is TypeScript regardless.

## Decision
- Daemon: Java 25 (LTS) + Quarkus, built with the Maven wrapper.
- UI: Vue 3 + TypeScript + Vite, served by Quarkus through Quinoa.
- Contracts shared with the UI are generated (OpenAPI for the API, JSON Schema for events), never hand-copied.

## Consequences
- Vue's fine-grained reactivity fits a continuous event stream; its API has been stable since 2020.
- Two languages to maintain; mitigated by generated contracts and by keeping all business logic in Java.
- The 3D scene is a plain TypeScript module, independent of Vue, so the framework choice stays low-stakes.

## Status of the contract generation
Not done yet (I3): the TypeScript types in `office-web/src/api/types.ts` are hand-written mirrors of the Java views. Generating them is tracked; until then, a change to a view must update both sides in the same commit.

## Reopen if
Contributor onboarding is blocked by the framework, or Quinoa stops tracking Quarkus releases.
