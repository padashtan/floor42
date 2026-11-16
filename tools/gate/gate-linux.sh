#!/bin/bash
# Linux half of the double gate (docs/releases.md): a fresh clone of this repository at <sha> (default HEAD) in the
# home directory, then one heavy job at a time. Meant for WSL or Linux. Logs in ~/floor42-gate/target/gate/.
# Last line: GATE GREEN|RED <host> <sha> <minutes>. Usage: tools/gate/gate-linux.sh [sha]
# Env: FORKS (2; 1 reuses Quarkus across classes and leaks state, do not), HEAP (2g), CHROMIUM (/usr/bin/chromium),
# GPU=true for headed Chromium on the Windows GPU (WSLg opens a window and takes the focus: off by default).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# A git worktree made on Windows points at its repository with a Windows path ("gitdir: C:/..."), which git in WSL
# cannot follow: clone from the repository's own .git (which holds every worktree's commits) instead.
if [ -f "$ROOT/.git" ] && command -v wslpath >/dev/null; then
  gitdir="$(sed -n 's/^gitdir: //p' "$ROOT/.git" | tr -d '\r')"
  case "$gitdir" in [A-Za-z]:*)
    # The worktree's own HEAD (a branch or a commit), not the main checkout's.
    head="$(tr -d '\r' < "$(wslpath -u "$gitdir")/HEAD")"
    DEFAULT="${head#ref: }"
    ROOT="$(wslpath -u "${gitdir%/worktrees/*}")" ;;
  esac
fi
SHA="$(git -C "$ROOT" rev-parse "${1:-${DEFAULT:-HEAD}}")" || exit 2
W=~/floor42-gate; T0=$(date +%s)
rm -rf "$W"
git clone -q "$ROOT" "$W" && cd "$W" && git checkout -q "$SHA" || exit 2
L="$W/target/gate"; mkdir -p "$L"
TUNE="-Dfloor42.test.forks=${FORKS:-2} -Dfloor42.test.heap=${HEAP:-2g}"
# Test failures are ignored at Maven level only so every step runs; the verdict below reads the summaries.
KEEP=-Dmaven.test.failure.ignore=true

step() { local n=$1 s=$(date +%s); shift; "$@" >"$L/$n.log" 2>&1; echo "$n exit $? in $(( $(date +%s)-s )) s"; }
failed() { grep -E "<<< (FAIL|ERR)" "$L/$1.log" | grep " -- in " | sed "s/.* -- in //"; }

echo "linux at $(git log --oneline -1)"
step npm bash -c 'cd floor42-web && npm ci --silent'
step verify ./mvnw -q -B verify $KEEP $TUNE
step slow ./mvnw -q -B -pl floor42-server -am test -Pslow -Dspotless.check.skip=true $KEEP $TUNE
step package ./mvnw -q -B package -DskipTests
step e2e bash -c "cd floor42-web && FLOOR42_E2E_GPU=${GPU:-false} FLOOR42_E2E_EXECUTABLE=${CHROMIUM:-/usr/bin/chromium} FLOOR42_JAVA=/usr/bin/java npx playwright test"

BAD=$( { failed verify; failed slow; } | sort -u)
[ -n "$BAD" ] && { echo "failing classes:"; echo "$BAD" | sed 's/^/  /'; }
tail -5 "$L/e2e.log"
RED=0
grep -qE "Tests run:.*(Failures|Errors): [1-9]" "$L/verify.log" "$L/slow.log" && RED=1
grep -qE "^ +[0-9]+ failed" "$L/e2e.log" && RED=1
grep -qE "[0-9]+ passed" "$L/e2e.log" || RED=1
echo "GATE $([ $RED = 1 ] && echo RED || echo GREEN) $(hostname) ${SHA:0:8} $(( ($(date +%s)-T0)/60 ))"
exit $RED
