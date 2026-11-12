#!/bin/sh
# Trivy (docs/security-scanning.md): sh tools/quality/trivy-run.sh fs|sbom|config|image|all
#   fs      the Maven and npm lock files and the source tree (vulnerabilities and secrets)
#   sbom    target/bom.json, the CycloneDX SBOM of the root build (./mvnw package produces it)
#   config  misconfigurations of the Dockerfile and compose.yaml
#   image   builds the Dockerfile's floor42 target (the published image) as floor42:scan, then scans it
# Environment: TRIVY_EXIT=1 fails on a finding of TRIVY_SEVERITY (default HIGH,CRITICAL) not listed in .trivyignore
# (default 0: the reports are written, the exit code stays 0). Reports in target/trivy/. Needs Docker and network access
# (the vulnerability database is fetched, then cached in the volume floor42-trivy-cache).
set -eu
cd "$(dirname "$0")/../.."
IMAGE=aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb
SEVERITY=${TRIVY_SEVERITY:-HIGH,CRITICAL}
EXIT=${TRIVY_EXIT:-0}
mkdir -p target/trivy
chmod 777 target/trivy

trivy() {
  docker run --rm -v "$PWD:/src" -w /src -v floor42-trivy-cache:/root/.cache -v /var/run/docker.sock:/var/run/docker.sock \
    ${M2:-} "$IMAGE" "$@"
}

# Reports every severity into SARIF, then gates on SEVERITY (the table is what a person reads).
scan() {
  name=$1; shift
  trivy "$@" --ignorefile .trivyignore --format sarif --output "target/trivy/$name.sarif" || true
  trivy "$@" --ignorefile .trivyignore --severity "$SEVERITY" --exit-code "$EXIT" --format table | tee "target/trivy/$name.txt"
}

what=${1:-all}
case $what in fs | sbom | config | image | all) ;; *) echo "usage: $0 fs|sbom|config|image|all" >&2; exit 2 ;; esac
status=0
run() { "$@" || status=1; }

# A pom.xml is resolved against Maven Central (and rate limited: HTTP 429 blocks the IP for 30 minutes) unless the local
# repository is mounted. Without ~/.m2 the poms are skipped here: the sbom scan covers the resolved Maven dependencies.
if [ -d "$HOME/.m2/repository" ]; then
  M2="-v $HOME/.m2:/root/.m2:ro"; SKIP=
else
  M2=; SKIP="--skip-files pom.xml,floor42-domain/pom.xml,floor42-server/pom.xml,floor42-coverage/pom.xml"
  echo "No ~/.m2/repository: the pom.xml files are skipped by the fs scan (covered by the sbom scan)."
fi
[ "$what" = fs ] || [ "$what" = all ] && run scan fs fs --scanners vuln,secret --skip-dirs node_modules,target,dist $SKIP .
[ "$what" = sbom ] || [ "$what" = all ] && {
  [ -f target/bom.json ] || { echo "target/bom.json is missing: run ./mvnw -B -ntp -q package -DskipTests first" >&2; exit 2; }
  run scan sbom sbom target/bom.json
}
[ "$what" = config ] || [ "$what" = all ] && run scan config config --skip-dirs node_modules,target,dist .
if [ "$what" = image ] || [ "$what" = all ]; then
  docker build --target floor42 -t floor42:scan .
  run scan image image floor42:scan
fi
exit $status
