"""Schemathesis check for the run without a session: only /api/status is public, so every other operation must
answer 401 or 403 (never 2xx, never 5xx). Loaded with SCHEMATHESIS_HOOKS=schemathesis_noauth.

Accepted, and only these, because the HTTP layer refuses the request before any handler or the access guard runs and
nothing of ours answers: a 405 (a method the route does not serve), and a 400 that is either the daemon's PATH_REFUSED
(a `;` or an encoded separator in a path), the framework's bare HTML 400 page (a body that is not valid UTF-8), or an
empty 400 (a header the server cannot parse, such as a negative Content-Length). On the agent door (/hooks/*, /mcp) a body
that is not JSON is refused with the framework's BAD_REQUEST before the route checks the bearer token; it holds nothing."""
import schemathesis

PUBLIC = {"/api/status"}


def _known_400(response, path):
    body = response.text or ""
    door = path.startswith("/hooks/") or path == "/mcp"
    return body == "" or "PATH_REFUSED" in body or "<p>HTTP 400</p>" in body or (door and "BAD_REQUEST" in body)


@schemathesis.check
def refused_without_session(ctx, response, case):
    if case.operation.path in PUBLIC:
        return
    status = response.status_code
    if status in (401, 403, 405) or (status == 400 and _known_400(response, case.operation.path)):
        return
    raise AssertionError(f"{case.method} {case.operation.path} answered {status} without a session")
