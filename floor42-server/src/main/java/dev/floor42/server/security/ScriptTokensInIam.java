package dev.floor42.server.security;

import io.vertx.core.http.HttpMethod;
import java.util.Set;

/**
 * In IAM mode a script token starts no agent work without a human (1.2.0), so it gets a default deny, as plan approval
 * ({@code StepUpApprovals.mayApprove}, a browser signed in through Keycloak) already does. Allowed: a read (GET, HEAD)
 * except the director's terminals and the three that run git in a folder it names, creating a mission (a proposal),
 * and the MCP endpoint, whose tools {@code DirectorMcpResource} filters the same way. Everything else, and any
 * websocket, is refused before the scope is looked at.
 */
public final class ScriptTokensInIam {

    /** The code of the refusal, on a route, on a mission's extra fields and on an MCP tool. */
    public static final String REFUSED = "IAM_SCRIPT_TOKEN_REFUSED";

    public static final String MESSAGE =
            "In IAM mode a script token starts no agent work: the director does it signed in through Keycloak";

    /** These GETs run git in a folder the caller names (and a repository's config can name programs to run). */
    private static final Set<String> PATH_PROBES =
            Set.of("/api/preflight", "/api/projects/folders", "/api/projects/init");

    private ScriptTokensInIam() {}

    /** Whether a script token may send this request in IAM mode; the path has no trailing slash. */
    static boolean mayCall(HttpMethod method, String path) {
        if (path.startsWith("/ws/") || path.equals("/api/terminals") || path.startsWith("/api/terminals/"))
            return false;
        if (PATH_PROBES.contains(path)) return false;
        if (method == HttpMethod.GET || method == HttpMethod.HEAD) return true;
        return method == HttpMethod.POST
                && (path.equals("/api/missions") || path.equals(LocalAccessGuard.DIRECTOR_MCP));
    }

    /** A path without its trailing slash, so a pattern cannot be skipped by adding one. */
    static String withoutTrailingSlash(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
