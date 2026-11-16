# ADR-0070: A repository profile and a preflight before a real mission

Date: 2026-11-13 · Status: Accepted (v1.1)

## Context

Before a real mission starts, the facts a director needs sat in separate places: the tools the project needs
(`ProjectToolchain`), what the sandbox has (`SandboxResource`), uncommitted files (`RepositoryFacts`), Claude Code
availability (`RUNTIME_UNAVAILABLE` after the click), and the sandbox refusal (`SANDBOX_REQUIRED`, at the first session,
after the plan). Nothing named the build tool, proposed a checks command, or said anything of the sandbox having no
network.

## Decision

1. **A derived profile, no event.** The profile is read from the folder's files (build tool, files, a proposed checks
   command from a fixed table) and from the journal's state (the checks command of the newest real mission on the
   folder). Nothing is saved, so no journal event is added (director's decision, question 3). An editable per-project
   profile (`ProjectProfileSet`) stays a later candidate.
2. **One read-only route.** `GET /api/preflight?project=&check=` returns the profile and an ordered list of lines
   (BLOCK, WARN, INFO, OK) with a verdict. It measures nothing and journals nothing. The logic is a pure class
   (`Preflight`) fed with values, so it is tested without Quarkus.
3. **What blocks.** Only what the server refuses: Claude Code unavailable, and the sandbox required but not working.
   A missing tool, an older tool, an unmeasured tool, a dirty tree, no checks command and a likely download with the
   sandbox network off all warn (question 4: a missing tool warns).
4. **The sandbox refusal moves to creation.** `MissionFactory` now refuses `SANDBOX_REQUIRED` when the mission is
   created, beside `RUNTIME_UNAVAILABLE`, so no mission exists that cannot run a shell. The refusal at the session's
   start (ADR-0063) stays as the last line of defence. The UI block is a courtesy; the rule is enforced by code.
5. **Nothing is measured by reading.** A tool never measured is "not measured", never "missing". The sandbox's offline
   ability is not measured either: with the network off, a build file that usually downloads (`mvnw`, `gradlew`,
   `package.json` without `node_modules`) gives a warning worded "usually downloads"; without such a signal the line is
   "Floor42 did not read the dependency cache" (INFO). Never presented as a fact (Origin rule).
6. **Tools are judged where the shell runs.** Tool and offline lines appear only when the sandbox is in use.

## Consequences

- A director sees blockers and warnings before the click; Create is disabled on a blocker, with the reason.
- A machine without a working sandbox now fails at creation (409 `SANDBOX_REQUIRED`) instead of at the first session.
- `sandbox.available()` may run its one measure (cached) when the route is first read with the sandbox in use.
- The default checks table is small and fixed; a project it does not know gets no proposal and a warning.

## Reopen if

Directors want to correct the profile (build tool, reference branch, sensitive paths): that needs a journaled event and
the director's approval. Also if a measured offline build (one run in the sandbox) is wanted: candidate for v1.2.
