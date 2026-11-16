# Progress: gotchas on Keycloak and IAM

Part of the progress notes: see [`../PROGRESS.md`](../PROGRESS.md). A branch adds or edits lines here; the State and Next step of PROGRESS.md change only at merge to main.

- The Keycloak mapper jar must exist before `docker compose up`: `mvn -f iam/keycloak-extensions/pom.xml verify`, then copy `target/floor42-keycloak-extensions.jar` to `iam/keycloak/providers/`. The image build fails without it, and the realm file references the mappers.
- Client secrets are hex (`openssl rand -hex 32`): Keycloak URL-decodes HTTP Basic credentials, so a secret with other characters fails to authenticate.
- The e2e daemon sets `floor42.sandbox.mandatory=false` (the fake Claude Code never reaches a real shell), as the `test` and `dev` profiles do; `bin/floor42-dev --real-claude` turns it back on. Without that line a REAL e2e mission fails with `SANDBOX_REQUIRED`.
- `auth_time` in the director's id token is in whole seconds; the step-up check compares it to the redirect second, not the millisecond.
- The Keycloak mapper integration test (`MappersIT`) needs Docker and is skipped without it.
