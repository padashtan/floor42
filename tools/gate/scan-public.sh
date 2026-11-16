#!/bin/bash
# The public CI's static scans (gitleaks on history and tree, Bearer, OSV), run locally BEFORE remote.sh pushes, on a
# copy shaped like the public repository: the public main plus ONE snapshot commit of the private tree. Needs Docker
# (WSL Debian on Windows). Usage: tools/gate/scan-public.sh [ref] (default HEAD). Last line: SCAN CLEAN|RED <sha>.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ -f "$ROOT/.git" ] && command -v wslpath >/dev/null; then
  # A worktree made on Windows: read its HEAD and use the repository's own .git (as gate-linux.sh does).
  gitdir="$(sed -n 's/^gitdir: //p' "$ROOT/.git" | tr -d '\r')"
  case "$gitdir" in [A-Za-z]:*)
    head="$(tr -d '\r' < "$(wslpath -u "$gitdir")/HEAD")"; DEFAULT="${head#ref: }"
    ROOT="$(wslpath -u "${gitdir%/worktrees/*}")" ;;
  esac
fi
REF="${1:-${DEFAULT:-HEAD}}"
S=~/floor42-scan; rm -rf "$S"
git clone -q --single-branch --branch main "https://github.com/${PUBLIC_REPO:-padashtan/floor42}.git" "$S" && cd "$S"
git fetch -q "$ROOT" "$REF"
C=$(GIT_AUTHOR_NAME=scan GIT_AUTHOR_EMAIL=scan@example.org GIT_COMMITTER_NAME=scan GIT_COMMITTER_EMAIL=scan@example.org \
  git commit-tree "$(git rev-parse 'FETCH_HEAD^{tree}')" -p HEAD -m snapshot)
git reset -q --hard "$C"
# Only the public main and the snapshot may be reachable: the fetched private ref must not be scanned as history.
git update-ref -d FETCH_HEAD 2>/dev/null || true; git reflog expire --expire=now --all; git gc -q --prune=now
echo "scan of $REF: $(git rev-list --count HEAD) commits"
rc=0; sh tools/quality/static-run.sh all || rc=1
echo "SCAN $([ $rc = 0 ] && echo CLEAN || echo RED) ${C:0:8} (reports in $S/target/static)"
exit $rc
