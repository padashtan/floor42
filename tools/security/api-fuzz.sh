#!/bin/sh
# Property-based API contract test of a test daemon with Schemathesis: sh tools/security/api-fuzz.sh
# Starts the packaged daemon itself (./mvnw package first) like tools/quality/zap-scan.sh: a free port (never the installed
# daemon's 7420 or the dev daemon's 7421), a temp folder that holds its data AND its home (HOME and -Duser.home), the fake
# Claude Code, sign-in through the printed link. It tests that daemon only: there is no way to point it at another URL.
# Then it runs the official Schemathesis image twice against the committed OpenAPI document: (1) signed in, every built-in
# check; (2) without a session, where every operation but /api/status must answer 401 or 403 (tools/security/
# schemathesis_noauth.py). Reports in target/api-fuzz/. Needs Java 25, curl and Docker.
# Accepted failures live in tools/security/api-fuzz-baseline.json (see docs/security-scanning.md). Exit 1 on a new failure.
# Knobs: API_FUZZ_EXAMPLES (cases per operation and phase, default 25), API_FUZZ_SEED (default 42), API_FUZZ_ARGS (extra).
set -eu
cd "$(dirname "$0")/../.."
ROOT=$PWD
IMAGE=schemathesis/schemathesis:4.33.1@sha256:9def217e2baace9f8e69070f94b7d8a81641e9b66bfde836faa5c033e777d080
OUT=$ROOT/target/api-fuzz
mkdir -p "$OUT"
find "$OUT" -mindepth 1 -delete
chmod 777 "$OUT"
cp floor42-web/src/api/openapi.json "$OUT/openapi.json"
cp tools/security/schemathesis_noauth.py "$OUT/schemathesis_noauth.py"
[ ! -f tools/security/api-fuzz-baseline.json ] || cp tools/security/api-fuzz-baseline.json "$OUT/baseline.json"
PID=
BASE=
cleanup() { [ -z "$PID" ] || kill "$PID" 2>/dev/null || true; [ -z "$BASE" ] || rm -rf "$BASE"; }
trap cleanup EXIT

JAVA=${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/java}}
JAVA=${JAVA:-java}
JAR=floor42-server/target/quarkus-app/quarkus-run.jar
[ -f "$JAR" ] || { echo "No $JAR: run ./mvnw -B -ntp -q package -DskipTests first" >&2; exit 2; }
# A port nobody listens on, checked, in a range that holds neither the installed daemon (7420) nor the dev one (7421).
PORT=
for try in 1 2 3 4 5 6 7 8 9 10; do
  p=$((20000 + ($$ * try + try * 977) % 20000))
  if ! curl -s -o /dev/null -m 2 "http://127.0.0.1:$p/"; then PORT=$p; break; fi
done
[ -n "$PORT" ] || { echo "No free port found" >&2; exit 1; }
BASE=$(mktemp -d)
mkdir -p "$BASE/home"
FAKE=$ROOT/floor42-server/src/test/resources/fake-claude
TTY=$ROOT/floor42-server/src/test/resources/fake-tty
# Everything the daemon reads or writes is under $BASE: its data, its backups, the Claude home, and the home directory
# itself (HOME for git and the children, user.home for the JVM), which is also its working directory.
(
  cd "$BASE/home"
  HOME="$BASE/home" exec "$JAVA" -Duser.home="$BASE/home" -Dquarkus.http.port=$PORT \
    -Dfloor42.data-dir="$BASE/data" -Dfloor42.legacy-data-dir="$BASE/no-legacy" \
    -Dfloor42.sign-in.delivery=stdout \
    -Dfloor42.claude.command="$JAVA,$FAKE/FakeClaude.java,--fake-script,$FAKE/session.txt,--fake-review-script,$FAKE/review.txt" \
    -Dfloor42.claude.hook-timeout-seconds=60 -Dfloor42.catalogue.claude-home="$BASE/claude-home" \
    -Dfloor42.gh.command=floor42-no-gh -Dfloor42.backup.dir="$BASE/backups" \
    -Dfloor42.plugins.claude-command=floor42-no-claude -Dfloor42.plugins.listing-base=http://127.0.0.1:9 \
    -Dfloor42.terminal.command="$JAVA,$TTY/FakeTty.java,--claude-prompt" -Dfloor42.terminal.claude-home="$BASE/claude-home" \
    -Dfloor42.retro.enabled=false -Dfloor42.sandbox.distro=floor42-no-such-distro -Dfloor42.sandbox.mandatory=false \
    -jar "$ROOT/$JAR"
) >"$BASE/daemon.log" 2>&1 &
PID=$!
URL=http://127.0.0.1:$PORT
i=0
until curl -fs "$URL/api/status" >/dev/null 2>&1; do
  i=$((i + 1)); [ $i -lt 120 ] || { echo "The daemon did not start:" >&2; tail -30 "$BASE/daemon.log" >&2; exit 1; }
  kill -0 "$PID" 2>/dev/null || { echo "The daemon exited:" >&2; tail -30 "$BASE/daemon.log" >&2; exit 1; }
  sleep 1
