#!/bin/sh
# Semgrep over the repository: the registry packs below plus the project rules (semgrep.yml). One place for the list,
# used by `make semgrep` and by the workflows. Needs Docker (or SEMGREP=semgrep for a local install) and network access
# (the packs are fetched from the registry). SARIF goes to $OUT (default target/semgrep.sarif).
# A rules file cannot reference registry packs, which is why they are listed here and not in semgrep.yml.
set -eu
cd "$(dirname "$0")/../.."
IMAGE=semgrep/semgrep:1.184.0@sha256:cc1b68266f267878b39739373ea9985b9a78e5d64497dd875c8de100b22695ed
OUT=${OUT:-target/semgrep.sarif}
mkdir -p "$(dirname "$OUT")"
# --error asks for exit 1 on findings (0 was seen with 1.184.0 and --sarif); the workflows only report either way.
set -- --config tools/quality/semgrep.yml \
  --config p/java --config p/owasp-top-ten --config p/jwt --config p/secrets --config p/typescript \
  --exclude node_modules --exclude target --exclude dist --exclude e2e-results --exclude '*.test.ts' \
  --sarif --output "$OUT" --metrics=off --error "$@"
if [ -n "${SEMGREP:-}" ]; then exec $SEMGREP "$@"; fi
exec docker run --rm -v "$PWD:/src" -w /src "$IMAGE" semgrep "$@"
