#!/bin/sh
# Static scanners without a language server: OSV-Scanner (dependencies), gitleaks (secrets, full history and working
# tree) and Bearer (data flows, privacy). One place for the pins, used by `make security-static` and the workflow.
# Usage: static-run.sh osv|gitleaks|bearer|all. Needs Docker. Reports go to target/static/. Exit code: 1 when a tool
# finds something that no exception covers (osv-scanner.toml, .gitleaks.toml, bearer.ignore).
# A git worktree has a .git file pointing outside the mount: gitleaks then needs a real clone (GITLEAKS_REPO=path).
set -eu
cd "$(dirname "$0")/../.."
OSV=ghcr.io/google/osv-scanner:v2.3.5@sha256:4d3d1a42ba435ac706286fec518c044f049c9753d21260b5bae60bab8740ed97
GITLEAKS=zricethezav/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f
BEARER=bearer/bearer:v1.51.0@sha256:bfbe54fdffcbb3997600f930409215dea11b29424878497b6018180239289b6c
OUT=target/static
mkdir -p "$OUT"
# The containers run as the caller: the reports land in the folder it owns (Bearer's image runs as its own user and
# could not write there), and the repository is read by its owner. HOME is a temp folder for the tools' caches.
# The mounted checkout is trusted whoever owns it (a CI job running as root, Docker Desktop's uid mapping): without
# safe.directory, git inside gitleaks and Bearer refuses it as "dubious ownership" and the scan fails.
AS="--user $(id -u):$(id -g) -e HOME=/tmp -e GIT_CONFIG_COUNT=2 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0=/src -e GIT_CONFIG_KEY_1=safe.directory -e GIT_CONFIG_VALUE_1=/repo"
rc=0

osv() {
  # Maven: the resolved dependency tree of the CycloneDX SBOM (the pom.xml files alone miss the managed Quarkus
  # versions); npm: the lock file. osv-scanner.toml is read from the working directory.
  if [ ! -f target/bom.json ]; then
    ./mvnw -q -B -ntp -Dspotless.check.skip=true org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom       -DoutputFormat=json -DoutputName=bom -DincludeTestScope=false -DprojectType=application -DschemaVersion=1.6
  fi
  docker run --rm $AS -v "$PWD:/src" -w /src "$OSV" scan source     -L target/bom.json --lockfile floor42-web/package-lock.json --lockfile iam/keycloak-extensions/pom.xml     --config osv-scanner.toml --format json --output-file "/src/$OUT/osv.json" || rc=1
}

gitleaks() {
  # Secrets are redacted in every output. `git` scans every commit of every ref, `dir` the working tree.
  repo=${GITLEAKS_REPO:-$PWD}
  docker run --rm $AS -v "$repo:/repo" -v "$PWD:/src" -w /repo "$GITLEAKS" git /repo \
    --config /src/.gitleaks.toml --redact=100 --no-banner -r "/src/$OUT/gitleaks-git.json" || rc=1
  docker run --rm $AS -v "$PWD:/src" -w /src "$GITLEAKS" dir /src \
    --config /src/.gitleaks.toml --redact=100 --no-banner -r "/src/$OUT/gitleaks-dir.json" || rc=1
}

bearer() {
  # Two reports, two passes: security (code weaknesses) and privacy (personal data reaching a log, a URL or a third
  # party). bearer.yml holds the skipped paths; bearer.ignore the triaged fingerprints, each with its reason.
  for report in security privacy; do
    docker run --rm $AS -v "$PWD:/src" -w /src "$BEARER" scan . --config-file bearer.yml --report "$report"       --format json --output "$OUT/bearer-$report.json" --quiet --disable-version-check || rc=1
  done
}

case "${1:-all}" in
  osv) osv ;;
  gitleaks) gitleaks ;;
  bearer) bearer ;;
  all) osv; gitleaks; bearer ;;
  *) echo "usage: $0 osv|gitleaks|bearer|all" >&2; exit 2 ;;
esac
exit $rc