done
curl -s -o /dev/null -X POST -H 'X-Floor42-Request: 1' "$URL/auth/open"
sleep 2
LINK=$(sed -n 's/^Floor42 sign-in link ([^)]*): \(.*\)$/\1/p' "$BASE/daemon.log" | head -1)
[ -n "$LINK" ] || { echo "The daemon printed no sign-in link" >&2; exit 1; }
curl -s -o /dev/null -c "$BASE/jar" "$LINK" || true
FLOOR42_COOKIE=$(awk -F'	' 'NF >= 7 && $6 == "floor42_session" { printf "%s=%s", $6, $7; exit }' "$BASE/jar")
[ -n "$FLOOR42_COOKIE" ] || { echo "No session cookie: the signed-in run would test nothing" >&2; exit 1; }
export FLOOR42_COOKIE
# The cookie reaches the image through its environment and this config (Schemathesis expands ${...}), never the command
# line. --generation-with-security-parameters false: otherwise Schemathesis generates its own cookie from the OpenAPI
# security scheme and the one from the config loses. The other headers are here too: -H on the command line would
# replace the whole headers table of the config.
printf 'headers = { Cookie = "${FLOOR42_COOKIE}", X-Floor42-Request = "1", Origin = "%s" }\n' "$URL" >"$OUT/schemathesis.toml"

N=${API_FUZZ_EXAMPLES:-25}
SEED=${API_FUZZ_SEED:-42}
BL=
[ ! -f "$OUT/baseline.json" ] || BL="--baseline /w/baseline.json"
# Left out of the signed-in run (they stay in the run without a session):
# - sign-out and the revoking of a session (DELETE /api/auth/sessions/{id}, whose id the run can read) end the session the
#   rest of the run needs;
# - /hooks/* and /mcp are the agent sessions' own door (their bearer token; a director's cookie is rightly refused with 401);
# - the routes that write at a path the client names (project folders, git init, terminals, open-folder): the daemon is
#   confined to $BASE by its home and data folders, but a fuzzer must not be the one to test that on a real disk.
SKIP='^(/api/auth/(sign-out|sessions/)|/hooks/|/mcp$|/api/projects/(folders|init)|/api/terminals|.*/open-folder$)'

# Three checks left out, accepted deviations (docs/security-scanning.md): positive_data_acceptance (the routes validate more
# than the schema says and answer a documented 400), negative_data_rejection (the daemon reads leniently: a wrong scalar
# type is coerced, a body is optional where the route has defaults) and allow_header_conformance (the framework answers
# OPTIONS with one Allow list for every path). They are not scoped per route because the stateful phase reaches different
# routes from one run to the next, so a per-route list or baseline of them would flap. unsupported_method stays on: a 2xx
# on a method the route does not declare, or a 405 without Allow, fails the run. The baseline holds only what the HTTP
# server answers before any route runs.
ACCEPTED=positive_data_acceptance,negative_data_rejection,allow_header_conformance

# --network host: the daemon refuses any Host that is not a loopback name, so the image must reach it as 127.0.0.1.
set +e
docker run --rm --user "$(id -u):$(id -g)" --network host -v "$OUT:/w:rw" -e FLOOR42_COOKIE "$IMAGE" --config-file /w/schemathesis.toml run /w/openapi.json \
  --url "$URL" --checks all --phases examples,coverage,fuzzing,stateful -n "$N" --seed "$SEED" --no-color \
  --generation-with-security-parameters false \
  --exclude-path-regex "$SKIP" --exclude-checks "$ACCEPTED" --request-timeout 20 \
  --report junit,ndjson --report-dir /w/signed-in $BL ${API_FUZZ_ARGS:-} >"$OUT/signed-in.txt" 2>&1
A=$?
tail -n 60 "$OUT/signed-in.txt"

docker run --rm --user "$(id -u):$(id -g)" --network host -v "$OUT:/w:rw" -e PYTHONDONTWRITEBYTECODE=1 -e PYTHONPATH=/w -e SCHEMATHESIS_HOOKS=schemathesis_noauth "$IMAGE" run /w/openapi.json \
  --url "$URL" --checks refused_without_session,not_a_server_error --phases examples,fuzzing -n "$N" --seed "$SEED" --no-color \
  --request-timeout 20 --report junit --report-dir /w/no-session >"$OUT/no-session.txt" 2>&1
B=$?
tail -n 40 "$OUT/no-session.txt"
set -e
echo "Schemathesis exit codes: signed in $A, without a session $B. Reports: target/api-fuzz/"
[ "$A" -eq 0 ] && [ "$B" -eq 0 ]
