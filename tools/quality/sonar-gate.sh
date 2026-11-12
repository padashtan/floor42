#!/bin/bash
# Creates the floor42 project and the "Floor42 way" quality gate on a local SonarQube (idempotent).
# Token read from ~/.floor42-sonar-token, never printed.
set -u
U=${SONAR_URL:-http://localhost:9000}; T=$(cat ~/.floor42-sonar-token)
api() { curl -s -u "$T:" -X POST "$U/api/$1" "${@:2}"; }
api projects/create -d project=floor42 -d name=Floor42 >/dev/null
api qualitygates/create -d name="Floor42 way" >/dev/null
cond() { api qualitygates/create_condition -d gateName="Floor42 way" -d metric="$1" -d op="$2" -d error="$3" | head -c 160; echo; }
# Sonar way, on new code
cond new_reliability_rating GT 1
cond new_security_rating GT 1
cond new_maintainability_rating GT 1
cond new_coverage LT 80
cond new_duplicated_lines_density GT 3
cond new_security_hotspots_reviewed LT 100
# Floor42: overall code (Google coverage tiers, no open vulnerability)
cond coverage LT 75
cond vulnerabilities GT 0
cond security_rating GT 1
api qualitygates/select -d gateName="Floor42 way" -d projectKey=floor42 >/dev/null
curl -s -u "$T:" "$U/api/qualitygates/get_by_project?project=floor42"; echo
