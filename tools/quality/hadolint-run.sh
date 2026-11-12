#!/bin/sh
# Dockerfile lint with the official hadolint image (CI and `make hadolint`). Fails on a warning or worse.
set -eu
cd "$(dirname "$0")/../.."
IMAGE=hadolint/hadolint:v2.15.1@sha256:32dac94127fd60b7b7e3fbfc65e1383b9b5e25c9bfd7b8536de7a539fe68a12d
for f in Dockerfile iam/keycloak/Dockerfile; do
  echo "== $f"
  docker run --rm -i "$IMAGE" hadolint --failure-threshold warning - < "$f"
done
