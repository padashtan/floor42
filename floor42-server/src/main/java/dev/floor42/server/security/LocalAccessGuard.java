package dev.floor42.server.security;

import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Local daemon protection (ADR-0007, ADR-0034, ADR-0044), applied before any route:
 *
 * <ul>
 *   <li>Host must name the loopback interface: defeats DNS rebinding. A request a proxy forwarded
 *       ({@code X-Forwarded-For}, {@code X-Forwarded-Host}, {@code Forwarded}) is refused: Floor42 is reached through an
 *       SSH tunnel, never a proxy (docs/remote.md).
 *   <li>Origin, when present, must be exactly the daemon's own, {@code http://} plus the request's Host (scheme, host
 *       and port), or one of {@code floor42.access.extra-origins} (dev mode only). SameSite does not tell ports apart:
 *       a page of another local port is the same site, so the cookie alone proves nothing.
 *   <li>A request the browser's cookie authenticates must carry an Origin when it changes something or opens a
 *       WebSocket, and {@code X-Floor42-Request: 1} when it changes something: a page of another origin cannot set that
 *       header without a CORS preflight, which the daemon never grants. Bearer tokens (scripts) need neither.
 *   <li>The API requires the director: a browser session (HttpOnly, SameSite=Strict cookie) opened by a one-time
 *       sign-in link the daemon itself opens ({@code POST /auth/open}), a script token issued from the desk, limited to
 *       its scope, or the director's token, kept in memory.
 *   <li>An agent session's own credential is refused on the API with 403: it opens {@code /hooks} and {@code /mcp},
 *       nothing else.
 * </ul>
 */
@ApplicationScoped
public class LocalAccessGuard {

