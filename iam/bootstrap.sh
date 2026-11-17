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
    echo "OPENFGA_DB_PASSWORD=$(openssl rand -hex 32)"
    echo "FLOOR42_FGA_KEY=$(openssl rand -hex 32)"
  } > .env
  chmod 600 .env
  echo "iam/.env written (mode 600). Start the stack: docker compose -f iam/compose.yaml up -d, then run this again."
  exit 0
fi
echo "iam/.env kept."
# An .env of an older version gains the OpenFGA secrets (hex, like the client secrets).
for v in OPENFGA_DB_PASSWORD FLOOR42_FGA_KEY; do
  grep -q "^$v=" .env || echo "$v=$(openssl rand -hex 32)" >> .env
done

# The postgres image makes only POSTGRES_DB on a fresh volume, so the openfga role and database are made here, and
# again on any run (\gexec runs each statement only when the role or database is missing).
if [ -n "$(docker compose ps -q --status running postgres 2>/dev/null)" ]; then
  # shellcheck disable=SC1091
  . ./.env
  docker compose exec -T postgres psql -U keycloak -d keycloak -v ON_ERROR_STOP=1 -v pw="$OPENFGA_DB_PASSWORD" >/dev/null <<'SQL'
SELECT format('CREATE ROLE openfga LOGIN PASSWORD %L', :'pw') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'openfga') \gexec
SELECT 'CREATE DATABASE openfga OWNER openfga' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'openfga') \gexec
SQL
  echo "openfga role and database ready: docker compose -f iam/compose.yaml up -d starts OpenFGA."
  echo "Enter FLOOR42_FGA_KEY (iam/.env) in Floor42's vault or set floor42.iam.fga.key; OpenFGA listens on http://127.0.0.1:8181."
fi

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
# An existing realm was not re-imported (compose skips it), so it may lack the desk role (ADR-0081): create it.
if ! kcadm -T keycloak "$ADM" get roles/floor42-director -r floor42 --config /tmp/kcadm.config >/dev/null 2>&1; then
  kcadm -T keycloak "$ADM" create roles -r floor42 -s name=floor42-director --config /tmp/kcadm.config
fi
FOUND=$(kcadm -T keycloak "$ADM" get users -r floor42 -q username=director -q exact=true --fields username \
  --config /tmp/kcadm.config)
if [[ "$FOUND" == *'"director"'* ]]; then
  echo "User director already exists in realm floor42."
  # Older installs: the desk needs the role (ADR-0081); adding it twice is harmless, a failure stops the script.
  kcadm -T keycloak "$ADM" add-roles -r floor42 --uusername director --rolename floor42-director --config /tmp/kcadm.config
  exit 0
fi
kcadm -T keycloak "$ADM" create users -r floor42 --config /tmp/kcadm.config \
  -s username=director -s enabled=true -s emailVerified=true \
  -s firstName=The -s lastName=Director -s email=director@floor42.invalid
echo "Type the director's password (not echoed):"
kcadm keycloak "$ADM" set-password -r floor42 --username director --config /tmp/kcadm.config
kcadm -T keycloak "$ADM" add-roles -r floor42 --uusername director --rolename floor42-director   --config /tmp/kcadm.config
echo "User director created in realm floor42."
