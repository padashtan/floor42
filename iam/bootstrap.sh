#!/usr/bin/env bash
# Dev only. First run: writes iam/.env with random secrets (kept on later runs).
# Once the stack is up (docker compose -f iam/compose.yaml up -d): creates the realm user "director",
# with a password you type (never echoed, never stored by this script).
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  umask 077
  {
    echo "FLOOR42_DAEMON_SECRET=$(openssl rand -hex 32)"
    echo "FLOOR42_API_SECRET=$(openssl rand -hex 32)"
    echo "KC_BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -hex 32)"
    echo "POSTGRES_PASSWORD=$(openssl rand -hex 32)"
  } > .env
  chmod 600 .env
  echo "iam/.env written (mode 600). Start the stack: docker compose -f iam/compose.yaml up -d, then run this again."
  exit 0
fi
echo "iam/.env kept."

if [ -z "$(docker compose ps -q --status running keycloak 2>/dev/null)" ]; then
  echo "Keycloak is not running: docker compose -f iam/compose.yaml up -d, then run this again to create the director."
  exit 0
fi

kcadm() { docker compose exec "$@"; }
for _ in $(seq 1 60); do
  # The admin password comes from the container's own environment, never from this shell.
  if kcadm -T keycloak sh -c '/opt/keycloak/bin/kcadm.sh config credentials --config /tmp/kcadm.config \
      --server http://localhost:8080 --realm master --user admin --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"' \
      >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

ADM="/opt/keycloak/bin/kcadm.sh"
FOUND=$(kcadm -T keycloak "$ADM" get users -r floor42 -q username=director -q exact=true --fields username \
  --config /tmp/kcadm.config)
if [[ "$FOUND" == *'"director"'* ]]; then
  echo "User director already exists in realm floor42."
  exit 0
fi
kcadm -T keycloak "$ADM" create users -r floor42 --config /tmp/kcadm.config \
  -s username=director -s enabled=true -s emailVerified=true \
  -s firstName=The -s lastName=Director -s email=director@floor42.invalid
echo "Type the director's password (not echoed):"
kcadm keycloak "$ADM" set-password -r floor42 --username director --config /tmp/kcadm.config
echo "User director created in realm floor42."
