#!/usr/bin/env bash
# Dev only. The broker's demo resource in realm floor42: a client "demo-resource" (the audience a broker exchange asks
# for) and an optional client scope "demo-resource-audience" whose mapper puts that audience in an exchanged token.
# The scope is optional: an agent's token carries the audience only when the broker asks for it, for a call the
# director delegated. Added to every existing floor42-agent-* client and to the realm's default optional scopes, so
# agent clients made later get it too. Safe to run again. Run it after iam/bootstrap.sh, with the stack up.
# FLOOR42_IAM_DIR: the folder the stack was started from, when not this one (its .env names the compose project).
set -euo pipefail
cd "${FLOOR42_IAM_DIR:-$(dirname "$0")}"

if [ -z "$(docker compose ps -q --status running keycloak 2>/dev/null)" ]; then
  echo "Keycloak is not running: docker compose -f iam/compose.yaml up -d, then run this again."
  exit 1
fi

kc() { docker compose exec -T keycloak "$@"; }
# The admin password comes from the container's own environment, never from this shell.
kc sh -c '/opt/keycloak/bin/kcadm.sh config credentials --config /tmp/kcadm.config \
  --server http://localhost:8080 --realm master --user admin --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"' >/dev/null
ADM() { kc /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config; }
csv() { tr -d '\r'; }

if [ -z "$(ADM get clients -r floor42 -q clientId=demo-resource --fields id --format csv --noquotes | csv)" ]; then
  ADM create clients -r floor42 -s clientId=demo-resource -s protocol=openid-connect -s enabled=true \
    -s publicClient=false -s standardFlowEnabled=false -s directAccessGrantsEnabled=false \
    -s serviceAccountsEnabled=false -s fullScopeAllowed=false >/dev/null
  echo "Client demo-resource created."
else
  echo "Client demo-resource already exists."
fi

SCOPE=$(ADM get client-scopes -r floor42 --fields id,name --format csv --noquotes | csv \
  | awk -F, '$2=="demo-resource-audience"{print $1}')
if [ -z "$SCOPE" ]; then
  ADM create client-scopes -r floor42 -s name=demo-resource-audience -s protocol=openid-connect \
    -s 'attributes."include.in.token.scope"=true' -s 'attributes."display.on.consent.screen"=true' \
    -s 'attributes."consent.screen.text"=Reach the demo resource for you' >/dev/null
  SCOPE=$(ADM get client-scopes -r floor42 --fields id,name --format csv --noquotes | csv \
    | awk -F, '$2=="demo-resource-audience"{print $1}')
  ADM create "client-scopes/$SCOPE/protocol-mappers/models" -r floor42 -s name=demo-resource-audience \
    -s protocol=openid-connect -s protocolMapper=oidc-audience-mapper \
    -s 'config."included.client.audience"=demo-resource' -s 'config."access.token.claim"=true' \
    -s 'config."id.token.claim"=false' -s 'config."introspection.token.claim"=true' >/dev/null
  echo "Client scope demo-resource-audience created, with its audience mapper."
else
  echo "Client scope demo-resource-audience already exists."
fi

ADM update "realms/floor42/default-optional-client-scopes/$SCOPE" -r floor42 -n >/dev/null
for CID in $(ADM get clients -r floor42 --fields id,clientId --format csv --noquotes | csv \
    | awk -F, '$2 ~ /^floor42-agent-/{print $1}'); do
  ADM update "clients/$CID/optional-client-scopes/$SCOPE" -r floor42 -n >/dev/null
done
echo "demo-resource-audience is an optional scope of the floor42-agent-* clients and of new ones."
