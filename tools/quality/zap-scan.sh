#!/bin/sh
# OWASP ZAP baseline (passive) scan of a test daemon: sh tools/quality/zap-scan.sh
# Starts the packaged daemon (./mvnw package first) on a free port, with a temp data folder and the fake Claude Code
# (the settings of floor42-web/e2e/daemon.ts), signs in with the link it prints (floor42.sign-in.delivery=stdout) and hands
# the session cookie to ZAP, so the scan reaches the authenticated API as well as the public pages. Needs Java 25 (JAVA
# or JAVA_HOME), curl and Docker. Reports in target/zap/. ZAP_FAIL=1 makes a FAIL-level alert exit 1 (default: report).
# Rules: tools/quality/zap-rules.tsv. To scan a daemon you started yourself: FLOOR42_URL=... FLOOR42_COOKIE=name=value.
set -eu
cd "$(dirname "$0")/../.."
ROOT=$PWD
IMAGE=zaproxy/zap-stable:2.17.0@sha256:9624669db72f54d86c4b46a7064518bd9bf79d8c61a4c31befa9923489f328af
OUT=$ROOT/target/zap
mkdir -p "$OUT"
chmod 777 "$OUT"
cp tools/quality/zap-rules.tsv "$OUT/rules.tsv"
chmod 644 "$OUT/rules.tsv"
PID=
BASE=
cleanup() { [ -z "$PID" ] || kill "$PID" 2>/dev/null || true; [ -z "$BASE" ] || rm -rf "$BASE"; }
trap cleanup EXIT

if [ -z "${FLOOR42_URL:-}" ]; then
  JAVA=${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/java}}
  JAVA=${JAVA:-java}
  JAR=floor42-server/target/quarkus-app/quarkus-run.jar
  [ -f "$JAR" ] || { echo "No $JAR: run ./mvnw -B -ntp -q package -DskipTests first" >&2; exit 2; }
  PORT=$((20000 + $$ % 20000))
  BASE=$(mktemp -d)
  FAKE=$ROOT/floor42-server/src/test/resources/fake-claude
  TTY=$ROOT/floor42-server/src/test/resources/fake-tty
  # A property list takes forward slashes (a backslash escapes in MicroProfile Config).
  "$JAVA" -Dquarkus.http.port=$PORT -Dfloor42.data-dir="$BASE/data" -Dfloor42.legacy-data-dir="$BASE/no-legacy" \
    -Dfloor42.sign-in.delivery=stdout \
    -Dfloor42.claude.command="$JAVA,$FAKE/FakeClaude.java,--fake-script,$FAKE/session.txt,--fake-review-script,$FAKE/review.txt" \
    -Dfloor42.claude.hook-timeout-seconds=60 -Dfloor42.catalogue.claude-home="$BASE/claude-home" \
    -Dfloor42.gh.command=floor42-no-gh -Dfloor42.backup.dir="$BASE/backups" \
    -Dfloor42.plugins.claude-command=floor42-no-claude -Dfloor42.plugins.listing-base=http://127.0.0.1:9 \
    -Dfloor42.terminal.command="$JAVA,$TTY/FakeTty.java,--claude-prompt" -Dfloor42.terminal.claude-home="$BASE/claude-home" \
    -Dfloor42.retro.enabled=false -Dfloor42.sandbox.distro=floor42-no-such-distro -Dfloor42.sandbox.mandatory=false \
    -jar "$JAR" >"$BASE/daemon.log" 2>&1 &
  PID=$!
  URL=http://127.0.0.1:$PORT
  i=0
  until curl -fs "$URL/api/status" >/dev/null 2>&1; do
    i=$((i + 1)); [ $i -lt 120 ] || { echo "The daemon did not start:" >&2; tail -30 "$BASE/daemon.log" >&2; exit 1; }
    kill -0 "$PID" 2>/dev/null || { echo "The daemon exited:" >&2; tail -30 "$BASE/daemon.log" >&2; exit 1; }
    sleep 1
  done
  # The page asks for the link with this call; the daemon prints it and never hands it back (ADR-0034).
  curl -s -o /dev/null -X POST -H 'X-Floor42-Request: 1' "$URL/auth/open"
  sleep 2
  LINK=$(sed -n 's/^Floor42 sign-in link ([^)]*): \(.*\)$/\1/p' "$BASE/daemon.log" | head -1)
  [ -n "$LINK" ] || { echo "The daemon printed no sign-in link" >&2; exit 1; }
  # The cookie jar is Netscape format: name and value are the last two fields (HttpOnly cookies carry a #HttpOnly_ prefix).
  curl -s -o /dev/null -c "$BASE/jar" "$LINK" || true
  COOKIE=$(awk -F'\t' 'NF >= 7 { printf "%s=%s", $6, $7; exit }' "$BASE/jar")
else
  URL=$FLOOR42_URL
  COOKIE=${FLOOR42_COOKIE:-}
fi

ZAP_OPTS=
if [ -n "$COOKIE" ]; then
  echo "Authenticated scan (session cookie from the sign-in link)."
  ZAP_OPTS="-config replacer.full_list(0).description=session -config replacer.full_list(0).enabled=true \
-config replacer.full_list(0).matchtype=REQ_HEADER -config replacer.full_list(0).matchstring=Cookie \
-config replacer.full_list(0).regex=false -config replacer.full_list(0).replacement=$COOKIE"
else
  echo "UNAUTHENTICATED scan: no session cookie was obtained, only the public pages are reached."
fi

# --network host: the daemon refuses any Host that is not a loopback name (ADR on the loopback bind), so ZAP must reach it
# as 127.0.0.1. -a adds the alpha passive rules, -j the AJAX spider is left off (the UI is a single page, the API is the target).
set +e
docker run --rm --network host -v "$OUT:/zap/wrk:rw" "$IMAGE" zap-baseline.py -t "$URL" -c rules.tsv \
  -r zap-report.html -J zap-report.json -w zap-report.md -a -z "$ZAP_OPTS"
CODE=$?
echo "ZAP baseline exit code $CODE (0 clean, 1 FAIL alert, 2 WARN alert, 3 error). Report: target/zap/zap-report.html"

# The API, from the committed OpenAPI document (the UI is a single page, so the baseline's spider never reaches it).
# Safe mode (-S, passive checks only) unless ZAP_API_ACTIVE=1: an active scan sends attack payloads to every route, so it
# belongs on a throwaway daemon (the nightly job, a container), never on one that holds real data. -T caps it in minutes.
cp floor42-web/src/api/openapi.json "$OUT/openapi.json"
MODE=-S
[ "${ZAP_API_ACTIVE:-0}" = 1 ] && MODE=
docker run --rm --network host -v "$OUT:/zap/wrk:rw" "$IMAGE" zap-api-scan.py -t openapi.json -f openapi -O "$URL" -c rules.tsv \
  -r zap-api-report.html -J zap-api-report.json -w zap-api-report.md -T "${ZAP_API_MINUTES:-10}" $MODE -z "$ZAP_OPTS"
API=$?
set -e
echo "ZAP API scan exit code $API. Report: target/zap/zap-api-report.html"
if [ "${ZAP_FAIL:-0}" = 1 ]; then
  for c in $CODE $API; do [ "$c" -ne 0 ] && [ "$c" -ne 2 ] && exit "$c"; done
fi
exit 0
