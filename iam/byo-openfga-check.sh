#!/usr/bin/env bash
# Bring your own OpenFGA: checks the server Floor42 will use (store, key, one check). Needs bash, curl, openssl.
# The key is read from the environment and never printed.
#
#   FGA_URL=http://127.0.0.1:8181 FGA_KEY=... iam/byo-openfga-check.sh
#
# Finds or creates the store "floor42" (one store per Floor42 data folder), then runs a Check against a throwaway
# model with HIGHER_CONSISTENCY, and refuses a call without the key. Floor42 itself writes its own model at start.
set -euo pipefail
FGA_URL=${FGA_URL:-http://127.0.0.1:8181}
KEY=${FGA_KEY:?set FGA_KEY (the preshared key)}
ok() { echo "PASS  $*"; }
bad() { echo "FAIL  $*" >&2; exit 1; }
jstr() { grep -o "\"$1\" *: *\"[^\"]*\"" | head -1 | sed 's/^[^:]*: *"//; s/"$//'; }
call() { local m=$1 p=$2; shift 2; curl -s -X "$m" -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' "$FGA_URL$p" "$@"; }

code=$(curl -s -o /dev/null -w '%{http_code}' "$FGA_URL/stores")
[ "$code" = 401 ] || bad "a call without the key answered $code, expected 401 (is authn preshared?)"
ok "a call without the key is refused"

store=$(call GET '/stores?name=floor42' | jstr id)
if [ -z "$store" ]; then
  store=$(call POST /stores -d '{"name":"floor42"}' | jstr id)
  [ -n "$store" ] || bad "cannot create the store (wrong key?)"
  ok "store floor42 created"
else
  ok "store floor42 found"
fi

model=$(call POST "/stores/$store/authorization-models" \
  -d '{"schema_version":"1.1","type_definitions":[{"type":"user"},{"type":"doc","relations":{"viewer":{"this":{}}},"metadata":{"relations":{"viewer":{"directly_related_user_types":[{"type":"user"}]}}}}]}' \
  | jstr authorization_model_id)
[ -n "$model" ] || bad "cannot write a model"
ok "a model is accepted"
ans=$(call POST "/stores/$store/check" -d "{\"authorization_model_id\":\"$model\",\"consistency\":\"HIGHER_CONSISTENCY\",\"tuple_key\":{\"user\":\"user:a\",\"relation\":\"viewer\",\"object\":\"doc:1\"},\"contextual_tuples\":{\"tuple_keys\":[{\"user\":\"user:a\",\"relation\":\"viewer\",\"object\":\"doc:1\"}]}}")
case "$ans" in *'"allowed":true'*) ok "a check with a contextual tuple answers allowed";; *) bad "check answered: $ans";; esac
echo "Set floor42.iam.fga.url=$FGA_URL and put the key in Floor42's vault (entry fga-key) or floor42.iam.fga.key."
