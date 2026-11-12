# IAM mode with your own Keycloak

IAM mode needs a Keycloak that issues the agents' tokens. `iam/compose.yaml` is a development convenience; this guide
turns IAM mode on against a Keycloak you already run. Background: [iam/README.md](../iam/README.md), ADR-0064,
[threat model](threat-model.md).

## What was measured

- Keycloak **26.8.1**, image `quay.io/keycloak/keycloak:26.8.1`, `start-dev`, a blank instance, 2026-11-09. Nothing else
  was tried. 26.2 and later have standard token exchange V2 (used here), so later 26.x should work; older versions
  and Keycloak 25 and below (legacy exchange) are not supported. Before trusting a newer
  version, run the checks below: they are the test.
- Not measured: a production `start` with TLS and a real hostname, an external database, a realm that already holds
  other clients, a realm named other than `floor42`.

## 1. Requirements on the Keycloak side

- Standard token exchange V2 is on by default in 26.2+; it is switched on per client below.
- `KC_HOSTNAME` must be set so the `iss` claim is stable whatever address a request used (Floor42 compares it with the
  issuer you configure). Example: `KC_HOSTNAME=http://localhost:8180`, or your `https://` URL.
- The Floor42 daemon must reach `<issuer>/protocol/openid-connect/*` and `<issuer with /realms/ replaced by /admin/realms/>`
  from the machine it runs on. The director's browser must reach the issuer too.
- Admin access to the master realm for the one-time setup (the daemon never needs it afterwards).

## 2. Install the `act` mapper extension

The extension adds two protocol mappers, `floor42-act-mapper` (the `act` claim) and `floor42-context-mapper`
(`floor42 = {launch, mission, ticket}`). Without it exchanged tokens have no `act` and Floor42 refuses every session.

```sh
# JDK 21 or later, Maven. Tests are skipped here; MappersIT needs Docker.
mvn -f iam/keycloak-extensions/pom.xml package -DskipTests
# -> iam/keycloak-extensions/target/floor42-keycloak-extensions.jar   (compiled against Keycloak 26.8.1)
```

Copy the jar into the providers directory of the Keycloak, then rebuild it:

```sh
cp iam/keycloak-extensions/target/floor42-keycloak-extensions.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build            # needed before `kc.sh start --optimized`; `start-dev` and `start` rebuild by themselves
```

Docker: mount the jar as `/opt/keycloak/providers/floor42-keycloak-extensions.jar` (measured with `start-dev`, the
log shows `KC-SERVICES0047: floor42-act-mapper ...`, an expected warning: the SPI is internal), or copy it in an image
build like `iam/keycloak/Dockerfile`. Rebuild the jar when you move to another Keycloak minor version: the SPI is
internal and may change.

## 3. Create the realm, the clients and the user

Fast path for a blank Keycloak, measured: import `iam/realm/floor42-realm.json` through the admin REST API after
replacing the two placeholders `${FLOOR42_DAEMON_SECRET}` and `${FLOOR42_API_SECRET}` by fresh secrets, then create the
user. `iam/byo-keycloak-check.sh provision` does exactly that (see section 6). Use **hex** secrets
(`openssl rand -hex 32`): Keycloak URL-decodes HTTP Basic credentials, so `+`, `/` and `=` break client login.

**Warning: every user of the realm who can sign in on the desk client becomes the director.** Floor42 does not pin a
subject or a role on the desk sign-in, so any account the realm lets through is the director for the whole daemon. Use a
dedicated realm that holds only the director's account, never a shared company realm.

To reuse a realm instead, create the following by hand (the realm file is the reference; this path was not run).

**Realm**: access token lifespan 300 s, default signature algorithm RS256, registration off. List the standard client
scopes explicitly if you import (a realm file that lists any scope gets no built-in one, and without `basic` a token has
no `sub`); an existing realm already has them.

