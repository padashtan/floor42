"""Check for the signed-in run, loaded with SCHEMATHESIS_HOOKS=schemathesis_signed_in. It replaces the built-in
not_a_server_error (excluded in api-fuzz.sh) with the same rule and one narrow exception: a 503 whose body code is
FGA_UNAVAILABLE, on an operation whose OpenAPI document lists 503. The test daemon runs without IAM mode, so the routes
that write grants answer that fail-closed 503 by design (ADR-0080). Any other 5xx, a 503 with another code, or a 503 on
an operation that does not document it, still fails. The real authorization routes are attacked in IAM mode, against a
live OpenFGA, in the v1.4 security lab."""
import json

import schemathesis


def _code(response):
    try:
        return json.loads(response.text or "").get("code")
    except (ValueError, AttributeError):
        return None


@schemathesis.check
def server_error_unless_fga_unavailable(ctx, response, case):
    status = response.status_code
    if status < 500:
        return
    documented = {str(s) for s in case.operation.definition.raw.get("responses", {})}
    if status == 503 and _code(response) == "FGA_UNAVAILABLE" and "503" in documented:
        return
    raise AssertionError(f"{case.method} {case.operation.path} answered {status}")
