#!/bin/bash
# Classify one failing test class: rerun it alone 3 times.
# REAL (fails alone), NEW-FLAKE (passes alone, not listed), KNOWN-FLAKE (passes alone, listed in docs/).
# Usage: tools/gate/triage.sh <TestClass>
C="${1:?usage: triage.sh <TestClass>}"
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 2
for i in 1 2 3; do
  if ! ./mvnw -q -B -pl floor42-server -am test -Dspotless.check.skip=true -Dquarkus.quinoa=false \
      -Dtest="$C" -Dsurefire.failIfNoSpecifiedTests=false >"target/triage-$C-$i.log" 2>&1; then
    echo "REAL $C (failed alone on run $i, target/triage-$C-$i.log)"; exit 1
  fi
done
if grep -qw "$C" docs/V2_IDEAS.md docs/PROGRESS.md docs/progress* 2>/dev/null; then
  echo "KNOWN-FLAKE $C (green alone 3 times, listed in docs/)"
else
  echo "NEW-FLAKE $C (green alone 3 times, not listed: add it to docs/V2_IDEAS.md flaky tests and give it a cause)"
fi
