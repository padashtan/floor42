#!/bin/bash
# Public CI gate (docs/adr/0069, docs/releases.md): ONE snapshot commit of the private tree at <sha>, on top of the
# public main, pushed to the public branch release/v<version>. The public CI then runs Windows and Linux on a tree
# without the private history. Never touches the public main.
# Usage: tools/gate/remote.sh <version> [sha]
# Env: PUBLIC_REPO (padashtan/floor42), DENY (~/.private/deny.txt, never printed), PUSH=1 to push (default: print the
# command), WAIT=1 to wait for the public runs. Last line with WAIT=1: GATE GREEN|RED public <sha> <minutes>.
set -euo pipefail
VERSION=${1:?usage: tools/gate/remote.sh <version> [sha]}
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SHA="$(git -C "$ROOT" rev-parse "${2:-HEAD}^{commit}")"
PUBLIC=${PUBLIC_REPO:-padashtan/floor42}; URL="https://github.com/$PUBLIC.git"
DENY=${DENY:-$HOME/.private/deny.txt}
NAME="Christophe Madrennes"; MAIL="191577577+padashtan@users.noreply.github.com"
BRANCH="release/v$VERSION"; W="$ROOT/target/remote-gate"; T0=$(date +%s)

[ -s "$DENY" ] || { echo "no deny-list at $DENY: a sweep without it is a failed sweep (skill pii-sweep)"; exit 2; }

# A scratch clone, so private refs and tags never sit next to the public ones.
rm -rf "$W"; git clone -q --no-local --no-tags "$ROOT" "$W" && cd "$W" && git checkout -q --detach "$SHA"

# Sweep, passes 1 to 3 of pii-sweep: file names and counts only, never the matched text. The list is normalised once
# (a Notepad file ends each line with a carriage return, which would never match) into a temporary file outside the repository.
LIST="$(mktemp)"; trap 'rm -f "$LIST"' EXIT
tr -d "\r" < "$DENY" | { grep -v '^[[:space:]]*$' || true; } > "$LIST"
[ -s "$LIST" ] || { echo "the deny-list holds no term"; exit 2; }
# A short term (under 5 characters, such as a nickname) sits inside many ordinary words: it counts as a whole word only.
SHORT="$LIST.short"; LONG="$LIST.long"; trap 'rm -f "$LIST" "$SHORT" "$LONG"' EXIT
awk 'length($0) < 5' "$LIST" > "$SHORT"; awk 'length($0) >= 5' "$LIST" > "$LONG"
# git grep exits 1 for "no match" and 2 or more on an error: an error must never read as clean.
found() { local out rc=0; out=$(git grep -c -i -I -F "$@" -- .) || rc=$?; [ $rc -le 1 ] || { echo "git grep failed ($rc)" >&2; exit 2; }; printf '%s' "$out"; }
BAD=0
hits=""; [ -s "$LONG" ] && hits=$(found -f "$LONG")
[ -s "$SHORT" ] && hits="$hits${hits:+$'
'}$(found -w -f "$SHORT")"
hits=$(printf '%s' "$hits" | sed '/^$/d')
[ -n "$hits" ] && { echo "deny-list hits (file:count):"; echo "$hits" | sed 's/^/  /'; BAD=1; }
# Sample paths (/Users/alice/, /home/dev/) are fixtures; the real risk is this machine's own user name.
me="$(basename "$HOME")"
paths=$(found -e "Users/$me/" -e "Users\\$me" -e "home/$me/")
[ -n "$paths" ] && { echo "paths with this machine's user name (file:count):"; echo "$paths" | sed 's/^/  /'; BAD=1; }
# One term at a time, with a git pathspec: Git for Windows' grep aborts when fed `git ls-files` through a pipe, and an
# aborted grep would read as "no match".
names=0
while IFS= read -r t; do
  [ -n "$(git ls-files -- ":(icase)*$t*")" ] && names=$((names + 1))
done < "$LONG"
ALL="$(git ls-files)"
while IFS= read -r t; do
  re="(^|[^[:alnum:]])${t,,}([^[:alnum:]]|$)"
  while IFS= read -r f; do [[ "${f,,}" =~ $re ]] && { names=$((names + 1)); break; }; done <<<"$ALL"
done < "$SHORT"
[ "$names" != 0 ] && { echo "deny-list terms in $names file names"; BAD=1; }
[ $BAD = 1 ] && { echo "sweep: TO FIX, nothing built"; exit 1; }
echo "sweep: CLEAN ($(git ls-files | wc -l) files)"

# One commit: the private tree, its parent the public main.
git fetch -q "$URL" main
COMMIT=$(GIT_AUTHOR_NAME="$NAME" GIT_AUTHOR_EMAIL="$MAIL" GIT_COMMITTER_NAME="$NAME" GIT_COMMITTER_EMAIL="$MAIL" \
  git commit-tree "$SHA^{tree}" -p FETCH_HEAD -m "Floor42 $VERSION")
[ "$(git log -1 --format='%ae %ce' "$COMMIT")" = "$MAIL $MAIL" ] || { echo "identity check failed"; exit 2; }
echo "snapshot ${COMMIT:0:8} of private ${SHA:0:8} on public main $(git rev-parse --short FETCH_HEAD)"

# The branch already exists after the first snapshot of the week: replacing it needs a lease on the sha seen now (a
# push to a URL has no remote-tracking ref to lease on), and that push is the director's to run.
OLD=$(git ls-remote "$URL" "refs/heads/$BRANCH" | cut -f1)
LEASE=""; [ -n "$OLD" ] && LEASE="--force-with-lease=refs/heads/$BRANCH:$OLD"
CMD="git -C \"$W\" push $LEASE \"$URL\" $COMMIT:refs/heads/$BRANCH"
if [ "${PUSH:-0}" = 1 ]; then eval "$CMD"; else echo "push with: $CMD"; exit 0; fi
# ci.yml runs on the pull request; the browser tests only run when dispatched.
gh pr view -R "$PUBLIC" "$BRANCH" --json number -q .number >/dev/null 2>&1   || echo "no PR yet, open it first: gh pr create -R $PUBLIC --base main --head $BRANCH --title \"Floor42 $VERSION\" --body-file <release notes>"
gh workflow run e2e.yml -R "$PUBLIC" --ref "$BRANCH"

[ "${WAIT:-0}" = 1 ] || exit 0
# Wait until the runs on this commit have appeared and all completed, 90 minutes at most.
sleep 30
until runs=$(gh run list -R "$PUBLIC" --commit "$COMMIT" --json name,status,conclusion     -q '.[] | "\(.status) \(.conclusion) \(.name)"') && [ -n "$runs" ] && ! grep -qv '^completed' <<<"$runs"; do
  [ $(( $(date +%s) - T0 )) -lt 5400 ] || { echo "${runs:-no run}"; echo "GATE RED public ${COMMIT:0:8} timeout"; exit 1; }
  sleep 60
done
echo "$runs" | sed 's/^completed /  /'
RED=0; grep -qvE '^completed (success|skipped|neutral) ' <<<"$runs" && RED=1
echo "GATE $([ $RED = 1 ] && echo RED || echo GREEN) public ${COMMIT:0:8} $(( ($(date +%s)-T0)/60 ))"
exit $RED