| Client | Type | Settings |
|---|---|---|
| `floor42-desk` | public | Standard flow only (no implicit, no direct grant, no service account). PKCE method `S256`. Redirect URIs `http://127.0.0.1:7420/auth/callback`, `http://localhost:7420/auth/callback` and the same two on port 7421 (dev mode). Web origins `+`. Optional scope `offline_access` available (plan approval asks for it). |
| `floor42-daemon` | confidential, secret | Service account on; no standard flow, no direct grant. Attribute `standard.token.exchange.enabled=true`. Service-account roles from `realm-management`: `manage-clients`, `view-clients`, `query-clients`. Nothing else. |
| `floor42-api` | confidential, secret | No flow, no service account. It is the audience of every agent session token and the only client that can introspect one. |

**Client scope `floor42-agent`** (protocol `openid-connect`, not shown on consent, not in the token `scope`), with three
mappers:

1. `oidc-audience-mapper`, included client audience `floor42-api`, access token and introspection on, ID token off.
2. `floor42-act-mapper` (from the extension), access token on.
3. `floor42-context-mapper` (from the extension), access token on.

Floor42 puts this scope in the default scopes of every agent client it creates. It also needs no change on the realm's
default scopes.

**User `director`**: enabled, email verified, a permanent password you choose. Plan
approval signs in through `floor42-desk` as this user, so that sign-in must work for it.

