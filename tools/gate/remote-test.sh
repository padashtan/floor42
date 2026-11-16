#!/bin/bash
# Self-check of the sweep in remote.sh, on a throwaway repository: no network, nothing pushed.
# A deny-list written on Windows (CRLF) must still find a term in a file's content and in a file name.
# Usage: tools/gate/remote-test.sh. Exit 0 when every case holds.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
git init -q "$T/repo" && mkdir -p "$T/repo/tools/gate" && cp "$HERE/remote.sh" "$T/repo/tools/gate/"
printf 'hello\n' > "$T/repo/readme.txt"
git -C "$T/repo" add -A && git -C "$T/repo" -c user.name=t -c user.email=t@example.org commit -q -m init
FAIL=0
check() { # <name> <expected exit> <expected text> <deny-list content>
  printf '%b' "$4" > "$T/deny.txt"
  out=$(DENY="$T/deny.txt" bash "$T/repo/tools/gate/remote.sh" 0.0.0 2>&1); rc=$?
  if [ "$rc" = "$2" ] && grep -q "$3" <<<"$out"; then echo "ok   $1"; else echo "FAIL $1 (exit $rc)"; echo "$out" | sed 's/^/     /'; FAIL=1; fi
}
printf 'contact zorglub-corp here\n' > "$T/repo/notes.txt"; : > "$T/repo/zorglub-plan.md"
git -C "$T/repo" add -A && git -C "$T/repo" -c user.name=t -c user.email=t@example.org commit -q -m term
check "CRLF list finds a term in content" 1 "notes.txt:1" 'nothing\r\nZORGLUB-CORP\r\n'
check "CRLF list finds a term in a file name" 1 "terms in 1 file names" 'zorglub-plan\r\n'
check "a short term inside a word is not a hit, as a word it is" 1 "notes.txt:1" 'orp
here
'
check "a short term in a file name counts as a whole word" 1 "terms in 1 file names" 'plan
'
check "a list of blank lines is refused, not read as clean" 2 "holds no term" '\r\n  \r\n'
exit $FAIL
