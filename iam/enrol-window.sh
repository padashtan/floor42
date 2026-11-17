#!/usr/bin/env bash
# Dev only. Opens or closes the window in which a user may set up an authenticator app (the Configure OTP required
# action of realm floor42). It is closed by the realm file: the strong step-up (acr floor42-strong) must never enrol
# a factor with just the password, so a user without one is refused there (ADR-0081).
#   iam/enrol-window.sh on     open it, then each person signs in at /realms/floor42/account and sets up the app
#   iam/enrol-window.sh off    close it again (do this as soon as the reviewers and the director are enrolled)
set -euo pipefail
cd "$(dirname "$0")"

case "${1:-}" in
  on) enabled=true ;;
  off) enabled=false ;;
  *) echo "usage: iam/enrol-window.sh on|off" >&2; exit 2 ;;
esac

if [ -z "$(docker compose ps -q --status running keycloak 2>/dev/null)" ]; then
  echo "Keycloak is not running: docker compose -f iam/compose.yaml up -d, then run this again." >&2
  exit 1
fi

ADM="/opt/keycloak/bin/kcadm.sh"
# The admin password comes from the container's own environment, never from this shell.
docker compose exec -T keycloak sh -c '/opt/keycloak/bin/kcadm.sh config credentials --config /tmp/kcadm.config \
  --server http://localhost:8080 --realm master --user admin --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"' >/dev/null
docker compose exec -T keycloak "$ADM" update authentication/required-actions/CONFIGURE_TOTP -r floor42 \
  --config /tmp/kcadm.config -s enabled="$enabled"
echo "Configure OTP is now enabled=$enabled in realm floor42."
