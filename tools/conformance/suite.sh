#!/bin/sh
# The conformance suite for Floor42: sh tools/conformance/suite.sh up|down. Prebuilt images pinned in compose.yaml (a pull,
# no build), on 127.0.0.1:8443. The TLS certificate is made here (SAN localhost.emobix.co.uk, which resolves to 127.0.0.1),
# so the daemon's JVM can verify the host name against a trust store that holds only this certificate.
set -eu
cd "$(dirname "$0")/../.."
TLS=$PWD/target/oidc-conformance/tls
export FLOOR42_TLS_DIR=$TLS
case "${1:-up}" in
  up)
    if [ ! -f "$TLS/suite.crt" ]; then
      mkdir -p "$TLS"
      openssl req -x509 -nodes -newkey rsa:2048 -days 30 -subj "/CN=localhost.emobix.co.uk" \
        -addext "subjectAltName=DNS:localhost.emobix.co.uk,DNS:localhost" \
        -keyout "$TLS/suite.key" -out "$TLS/suite.crt" 2>/dev/null
      chmod 644 "$TLS/suite.key"
    fi
    docker compose -f tools/conformance/compose.yaml up -d
    i=0
    until curl -ks --resolve localhost.emobix.co.uk:8443:127.0.0.1 https://localhost.emobix.co.uk:8443/api/runner/available >/dev/null 2>&1; do
      i=$((i + 1)); [ $i -lt 90 ] || { echo "The suite did not start" >&2; exit 1; }
      sleep 2
    done
    echo "Suite up on https://localhost.emobix.co.uk:8443" ;;
  down) docker compose -f tools/conformance/compose.yaml down -v ;;
esac
