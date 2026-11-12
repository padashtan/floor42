#!/usr/bin/env bash
# Bring your own Keycloak: provisions realm "floor42" on a BLANK Keycloak and checks the token chain Floor42 relies on.
# See docs/iam-bring-your-own-keycloak.md. Needs bash, curl, openssl, sed. No jq. Secrets are generated here, kept in
# a mode-600 folder, and never printed (only their length).
#
#   KC_URL=http://localhost:18180 KC_ADMIN_USER=admin KC_ADMIN_PASSWORD=... iam/byo-keycloak-check.sh provision
#   KC_URL=... KC_ADMIN_USER=... KC_ADMIN_PASSWORD=...                       iam/byo-keycloak-check.sh check
#
# provision: imports iam/realm/floor42-realm.json through the admin REST API (placeholders replaced by fresh secrets),
#            creates the user "director" with a generated password. Refuses when the realm exists.
# check:     the daemon's client_credentials token, an agent client created by the daemon (as Floor42 does), a token
#            exchange with the act claim, introspection by floor42-api. Removes the agent client; restores the desk.
set -euo pipefail
cd "$(dirname "$0")"
KC_URL=${KC_URL:-http://localhost:18180}
REALM=${REALM:-floor42}
WORK=${WORK_DIR:-${TMPDIR:-/tmp}/floor42-byo}
OIDC="$KC_URL/realms/$REALM/protocol/openid-connect"
ADMIN="$KC_URL/admin/realms/$REALM"
umask 077
mkdir -p "$WORK"

# First string value of a key in compact JSON (Keycloak answers are compact).
jstr() { grep -o "\"$1\" *: *\"[^\"]*\"" | head -1 | sed 's/^[^:]*: *"//; s/"$//'; }
payload() { local p; p=$(printf '%s' "$1" | cut -d. -f2 | tr '_-' '/+'); while [ $(( ${#p} % 4 )) -ne 0 ]; do p="$p="; done; printf '%s' "$p" | base64 -d 2>/dev/null; }
ok() { echo "PASS  $*"; }
bad() { echo "FAIL  $*" >&2; exit 1; }
want() { case "$2" in *"$1"*) ok "$3";; *) bad "$3 (expected $1)";; esac; }

master_token() {
  curl -s "$KC_URL/realms/master/protocol/openid-connect/token" -d grant_type=password -d client_id=admin-cli \
    -d username="${KC_ADMIN_USER:?}" --data-urlencode password="${KC_ADMIN_PASSWORD:?}" | jstr access_token
}

provision() {
  local mt; mt=$(master_token); [ -n "$mt" ] || bad "master admin login"
  local code; code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $mt" "$ADMIN")
  [ "$code" = 404 ] || bad "realm $REALM exists already ($code): reuse it by hand, see the guide"
  local dsec asec dpw
  dsec=$(openssl rand -hex 32); asec=$(openssl rand -hex 32); dpw=$(openssl rand -hex 16)
  printf 'FLOOR42_DAEMON_SECRET=%s\nFLOOR42_API_SECRET=%s\nDIRECTOR_PASSWORD=%s\n' "$dsec" "$asec" "$dpw" > "$WORK/secrets.env"
  sed -e "s/\${FLOOR42_DAEMON_SECRET}/$dsec/" -e "s/\${FLOOR42_API_SECRET}/$asec/" realm/floor42-realm.json > "$WORK/realm.json"
  code=$(curl -s -o "$WORK/import.out" -w '%{http_code}' -H "Authorization: Bearer $mt" -H 'Content-Type: application/json' \
    --data-binary @"$WORK/realm.json" "$KC_URL/admin/realms"); rm -f "$WORK/realm.json"
  [ "$code" = 201 ] || bad "realm import answered $code: $(cat "$WORK/import.out")"
  ok "realm $REALM imported (daemon secret ${#dsec} chars, api secret ${#asec} chars)"
  code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $mt" -H 'Content-Type: application/json' \
    -d '{"username":"director","enabled":true,"emailVerified":true,"firstName":"The","lastName":"Director","email":"director@floor42.invalid","credentials":[{"type":"password","value":"'"$dpw"'","temporary":false}]}' "$ADMIN/users")
  [ "$code" = 201 ] || bad "director user answered $code"
  ok "user director created (password ${#dpw} chars, in $WORK/secrets.env)"
}

check() {
  # shellcheck disable=SC1091
  . "$WORK/secrets.env"
  local disc; disc=$(curl -s "$KC_URL/realms/$REALM/.well-known/openid-configuration")
  want "\"issuer\":\"$KC_URL/realms/$REALM\"" "$disc" "1 issuer is $KC_URL/realms/$REALM (this is the value for Floor42's Settings)"
  want '/protocol/openid-connect/certs' "$disc" "1 JWKS endpoint published"

  local dt dtok; dt=$(curl -s "$OIDC/token" -u "floor42-daemon:$FLOOR42_DAEMON_SECRET" -d grant_type=client_credentials)
  dtok=$(printf '%s' "$dt" | jstr access_token); [ -n "$dtok" ] || bad "2 floor42-daemon client_credentials: $dt"
  ok "2 floor42-daemon client_credentials token (${#dtok} chars)"
  want '"azp":"floor42-daemon"' "$(payload "$dtok")" "2 token azp is floor42-daemon"
  H="Authorization: Bearer $dtok"

  # 3 as Floor42 does: agent client, default scope floor42-agent, audience mapper on the desk
  aid=floor42-agent-byocheck; local asec; asec=$(openssl rand -hex 32)
  curl -s -o /dev/null -X DELETE -H "$H" "$ADMIN/clients/$(curl -s -H "$H" "$ADMIN/clients?clientId=$aid" | jstr id)" || true
  local code; code=$(curl -s -o /dev/null -w '%{http_code}' -H "$H" -H 'Content-Type: application/json' -d '{"clientId":"'$aid'","protocol":"openid-connect","enabled":true,"publicClient":false,"serviceAccountsEnabled":true,"standardFlowEnabled":false,"directAccessGrantsEnabled":false,"secret":"'"$asec"'","defaultClientScopes":["floor42-agent"],"attributes":{"access.token.lifespan":"3600","standard.token.exchange.enabled":"true"}}' "$ADMIN/clients")
  [ "$code" = 201 ] || bad "3 daemon creates an agent client: $code (service account roles?)"
  ok "3 floor42-daemon created $aid (201): manage-clients suffices"
  desk=$(curl -s -H "$H" "$ADMIN/clients?clientId=floor42-desk" | jstr id)
  code=$(curl -s -o /dev/null -w '%{http_code}' -H "$H" -H 'Content-Type: application/json' -d '{"name":"aud-'$aid'","protocol":"openid-connect","protocolMapper":"oidc-audience-mapper","config":{"included.client.audience":"'$aid'","access.token.claim":"true","id.token.claim":"false"}}' "$ADMIN/clients/$desk/protocol-mappers/models")
  [ "$code" = 201 ] || [ "$code" = 409 ] || bad "3 audience mapper on the desk: $code"
  ok "3 audience mapper for $aid on floor42-desk (201)"

  # 4 a director token: the desk has no password grant, so it is opened for this check only and closed again
  trap 'curl -s -o /dev/null -X PUT -H "$H" -H "Content-Type: application/json" -d "{\"directAccessGrantsEnabled\":false}" "$ADMIN/clients/$desk"; curl -s -o /dev/null -X DELETE -H "$H" "$ADMIN/clients/$(curl -s -H "$H" "$ADMIN/clients?clientId=$aid" | jstr id)"' EXIT
  curl -s -o /dev/null -X PUT -H "$H" -H 'Content-Type: application/json' -d '{"directAccessGrantsEnabled":true}' "$ADMIN/clients/$desk"
  local dir; dir=$(curl -s "$OIDC/token" -d grant_type=password -d client_id=floor42-desk -d username=director \
    --data-urlencode password="$DIRECTOR_PASSWORD" -d scope=openid | jstr access_token)
  [ -n "$dir" ] || bad "4 director token"
  ok "4 director access token (${#dir} chars), then the desk is closed again"
  want '"preferred_username":"director"' "$(payload "$dir")" "4 director token names the user"

  # 5 the exchange as the agent client
  local launch; launch=$(cat /proc/sys/kernel/random/uuid)
  local ex; ex=$(curl -s "$OIDC/token" -u "$aid:$asec" -d grant_type=urn:ietf:params:oauth:grant-type:token-exchange \
    --data-urlencode "subject_token=$dir" -d subject_token_type=urn:ietf:params:oauth:token-type:access_token \
    -d requested_token_type=urn:ietf:params:oauth:token-type:access_token -d audience=floor42-api \
    -d floor42_launch="$launch" -d floor42_mission=M-1 -d floor42_ticket=T-1)
  local st; st=$(printf '%s' "$ex" | jstr access_token); [ -n "$st" ] || bad "5 token exchange: $ex"
  local c; c=$(payload "$st")
  ok "5 token exchange gave a session token (${#st} chars)"
  want '"aud":"floor42-api"' "$c" "5 aud is exactly floor42-api"
  want '"azp":"'$aid'"' "$c" "5 azp is the agent client"
  want '"act":{' "$c" "5 act claim present (the extension is loaded)"
  want '"client_id":"'$aid'"' "$c" "5 act.client_id is the agent client"
  want '"floor42":{' "$c" "5 floor42 context claim present"
  want '"launch":"'$launch'"' "$c" "5 floor42.launch is the one we sent"
  want '"mission":"M-1"' "$c" "5 floor42.mission"
  case "$ex" in *refresh_token*) bad "5 an agent exchange must not return a refresh token";; *) ok "5 no refresh token in the exchange";; esac

  # 6 the agent's own client_credentials token has no act: Floor42 refuses it, so it must stay distinguishable
  local own; own=$(curl -s "$OIDC/token" -u "$aid:$asec" -d grant_type=client_credentials | jstr access_token)
  case "$(payload "$own")" in *'"act":'*) bad "6 own token carries act";; *) ok "6 the agent's own token has no act claim";; esac

  # 7 introspection by floor42-api (only caller that sees an aud=floor42-api token as active)
  local i; i=$(curl -s "$OIDC/token/introspect" -u "floor42-api:$FLOOR42_API_SECRET" --data-urlencode "token=$st")
  want '"active":true' "$i" "7 floor42-api introspects the session token: active"
  i=$(curl -s "$OIDC/token/introspect" -u "floor42-api:$FLOOR42_API_SECRET" -d token=floor42-start-probe)
  want '"active":false' "$i" "7 start probe (garbage token) answers 200 active:false with the floor42-api credential"
  code=$(curl -s -o /dev/null -w '%{http_code}' "$OIDC/token/introspect" -u "floor42-api:wrong" -d token=x)
  [ "$code" = 401 ] && ok "7 a wrong floor42-api secret is refused (401)" || bad "7 wrong secret answered $code"

  # 8 disabling the agent client acts at once
  curl -s -o /dev/null -X PUT -H "$H" -H 'Content-Type: application/json' -d '{"enabled":false}' "$ADMIN/clients/$(curl -s -H "$H" "$ADMIN/clients?clientId=$aid" | jstr id)"
  i=$(curl -s "$OIDC/token/introspect" -u "floor42-api:$FLOOR42_API_SECRET" --data-urlencode "token=$st")
  want '"active":false' "$i" "8 agent client disabled: the session token is inactive at once"
  echo "ALL CHECKS PASSED"
}

case "${1:-}" in provision) provision;; check) check;; *) echo "usage: $0 provision|check" >&2; exit 2;; esac
