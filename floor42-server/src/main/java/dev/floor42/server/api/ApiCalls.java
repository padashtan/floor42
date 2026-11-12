package dev.floor42.server.api;

import dev.floor42.server.security.LocalAccessGuard;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Every call made to the daemon's API, for the director's console: what was called, by whom, with what status and how
 * long it took. Kept in memory (the last {@value #CAPACITY}), never in the journal: these are traffic facts, not company
 * facts. The bodies of a call are kept beside it, capped at {@value #BODY_LIMIT} bytes each, for the console to show
 * on demand; the session token never is (a token in the query string is masked, and it travels in no body).
 */
@ApplicationScoped
public class ApiCalls {

    static final int CAPACITY = 500;
    static final int BODY_LIMIT = 32 * 1024;
    /** Set on a request the console records, so {@link ApiBodies} keeps its bodies. */
    static final String WATCHED = "floor42.console.watched";

    private static final Pattern TOKEN = Pattern.compile("(?i)((?:token|code)=)[^&]*");

    /** @param caller who made the call: the UI (session cookie), a script (bearer), an agent hook, or anonymous */
    public record Call(
            long sequence, String at, String method, String path, int status, long durationMs, String caller) {}

    /**
     * What the console shows when a call is opened. A body is null when there was none, or none the API read or wrote
     * (a WebSocket, a refused call); {@code truncated} says a body was longer than what is kept.
     */
    public record CallDetail(Call call, String requestBody, String responseBody, boolean truncated) {}

    private record Bodies(String request, String response, boolean truncated) {}

    private final ArrayDeque<Call> recent = new ArrayDeque<>();
    private final java.util.Map<Long, Bodies> bodies = new java.util.HashMap<>();
    private final List<Consumer<Call>> listeners = new CopyOnWriteArrayList<>();
    private long sequence;

    void register(@Observes Router router) {
        router.route().order(Integer.MIN_VALUE).handler(this::watch);
    }

    /** Watches one request: the call is recorded when its status line is sent (101 for a WebSocket upgrade). */
    void watch(RoutingContext rc) {
        String path = rc.normalizedPath();
        if (!worthLogging(path)) {
            rc.next();
            return;
        }
        Instant start = Instant.now();
        String query = rc.request().query();
        String shown = query == null ? path : path + "?" + TOKEN.matcher(query).replaceAll("$1***");
        String method = rc.request().method().name();
        String caller = callerOf(rc, path);
        // The console's own history is not copied into itself, and a route that carries a secret keeps no body.
        boolean keepBodies = !path.startsWith("/api/calls") && !ConsoleMask.secretRoute(path);
        if (keepBodies) rc.put(WATCHED, true);
        long[] seq = {0};
        rc.addHeadersEndHandler(v -> seq[0] = record(
                start, method, shown, rc.response().getStatusCode(), Duration.between(start, Instant.now()), caller));
        if (keepBodies) rc.addBodyEndHandler(v -> keep(seq[0], rc));
        rc.next();
    }

    /** The API, its streams, the agents' hook and the login: not the UI's static files. */
    static boolean worthLogging(String path) {
        return path.startsWith("/api/")
                || path.equals("/api")
                || path.startsWith("/ws/")
                || path.startsWith("/hooks/")
                || path.equals("/auth")
                || path.equals("/auth/open")
                || path.startsWith("/q/openapi");
    }

    static String callerOf(RoutingContext rc, String path) {
        if (path.startsWith("/hooks/")) return "agent hook";
        if (rc.request().getCookie(LocalAccessGuard.COOKIE) != null) return "UI";
        if (rc.request().getHeader("Authorization") != null) return "script";
        return "anonymous";
    }

    private long record(Instant at, String method, String path, int status, Duration took, String caller) {
        Call call;
        synchronized (recent) {
            call = new Call(++sequence, at.toString(), method, path, status, took.toMillis(), caller);
            recent.addLast(call);
            if (recent.size() > CAPACITY) bodies.remove(recent.removeFirst().sequence());
        }
        listeners.forEach(l -> l.accept(call));
        return call.sequence();
    }

    private void keep(long seq, RoutingContext rc) {
        byte[] request = rc.get(ApiBodies.REQUEST);
        ApiBodies.Copy response = rc.get(ApiBodies.RESPONSE);
        if (seq == 0 || (request == null && response == null)) return;
        boolean truncated =
                (request != null && request.length > BODY_LIMIT) || (response != null && response.total > BODY_LIMIT);
        Bodies kept = new Bodies(
                request == null ? null : text(request, Math.min(request.length, BODY_LIMIT)),
                response == null ? null : text(response.kept.toByteArray(), response.kept.size()),
                truncated);
        synchronized (recent) {
            if (recent.isEmpty() || recent.peekFirst().sequence() > seq) return; // already gone
            bodies.put(seq, kept);
        }
    }

    private static String text(byte[] bytes, int length) {
        return ConsoleMask.masked(new String(bytes, 0, length, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** One call with its bodies, while the console still keeps it. */
    public java.util.Optional<CallDetail> detail(long seq) {
        synchronized (recent) {
            return recent.stream().filter(c -> c.sequence() == seq).findFirst().map(c -> {
                Bodies b = bodies.get(seq);
                return b == null
                        ? new CallDetail(c, null, null, false)
                        : new CallDetail(c, b.request(), b.response(), b.truncated());
            });
        }
    }

    /** The latest calls, oldest first. */
    public List<Call> recent(int limit) {
        synchronized (recent) {
            return recent.stream().skip(Math.max(0, recent.size() - limit)).toList();
        }
    }

    public AutoCloseable subscribe(Consumer<Call> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
