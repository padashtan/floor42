package dev.floor42.server.security;

import dev.floor42.server.iam.ScriptTokenBindings;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

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

    /** A bound token whose Keycloak session ended (ADR-0083): refused on every route for good. */
    public static final String UNBOUND = "SCRIPT_TOKEN_UNBOUND";

    public static final String IDP_UNAVAILABLE = "IDP_UNAVAILABLE";

    /**
     * The POST routes that start or steer agent work, which a script token bound to a Keycloak session may take in IAM
     * mode and an unbound one may not (ADR-0083). A bound token reaches nothing else {@link #mayCall} refuses: no
     * terminal, no file, no setting, no new token. The scope still applies on top.
     */
    static final List<Pattern> STEERING = List.of(
            Pattern.compile("/api/missions/[^/]+/(tickets/approve|nudge|workshop/directive|resume)"),
            Pattern.compile("/api/questions/[^/]+/answer"),
            Pattern.compile("/api/agents/[^/]+/resume"));

    /** Approving a plan is the director's own act, whatever the token (ADR-0064 point 2). */
    private static final Pattern PLAN_APPROVAL = Pattern.compile("/api/missions/[^/]+/approve");

    private ScriptTokensInIam() {}

    /** Whether a token bound to a live Keycloak session may send this request in IAM mode. */
    static boolean boundMayCall(HttpMethod method, String path) {
        return mayCall(method, path)
                || (method == HttpMethod.POST
                        && STEERING.stream().anyMatch(r -> r.matcher(path).matches()));
    }

    /** The refusal of a script token in IAM mode by what it is bound to, or null when it may go on. */
    static Denial refusal(HttpMethod method, String path, ScriptTokenBindings.Liveness bound) {
        return switch (bound) {
            case DEAD ->
                new Denial(401, UNBOUND, "The Keycloak session behind this token ended: the director issues a new one");
            case UNAVAILABLE ->
                new Denial(503, IDP_UNAVAILABLE, "Keycloak did not answer: a bound token is refused meanwhile");
            case LIVE ->
                method == HttpMethod.POST && PLAN_APPROVAL.matcher(path).matches()
                        ? new Denial(
                                403, "HUMAN_ONLY", "A plan is approved by the director on the desk, never by a token")
                        : boundMayCall(method, path) ? null : new Denial(403, REFUSED, MESSAGE);
            default -> mayCall(method, path) ? null : new Denial(403, REFUSED, MESSAGE);
        };
    }

    /**
     * A bound token never approves (ADR-0083): the desk, which reads the scope to refuse a permission question, takes
     * one of scope all for missions:write.
     */
    static SessionRegistry.Scope seenByDesk(SessionRegistry.Scope scope, boolean bound) {
        return bound && scope == SessionRegistry.Scope.ALL ? SessionRegistry.Scope.MISSIONS_WRITE : scope;
    }

    /** The last answer of Keycloak is older than a minute: asked off the event loop, the request waits for it. */
    static void ask(
            RoutingContext rc,
            ScriptTokenBindings bindings,
            String tokenId,
            Consumer<ScriptTokenBindings.Liveness> then) {
        rc.request().pause();
        rc.vertx()
                .<ScriptTokenBindings.Liveness>executeBlocking(() -> bindings.live(tokenId), false)
                .onComplete(done -> {
                    rc.request().resume();
                    then.accept(done.succeeded() ? done.result() : ScriptTokenBindings.Liveness.UNAVAILABLE);
                });
    }

    /** Why a request is refused, in the API's error shape. */
    record Denial(int status, String code, String message) {}

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
