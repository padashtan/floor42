# ADR-0008: AGPL-3.0 + commercial license + CLA

Date: 2026-10-20 · Status: Superseded by [ADR-0043](0043-apache-2-license.md) (Apache-2.0, 2026-10-31)

## Context
Office must be freely usable while letting the maintainer earn money. Options compared: Apache-2.0/MIT, AGPL-3.0 with dual licensing, FSL (source-available), open core.

## Decision
AGPL-3.0 for everyone, a commercial license for organizations that cannot comply, and a CLA so contributions can be relicensed. Donations (GitHub Sponsors, crypto through a funding link) are welcome but are not the business model.

## Consequences
- Some companies ban the AGPL; they are the commercial license audience.
- No external contribution may be merged before the CLA exists.
- "Office" collides with the Microsoft Office trademark: it stays a codename until a public name is chosen.
- The commercial license and CLA texts need a lawyer's review before publication.

## Reopen if
Adoption is blocked by the AGPL more than revenue justifies.
