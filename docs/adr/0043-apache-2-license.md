# ADR-0043: Apache-2.0, contributions under the DCO; supersedes ADR-0008

Date: 2026-10-31 · Status: Accepted · Supersedes [ADR-0008](0008-licensing.md)

## Context

ADR-0008 chose AGPL-3.0, a commercial license for organizations that cannot comply, and a CLA so that contributions
could be relicensed. None of the three was ever exercised: no commercial license was sold, the CLA text was never
written, so no outside contribution could be merged. Every commit so far is the sole author's.

Floor42 now goes public as a showcase in its own repository, `padashtan/floor42`. Its purpose changes: being read,
tried and reused matters more than protecting a revenue stream that does not exist.

## Options

- **Keep AGPL-3.0 + commercial license + CLA (ADR-0008).** Protects against a hosted copy that gives nothing back.
  Cost: many companies forbid the AGPL outright, so the very readers a showcase wants (engineers evaluating it at
  work) cannot even try it there; a CLA is a known brake on first contributions; the commercial license and the CLA
  still need a lawyer before they exist.
- **MIT.** As permissive, shorter. Cost: no explicit patent grant and no NOTICE mechanism.
- **Apache-2.0.** Permissive, explicit patent grant and patent retaliation, a NOTICE file for attribution; the license
  most enterprise legal teams pre-approve, and the one of most of Floor42's own dependencies (Quarkus, Netty,
  SmallRye, Jackson, sqlite-jdbc, Flyway).

## Decision

Floor42 is licensed under **Apache-2.0** (`LICENSE`, `NOTICE`), from this commit on. The sole author relicenses his own
code; there is no other copyright holder to ask.

Contributions are accepted under the **Developer Certificate of Origin**: each commit carries a `Signed-off-by:` line
(`CONTRIBUTING.md`). No CLA, no relicensing right granted to the maintainer.

Why:

- **Readers and adoption over protection.** A showcase is worth what people read, run and borrow from it.
- **Enterprise-friendly.** Apache-2.0 passes the legal review that the AGPL often fails, and its patent grant is what
  those reviews look for.
- **The business options stay open.** The author can still offer paid hosting, support or custom work later; none of
  them needs a copyleft license or a CLA.

## Consequences

- `LICENSE` holds the Apache-2.0 text; `NOTICE` names the copyright holder and states that Claude Code is not part of
  Floor42. `pom.xml`, `floor42-web/package.json`, the OpenAPI document, the Docker image labels, the README and
  `CONTRIBUTING.md` say Apache-2.0.
- Nothing was published under AGPL-3.0: the versions before this decision were internal to the private development.
  Every public release of Floor42, from v1.0.0 on, is Apache-2.0.
- Dependencies under EPL-2.0 (Jakarta APIs, Vert.x) and LGPL-2.1 (Narayana) are used as libraries, unchanged; they are
  compatible with an Apache-2.0 application. As in ADR-0041, a new dependency's license is checked before it is added;
  ADR-0041's license argument against pty4j (EPL-1.0 against AGPL-3.0) no longer applies, its other reasons stand.
- Anyone may host Floor42 for others without sharing changes. Accepted: that is the trade.
- The published Docker image never contains Claude Code, which is Anthropic's software under its own terms.

## Reopen when

- A company hosts Floor42 commercially in a way that clearly harms the project, and contributions do not follow; or
- a dependency the project needs is incompatible with Apache-2.0.
