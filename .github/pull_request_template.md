## What and why

<!-- What this changes, and the issue or ADR it follows. -->

## How it was checked

<!-- The tests added or run; for a UI change, what you looked at and where. -->

## Checklist

- [ ] `./mvnw spotless:apply && ./mvnw verify` passes, and `npm test` in `floor42-web`.
- [ ] No test calls a paid model or a real agent (the fake Claude Code in `floor42-server/src/test/resources/fake-claude`).
- [ ] Every new event carries its `Origin`; nothing simulated or estimated is shown as real or measured.
- [ ] Permissions are enforced by code (hooks, policy), not by prompt text.
- [ ] New UI texts exist in both catalogues of `floor42-web/src/i18n/` (English and French).
- [ ] A structural decision has an ADR in `docs/adr/`; the change has a line in `CHANGELOG.md` under Unreleased.
- [ ] Every commit is signed off (`Signed-off-by:`, Developer Certificate of Origin, see `CONTRIBUTING.md`).
