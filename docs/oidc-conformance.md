# OIDC conformance of the director's sign-in

Floor42's sign-in through Keycloak (Authorization Code, PKCE S256, nonce, a public client `floor42-desk`, the ID token
checked by `IdTokenCheck`, the keys by `Jwks`) is run against the relying party tests of the OpenID Foundation
[conformance suite](https://gitlab.com/openid/conformance-suite). This is a test run, not a certification: the suite
reports nothing about Floor42 to the Foundation.

## Rerun

```sh
make oidc-conformance      # Linux or WSL: Docker, Python 3, Java 25, keytool, openssl
```

It packages the daemon without the web UI, starts the suite (`tools/conformance/suite.sh up`), runs
`tools/conformance/run.py`, and stops the suite whatever the outcome. A few minutes. `DEBUG=1 python3 tools/conformance/run.py <module>...` runs chosen modules and prints the suite's log
and the keys it published. Results: `target/oidc-conformance/results.md` and `results.json`. The manual workflow
`OIDC conformance` (Actions, `workflow_dispatch`) does the same on a runner; it was written to the same commands but
has not been run there.

Suite measured: images `registry.gitlab.com/openid/conformance-suite` and `.../nginx`, pinned by digest in
`tools/conformance/compose.yaml` (built 2026-11-09 from the suite's `master`, commit `1e6f5433`), MongoDB 6.0.13 (also by digest). No
build: the images are pulled. To move to a newer suite, change the digests and rerun.

## What the run does

1. `suite.sh up` makes a certificate for `localhost.emobix.co.uk` (it resolves to 127.0.0.1) and starts the suite on
   `https://localhost.emobix.co.uk:8443`, bound to 127.0.0.1. The suite's own certificate has no subject alternative
   name, which the JVM's host name check refuses.
2. `run.py` starts the packaged daemon in IAM mode with `floor42.iam.issuer=https://localhost.emobix.co.uk:8443/test/a/floor42/`
   and a trust store holding only that certificate, in a temp data folder, with the fake Claude Code. IAM mode is
   reported "not usable" (no WSL sandbox): the sign-in routes do not depend on it.
3. It creates the plan `oidcc-client-test-plan` with the variant below, then for each module starts it, plays one
   browser sign-in (Floor42's `/auth/keycloak`, the suite's `authorize`, Floor42's `/auth/callback`, cookies kept as a
   browser keeps them) and reads Floor42's answer: 303 and a session cookie is an accept, 401 `SIGN_IN_FAILED` a
   reject. A reject counts only when all of these hold: the suite logged the client's token endpoint call, Floor42's
   daemon log carries exactly one `ID token refused: rule=<rule>` line (the claims check refused a token it had
   received, so the token request answered; the line names the rule, never the token or a claim value) and the rule is
   the one expected for that module; for modules whose fault is behind the key lookup, the suite also logged the JWKS
   fetch. An accept needs no such line. A module fails when any of this differs, when the suite logged a FAILURE, or
   when the module is missing from the expectation table of `run.py` (a new suite module must be classified).
   The suite's host name is pinned to 127.0.0.1 for the run and for the daemon (`-Djdk.net.hosts.file`), not
   resolved through public DNS.
4. `nginx.conf` is the suite's own 8443 server plus three rewrites: Floor42 builds Keycloak's endpoint paths from the issuer
   (`/protocol/openid-connect/auth`, `token`, `certs`) and the suite serves `/authorize`, `/token`, `/jwks`.

Plan variant: `client_registration=static_client`, `response_type=code`, `response_mode=default`,
`client_auth_type=none`, `request_type=plain_http_request`. Client: `client_id=floor42-desk`, `redirect_uri=http://127.0.0.1:7431/auth/callback`.
That is Floor42's whole profile; Floor42 has no other.

The suite cannot give its own verdict on these modules: a code-flow module ends when the client calls the userinfo
endpoint, which Floor42 never does, so the modules stay `WAITING` and are stopped by the run. Floor42's answer, judged
by the table in `run.py` (`EXPECT`), is the verdict; the suite's log is the check that the requests Floor42 made
(authorization request, token request) were well formed. It held no FAILURE and no WARNING in any module.

## Results (2026-11-09)

14 passed, 0 failed, 1 known deviation, 13 not applicable (second run, after the review fixes; the first run gave 14 and 14 with the known deviation counted as not applicable).

| Module | Expected | Refusal rule logged | Result |
|---|---|---|---|
| oidcc-client-test | accept | none | pass |
| oidcc-client-test-idtoken-sig-rs256 | accept | none | pass |
| oidcc-client-test-signing-key-rotation | accept | none | pass |
| oidcc-client-test-signing-key-rotation-just-before-signing | accept | none | pass |
| oidcc-client-test-nonce-invalid | reject | nonce | pass |
| oidcc-client-test-missing-aud | reject | aud | pass |
| oidcc-client-test-invalid-aud | reject | aud | pass |
| oidcc-client-test-invalid-iss | reject | iss | pass |
| oidcc-client-test-idtoken-sig-none | reject | alg | pass |
| oidcc-client-test-invalid-sig-rs256 | reject | signature | pass |
| oidcc-client-test-invalid-sig-es256 | reject | alg | pass |
| oidcc-client-test-kid-absent-multiple-jwks | reject | kid | pass |
| oidcc-client-test-missing-iat | reject | iat | pass, after the fix below |
| oidcc-client-test-missing-sub | reject | sub | pass, after the fix below |

Known deviation (Floor42 is stricter than the specification, reason below): `kid-absent-single-jwks`.

Not applicable (reasons in the next section): `client-secret-basic`, `scope-userinfo-claims`, `userinfo-invalid-sub`, `userinfo-bearer-header`, `userinfo-bearer-body`, `userinfo-signed`,
`aggregated-claims`, `distributed-claims`, `discovery-openid-config`, `discovery-jwks-uri-keys`,
`discovery-issuer-mismatch`, `discovery-webfinger-acct`, `discovery-webfinger-url` (13). The other response types of the
plan (implicit, hybrid, form_post) and the other client authentication types are outside Floor42's profile.

## Defects found and fixed

The first full run accepted two ID tokens it should have refused:

- `missing-sub`: an ID token without `sub` signed in. Core 2 makes `sub` required. `IdTokenCheck` now requires a text
  `sub` of 1 to 255 characters.
- `missing-iat`: an ID token without `iat` signed in (the time check only looked at `iat` when it was there). Core 2
  makes `iat` required; it is now.

Found by reading the same rules while there, not by a module: with several audiences, Core 3.1.3.7 asks for `azp`;
a token with `aud: [floor42-desk, other]` and no `azp` was accepted. It is refused now. A review then found that an
`aud` given as a JSON object was read by its values (`{"x":"floor42-desk"}` passed and skipped the `azp` rule): `aud`
must now be a string or a non-empty array of strings. `IdTokenCheck` also logs a WARN line `ID token refused: rule=<rule>`
(rules: alg, signature, kid, iss, sub, aud, azp, nonce, exp, iat, nbf, malformed) which the run uses as its proof. Tests:
`IdTokenCheckTest` (`aMissingOrMalformedSubjectIsRefused`, `aMissingIssuedAtIsRefused`,
`severalAudiencesNeedAnAuthorizedParty`, `anAudienceThatIsNotAStringOrAnArrayOfStringsIsRefused`,
`aSingleAudienceInAnArrayNeedsNoAuthorizedParty`; `StepUpApprovalsTest.anIdTokenWithoutSubOrIatIsNotAProof`). `IdTokenCheck` also serves the plan approval's step-up sign-in, so both paths
got the fix. Keycloak's own tokens carry all three claims; the fake Keycloak of the tests too.

Checked and already right: issuer compared exactly, audience, `azp` equal to the client when present, nonce
(constant time), `exp` and `iat` and `nbf` with 30 s of skew, RS256 only (any other algorithm, `none` included, and `crit` are refused),
a signature that does not match the key of the `kid`, keys under 2048 bits ignored, a key set with unusable keys
(unknown key type, curve or algorithm) still read, a rotation seen at once (the first unknown `kid` refetches the keys).

## Deviations

| Module | Category and reason |
|---|---|
| discovery-*, webfinger-* | Not applicable. Floor42 does not do discovery or webfinger. It derives the endpoint paths from the configured issuer, which is Keycloak's (ADR-0064), and requires the `iss` of the ID token to equal that issuer exactly. A different OP needs the three rewrites above. |
| userinfo-*, scope-userinfo-claims, aggregated-claims, distributed-claims | Not applicable. Floor42 never calls the userinfo endpoint and reads no claim but the ones of the signed ID token. |
| client-secret-basic | Not applicable. `floor42-desk` is a public client. Its authorization code is protected by PKCE S256. |
| kid-absent-single-jwks | Known deviation. The suite allows a client to verify an ID token with no `kid` against a single-key set. Floor42 refuses such a token, and ignores a key without a `kid`: Keycloak always sets one, and the refusal costs nothing. Stricter than the specification, on purpose. |
| Key refetch limit | Not a module. After an ID token whose `kid` no key matches, Floor42 refetches the keys once and then refuses to refetch for 30 s (`Jwks.MIN_REFETCH`), so a forged `kid` cannot make it hammer the issuer. A real rotation within 30 s of a forged `kid` waits for the end of that window (documented in `Jwks`). A token with no `kid` at all is refused before any fetch, so the run needs no wait between modules. |

## Not covered

- Discovery, dynamic registration, request objects, encrypted tokens, userinfo, refresh: not part of Floor42's sign-in.
- The suite's `form_post` error module and the other profiles: other response modes than the default query are not used.
- An authorization error answered by the OP (`error=access_denied`): Floor42 answers `SIGN_IN_FAILED` and does not redeem
  anything (`KeycloakSignInHandlersTest`); the suite's modules for it need the `form_post` profile.
- A production Keycloak behind TLS and a real host name: the run uses the suite as the issuer.