    private static final Logger LOG = Logger.getLogger(LocalAccessGuard.class);
    public static final String COOKIE = "floor42_session";
    /** The anti-CSRF header the web client sends on every request that changes something. */
    public static final String REQUEST_HEADER = "X-Floor42-Request";

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "floor42.localhost", "[::1]");
    /** Public endpoints: the mode banner must show even before login. */
    private static final Set<String> OPEN_API = Set.of("/api/status");

    private static final List<String> FORWARDED = List.of("X-Forwarded-For", "X-Forwarded-Host", "Forwarded");
    private static final Set<HttpMethod> READS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);
    /**
     * What a {@code missions:write} script token may change (ADR-0044): create a mission, approve or reject its plan
     * and its tickets, steer it (a directive, a nudge), and answer its questions. A permission question is refused by
     * the desk itself ({@link #scopeOf}), which knows the question's kind; the guard knows only paths. Everything else
     * (the director's files, the workshop's acceptance, pull requests, budgets, permissions, remembered approvals)
     * needs {@code all}.
     */
    private static final List<Pattern> MISSION_WRITES = List.of(
            Pattern.compile("/api/missions"),
            Pattern.compile("/api/missions/[^/]+/(approve|reject|nudge|tickets/approve|workshop/directive)"),
            Pattern.compile("/api/questions/[^/]+/answer"));

    /** Where the guard leaves the caller's scope for the routes that refine it ({@link #scopeOf}). */
    private static final String SCOPE_KEY = "floor42.scope";

    /** Where the guard leaves the caller's session for the routes that act on it ({@link #sessionOf}). */
    private static final String SESSION_KEY = "floor42.session";

    @Inject
    SessionToken token;

    @Inject
    DirectorSignIn signIn;

    @Inject
    SessionRegistry sessions;

    @Inject
    AgentCredentials agents;

    /** Quarkus's mapper: the error body is written by Jackson, like every other one. */
    @Inject
    com.fasterxml.jackson.databind.ObjectMapper json;

    @Inject
    jakarta.enterprise.event.Event<ConsoleSignIn> consoleSignIns;

    @ConfigProperty(name = "floor42.access.extra-origins")
    Optional<List<String>> extraOrigins;

    /**
     * Whether Quarkus's own pages under {@code /q/} (OpenAPI, Swagger UI, the dev UI) open without the director. Only
     * dev mode sets it: its dev UI and continuous testing live there. Everywhere else they need the director like the
     * API, which they describe and can drive ("Try it out").
     */
    @ConfigProperty(name = "floor42.access.open-quarkus-pages", defaultValue = "false")
    boolean openQuarkusPages;

    /** Who a request is, once the guard let it through. */
    public enum Via {
        BEARER,
        COOKIE
    }

    /** @param session null for the director's own token */
    record Caller(Via via, SessionRegistry.Scope scope, SessionRegistry.Session session) {}

    void register(@Observes Router router) {
        // Integer.MIN_VALUE is the API console's watcher: it sees refused calls too.
        router.route().order(Integer.MIN_VALUE + 1).handler(this::guard);
        router.get("/auth").order(Integer.MIN_VALUE + 2).handler(this::login);
        router.post("/auth").order(Integer.MIN_VALUE + 2).handler(this::login);
        router.post("/auth/open").order(Integer.MIN_VALUE + 2).handler(this::open);
        if (token.writtenToFile()) {
            LOG.warnf(
                    "floor42.director-token-file is deprecated and will go: the director token is written to %s,"
                            + " where unconfined agents can read it (ADR-0034). Issue a script token from the desk"
                            + " instead (System > Sessions and tokens, ADR-0044).",
                    token.file());
        } else {
            LOG.info("Director token kept in memory only: sign in with POST /auth/open (bin/floor42 does it)");
        }
    }

    private void guard(RoutingContext rc) {
        String origin = rc.request().getHeader(HttpHeaders.ORIGIN);
        Denial d = transport(rc, origin);
        if (d != null) {
            deny(rc, d.status(), d.code(), d.message());
            return;
        }
        String path = rc.normalizedPath();
        boolean socket = path.startsWith("/ws/");
        boolean api = path.equals("/api") || path.startsWith("/api/") || socket || quarkusPage(path);
        if (!api || OPEN_API.contains(path)) {
            rc.next();
            return;
        }
        Caller caller = caller(rc);
        d = caller == null ? unknown(rc) : refusal(rc, caller, origin, path, socket);
        if (d != null) {
            deny(rc, d.status(), d.code(), d.message());
            return;
        }
        rc.put(SCOPE_KEY, caller.scope());
        if (caller.session() != null) rc.put(SESSION_KEY, caller.session());
        rc.next();
    }

    /** Why the guard refuses a request, in the API's error shape. */
    private record Denial(int status, String code, String message) {}

    /** Refusals before any credential is read: the Host, a proxy, the Origin, a path hiding a route. */
    private Denial transport(RoutingContext rc, String origin) {
        if (!LOOPBACK.contains(requestHost(rc))) return new Denial(403, "FORBIDDEN", "Host not allowed");
        for (String header : FORWARDED) {
            if (rc.request().getHeader(header) != null) {
                return new Denial(
                        403,
                        "FORWARDED",
                        "This request went through a proxy (" + header + "): Floor42 is not supported behind a"
                                + " reverse proxy. Reach it through an SSH tunnel instead (docs/remote.md)");
            }
        }
        if (origin != null && !allowedOrigin(origin, rc.request().getHeader(HttpHeaders.HOST))) {
            return new Denial(403, "FORBIDDEN", "Origin not allowed: only the daemon's own page may call it");
        }
        if (hidesARoute(rc.request().path()) || hidesARoute(rc.normalizedPath())) {
            return new Denial(
                    400, "PATH_REFUSED", "A path parameter (;) or an encoded separator is not accepted in a path");
        }
        return null;
    }

    /** No director's credential: an agent session's is told so (403), anything else needs one (401). */
    private Denial unknown(RoutingContext rc) {
        if (agents.isLive(bearer(rc))) {
            return new Denial(
                    403,
                    "FORBIDDEN",
                    "An agent session's credential opens its hooks and Floor42's MCP server, never the API");
        }
        return new Denial(401, "UNAUTHORIZED", "Session token required");
    }

    /** The director's own request: a browser's change names its Origin and the header; a token keeps to its scope. */
    private Denial refusal(RoutingContext rc, Caller caller, String origin, String path, boolean socket) {
        boolean changes = !READS.contains(rc.request().method());
        if (caller.via() == Via.COOKIE) {
            if ((changes || socket) && origin == null) {
                return new Denial(403, "FORBIDDEN", "A browser request that changes something must name its Origin");
            }
            if (changes && !"1".equals(rc.request().getHeader(REQUEST_HEADER))) {
                return new Denial(403, "FORBIDDEN", "Missing " + REQUEST_HEADER + ": 1 (sent by Floor42's own page)");
            }
        }
        if (!allowedByScope(caller.scope(), path, changes || path.startsWith("/ws/terminal"))) {
            return new Denial(
                    403, "SCOPE", "This token's scope (" + caller.scope().wire() + ") does not allow this request");
        }
        return null;
    }

    /**
     * The scope of the director's credential on this request, as the guard checked it: {@code all} for the browser and
     * the director's token. A route the guard lets a narrower scope reach refines it with what only it knows (the desk
     * refuses a permission question to {@code missions:write}). Unknown (no guarded request) reads as {@code read}.
     */
    public static SessionRegistry.Scope scopeOf(RoutingContext rc) {
        Object scope = rc == null ? null : rc.get(SCOPE_KEY);
        return scope instanceof SessionRegistry.Scope s ? s : SessionRegistry.Scope.READ;
    }

    /**
     * The director's session this request came with, as the guard authenticated it: a browser's cookie or a script
     * token. Empty for the director's own token, and outside a guarded request.
     */
    public static Optional<SessionRegistry.Session> sessionOf(RoutingContext rc) {
        Object session = rc == null ? null : rc.get(SESSION_KEY);
        return session instanceof SessionRegistry.Session s ? Optional.of(s) : Optional.empty();
    }

    /** {@code /q} and below: the API's own documentation (OpenAPI, Swagger UI), guarded unless dev mode opens it. */
    private boolean quarkusPage(String path) {
        return !openQuarkusPages && (path.equals("/q") || path.startsWith("/q/"));
    }

    /**
     * Whether a raw path could reach a route the guard does not see: JAX-RS strips matrix parameters, so
     * {@code /api;x/agents} matches {@code /api/agents} while the guard reads a path outside {@code /api/}. Floor42
     * never uses matrix parameters, so any {@code ;} (raw or encoded) and any encoded slash or backslash is refused.
     */
    static boolean hidesARoute(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.contains(";") || lower.contains("%3b") || lower.contains("%2f") || lower.contains("%5c");
    }

    static boolean allowedByScope(SessionRegistry.Scope scope, String path, boolean changes) {
        return switch (scope) {
            case ALL -> true;
            case READ -> !changes;
            case MISSIONS_WRITE ->
                !changes
                        || MISSION_WRITES.stream().anyMatch(r -> r.matcher(path).matches());
        };
    }

    boolean allowedOrigin(String origin, String host) {
        String o = origin.toLowerCase(Locale.ROOT);
        if (host != null && o.equals("http://" + host.toLowerCase(Locale.ROOT))) return true;
        return extraOrigins.orElse(List.of()).stream()
                .anyMatch(e -> e.strip().toLowerCase(Locale.ROOT).equals(o));
    }

    /** The director, by bearer (token or script token) or cookie (browser session); null otherwise. */
    private Caller caller(RoutingContext rc) {
        String bearer = bearer(rc);
        if (bearer != null) {
            if (token.matches(bearer)) return new Caller(Via.BEARER, SessionRegistry.Scope.ALL, null);
            return sessions.authenticate(bearer)
                    .filter(s -> s.kind() == SessionRegistry.Kind.SCRIPT)
                    .map(s -> new Caller(Via.BEARER, s.scope(), s))
                    .orElse(null);
        }
        String cookie = cookieOf(rc.request().getHeader(HttpHeaders.COOKIE));
        if (cookie == null) return null;
        return sessions.authenticate(cookie)
                .filter(s -> s.kind() == SessionRegistry.Kind.BROWSER)
                .map(s -> new Caller(Via.COOKIE, SessionRegistry.Scope.ALL, s))
                .orElse(null);
    }

    private void login(RoutingContext rc) {
        String session = newSession(rc);
        if (session == null) return;
        consoleSignIns.fire(new ConsoleSignIn(
                sessions.authenticate(session).map(SessionRegistry.Session::id).orElse(null)));
        rc.response()
                .addCookie(sessionCookie(session))
                .putHeader(HttpHeaders.LOCATION, "/")
                .setStatusCode(303)
                .end();
    }

    /** The secret of the browser session this request opens (a link's code, or the director's bearer); null once refused. */
    private String newSession(RoutingContext rc) {
        String code = rc.request().getParam("code");
        String userAgent = rc.request().getHeader(HttpHeaders.USER_AGENT);
        if (code != null) {
            String session = signIn.redeem(code, userAgent).orElse(null);
            if (session == null) {
                deny(
                        rc,
                        401,
                        "UNAUTHORIZED",
                        "This sign-in link is unknown, expired or already used: ask for a new one");
            }
            return session;
        }
        if (rc.request().getParam("token") != null) {
            // A full-scope token in a URL lands in the browser history and the logs.
            deny(
                    rc,
                    400,
                    "TOKEN_IN_URL",
                    "The director token is never accepted in a URL: POST /auth with Authorization: Bearer <token>");
            return null;
        }
        if (rc.request().method() == HttpMethod.POST && token.matches(bearer(rc))) {
            // The browser gets a session of its own: the director's token never sits in its cookie store.
            return sessions.openBreakGlass(userAgent).secret();
        }
        deny(rc, 401, "UNAUTHORIZED", "Invalid token");
        return null;
    }

    public static Cookie sessionCookie(String value) {
        return Cookie.cookie(COOKIE, value)
                .setHttpOnly(true)
                .setSameSite(CookieSameSite.STRICT)
                .setPath("/");
    }

    /** Asks for a sign-in link, delivered to the director's browser, never to the caller (ADR-0034). */
    private void open(RoutingContext rc) {
        String base = signInBase(
                rc.request().getHeader(HttpHeaders.HOST),
                rc.request().localAddress().port());
        if (!signIn.open(base)) {
            deny(
                    rc,
                    429,
                    "TOO_MANY_REQUESTS",
                    "No sign-in link now: one was just opened, or no browser could be opened");
            return;
        }
        rc.response().setStatusCode(202).end();
    }

    static String bearer(RoutingContext rc) {
        return bearerOf(rc.request().getHeader(HttpHeaders.AUTHORIZATION));
    }

    /** The bearer secret of an Authorization header; null when there is none. */
    public static String bearerOf(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }

    /** The session cookie of a Cookie header; null when there is none. */
    public static String cookieOf(String header) {
        if (header == null) return null;
        for (io.netty.handler.codec.http.cookie.Cookie c :
                io.netty.handler.codec.http.cookie.ServerCookieDecoder.STRICT.decode(header)) {
            if (c.name().equals(COOKIE)) return c.value();
        }
        return null;
    }

    /** What a request presents, read from its raw headers: the one reading, for the guard and for the sockets. */
    public record Credential(boolean bearer, String secret) {}

    /** The credential of a request: a bearer wins over a cookie, as in the guard; null when it carries neither. */
    public static Credential credentialOf(String authorization, String cookieHeader) {
        String bearer = bearerOf(authorization);
        if (bearer != null) return new Credential(true, bearer);
        String cookie = cookieOf(cookieHeader);
        return cookie == null ? null : new Credential(false, cookie);
    }

    /** The API's documented error shape, {@code {code, message}}, written by Jackson. */
    private void deny(RoutingContext rc, int status, String code, String reason) {
        rc.response()
                .setStatusCode(status)
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(json.createObjectNode()
                        .put("code", code)
                        .put("message", reason)
                        .toString());
    }

    /** The Host header, or the HTTP/2 authority that replaces it (clients upgrading with h2c send no plain Host). */
    private static String requestHost(RoutingContext rc) {
        String header = rc.request().getHeader(HttpHeaders.HOST);
        if (header != null) return hostName(header);
        var authority = rc.request().authority();
        if (authority == null) return "";
        String host = authority.host();
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    /**
     * Where the sign-in link points: the loopback name the browser used (checked by {@code transport}) and the port
     * this daemon really listens on. Never the caller's Host header as sent: its port or anything after the name
     * ("127.0.0.1:6666", "localhost:1@elsewhere") would send the single-use code to another listener.
     */
    public static String signInBase(String hostHeader, int localPort) {
        String name = hostName(hostHeader);
        return "http://" + (LOOPBACK.contains(name) ? name : "127.0.0.1") + ":" + localPort;
    }

    static String hostName(String hostHeader) {
        if (hostHeader == null) return "";
        if (hostHeader.startsWith("[")) {
            int end = hostHeader.indexOf(']');
            return end < 0 ? hostHeader : hostHeader.substring(0, end + 1);
        }
        int colon = hostHeader.indexOf(':');
        return colon < 0 ? hostHeader : hostHeader.substring(0, colon);
    }
}