**Agent clients** (`floor42-agent-<agentId>`) are created by the daemon at first start and hire, using its service
account: confidential, service account on, standard flow and direct grant off, default scope `floor42-agent`,
`standard.token.exchange.enabled=true`, `access.token.lifespan` = `maxTokenHours * 3600` (at most 28800). It also adds an
audience mapper named `aud-<client>` on `floor42-desk` (the requester must be in the subject token's audience). Never set
`standard.token.exchange.enableRefreshRequestedTokenType` on an agent client. You do not create these by hand.

## 4. Token lifespans

| Token | Lifespan | Set by |
|---|---|---|
| Director access token (from the desk) | 300 s | realm `accessTokenLifespan` |
| Agent session token (exchange) | `maxTokenHours` hours, at most 8 (client attribute `access.token.lifespan`) | Floor42, on each agent client; the requester client's lifespan applies, not the subject token's |
| Offline refresh token (per-mission delegation) | until revoked or the offline session idle limit | realm SSO offline settings; Floor42 revokes it when the mission ends |

Do not shorten the realm's offline session idle below your longest mission, or its delegation dies early.

## 5. The Floor42 side

IAM mode needs the WSL sandbox turned on and working (`IAM_NEEDS_SANDBOX` otherwise) and a restart to change.

1. Enable IAM mode in Settings or with `PUT /api/iam/settings` (the web screen may lag the API): issuer `https://your-keycloak/realms/floor42` (must equal the `iss` claim exactly), max token
   hours (1 to 8). Fields:
   (`enabled`, `issuer`, `maxTokenHours`).
2. The secrets of `floor42-daemon` and `floor42-api` go through the secret form of that screen (field `daemonSecret`
   and `apiSecret` of the same call). They are written to the vault (AES-GCM, key outside the worktrees) and never
   returned. Take them from the secrets you generated in step 3; do not put them in a file in the repository.
3. Restart the daemon. At start Floor42 runs the checks of section 7.

The properties `floor42.iam.enabled`, `floor42.iam.issuer` and `floor42.iam.max-token-hours` override the saved file.

## 6. Prove your Keycloak with the script

`iam/byo-keycloak-check.sh` runs on any bash with `curl` and `openssl` (no jq). It was run in WSL against a blank
throwaway container.

```sh
# 1. Blank Keycloak (throwaway; the image must already be pulled). Jar built as in section 2.
export KC_ADMIN_USER=admin KC_ADMIN_PASSWORD=$(openssl rand -hex 24) KC_URL=http://localhost:18180
docker run -d --name floor42-byo-kc -p 127.0.0.1:18180:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD="$KC_ADMIN_PASSWORD" -e KC_HOSTNAME=$KC_URL \
  -v "$PWD/iam/keycloak-extensions/target/floor42-keycloak-extensions.jar":/opt/keycloak/providers/floor42-keycloak-extensions.jar:ro \
  quay.io/keycloak/keycloak:26.8.1 start-dev
until curl -s -o /dev/null $KC_URL/realms/master; do sleep 2; done

# 2. Realm, clients, director (secrets generated into $TMPDIR/floor42-byo/secrets.env, mode 600; only lengths printed)
iam/byo-keycloak-check.sh provision
# 3. The token chain
iam/byo-keycloak-check.sh check
# 4. Remove the throwaway
docker rm -f floor42-byo-kc
```

`check` uses the daemon's own service account for everything it does to the realm, so it also proves the three
`realm-management` roles suffice. It opens direct grant on `floor42-desk` for the seconds needed to get a director token,
closes it again, and deletes the test agent client (the `aud-floor42-agent-byocheck` mapper on the desk stays; rerun is
fine).

Measured output on 26.8.1, 2026-11-09 (blank container, then a second run on the same realm; both passed):

```
PASS  1 issuer is http://localhost:18180/realms/floor42 (this is the value for Floor42's Settings)
PASS  2 floor42-daemon client_credentials token (1057 chars)
PASS  3 floor42-daemon created floor42-agent-byocheck (201): manage-clients suffices
PASS  5 aud is exactly floor42-api            PASS  5 act claim present (the extension is loaded)
PASS  5 floor42.launch is the one we sent     PASS  5 no refresh token in the exchange
PASS  6 the agent's own token has no act claim
PASS  7 floor42-api introspects the session token: active
PASS  8 agent client disabled: the session token is inactive at once
ALL CHECKS PASSED
```

To check your own Keycloak (provisioned by hand), run `check` with `KC_URL` and a `WORK_DIR/secrets.env` that holds
`FLOOR42_DAEMON_SECRET`, `FLOOR42_API_SECRET` and `DIRECTOR_PASSWORD`. Its director-token step temporarily enables the
direct grant on `floor42-desk`: do not run it against a Keycloak that serves other people while you read this.

## 7. Verification checklist: what Floor42 itself checks

`GET /api/iam/status` (Settings, IAM) says whether IAM mode is running and why not. At start, in this order, anything
short of UP blocks every session (fail closed):

| Check | Failing status | Fix |
|---|---|---|
| Vault key sound | `IAM_VAULT_KEY_INVALID` | restore or recreate the vault key (see the reason text) |
| WSL sandbox on and working | `IAM_NEEDS_SANDBOX` | turn the sandbox on |
| Process binding supported | `IAM_NEEDS_PROCESS_BINDING` | unsupported platform |
| Issuer answers: JWKS fetched | `UNREACHABLE`, "Keycloak does not answer at <issuer>" | wrong issuer, network, `KC_HOSTNAME` |
| `floor42-api` secret saved | `IAM_API_SECRET_MISSING` | enter it in Settings |
| `floor42-api` credential accepted by introspection (answer 200 `active:false` for a probe token) | `IAM_API_CREDENTIAL_REFUSED` | wrong secret, or client not confidential |
| Every agent on staff has its client (created with the `floor42-daemon` credential) | `UNREACHABLE`, "Keycloak is not usable: ..." | daemon secret wrong (401), missing `manage-clients` roles (403), `floor42-agent` scope absent |

After start, every 60 s Floor42 asks Keycloak whether each live session's agent client is still enabled; Keycloak
unreachable blocks new sessions at once and refuses running ones after two misses. On each session, Floor42 verifies
RS256 signature, `iss`, `aud` = `floor42-api`, `exp`, `act`, the `floor42` claim and the socket owner: a missing `act`
means the extension is not loaded.

Map of script step to Floor42 check: 1 issuer and JWKS, 2 and 3 agent clients, 5 the session token claims, 7 the probe
and live introspection, 8 the 60 s watch.

## What this does not cover

Production hardening of your Keycloak (TLS, hostname, database, admin exposure) is yours. Identity proves which session
acts and for whom, not what the model intends: the permission table remains the defence.
