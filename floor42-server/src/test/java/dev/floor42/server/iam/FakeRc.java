package dev.floor42.server.iam;

import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** A request and its response as plain data, for handlers tested without a server (no Quarkus, no Vert.x loop). */
public final class FakeRc {

    public final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    public final Map<String, String> params = new HashMap<>();
    public final Map<String, String> cookies = new HashMap<>();
    public final Map<String, Object> context = new HashMap<>();
    public int port = 7420;
    public HttpMethod method = HttpMethod.GET;
    public String path = "/";
    /** Null: the same as {@link #path}. */
    public String normalizedPath;
    /** The HTTP/2 authority, used when there is no Host header. */
    public HostAndPort authority;

    public int status;
    public final Map<String, String> sentHeaders = new HashMap<>();
    public final List<Cookie> sentCookies = new ArrayList<>();
    public String body;
    /** The guard let the request through to the next handler. */
    public boolean passed;

    private final Object request = Proxy.newProxyInstance(
            FakeRc.class.getClassLoader(),
            new Class<?>[] {io.vertx.core.http.HttpServerRequest.class},
            (p, m, a) -> switch (m.getName()) {
                case "getHeader" ->
                    a[0].toString().equalsIgnoreCase("Cookie") && !cookies.isEmpty()
                            ? String.join(
                                    "; ",
                                    cookies.entrySet().stream()
                                            .map(e -> e.getKey() + "=" + e.getValue())
                                            .toList())
                            : headers.get(a[0].toString());
                case "getParam" -> params.get(a[0].toString());
                case "getCookie" -> cookie(a[0].toString());
                case "cookies" -> {
                    Set<Cookie> all = new HashSet<>();
                    cookies.forEach((k, v) -> all.add(Cookie.cookie(k, v)));
                    yield all;
                }
                case "localAddress" -> SocketAddress.inetSocketAddress(port, "127.0.0.1");
                case "method" -> method;
                case "path" -> path;
                case "authority" -> authority;
                default -> null;
            });

    private final Object response = Proxy.newProxyInstance(
            FakeRc.class.getClassLoader(),
            new Class<?>[] {io.vertx.core.http.HttpServerResponse.class},
            new InvocationHandler() {
                @Override
                public Object invoke(Object self, Method m, Object[] a) {
                    switch (m.getName()) {
                        case "setStatusCode" -> status = (Integer) a[0];
                        case "putHeader" -> sentHeaders.put(a[0].toString().toLowerCase(), a[1].toString());
                        case "addCookie" -> sentCookies.add((Cookie) a[0]);
                        case "end" -> {
                            body = a != null && a.length > 0 && a[0] instanceof String s ? s : "";
                            return null;
                        }
                        default -> {
                            return null;
                        }
                    }
                    return self;
                }
            });

    public final RoutingContext rc = (RoutingContext) Proxy.newProxyInstance(
            FakeRc.class.getClassLoader(), new Class<?>[] {RoutingContext.class}, (p, m, a) -> switch (m.getName()) {
                case "request" -> request;
                case "response" -> response;
                case "normalizedPath" -> normalizedPath != null ? normalizedPath : path;
                case "next" -> {
                    passed = true;
                    yield null;
                }
                case "put" -> {
                    context.put((String) a[0], a[1]);
                    yield p;
                }
                case "get" -> context.get(a[0]);
                default -> null;
            });

    public FakeRc header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public FakeRc param(String name, String value) {
        params.put(name, value);
        return this;
    }

    public FakeRc withCookie(String name, String value) {
        cookies.put(name, value);
        return this;
    }

    /** A request as the browser sends it to the daemon: loopback Host, a path and a method. */
    public static FakeRc request(HttpMethod method, String path) {
        FakeRc r = new FakeRc().header("Host", "127.0.0.1:7420");
        r.method = method;
        r.path = path;
        return r;
    }

    private Cookie cookie(String name) {
        return cookies.containsKey(name) ? Cookie.cookie(name, cookies.get(name)) : null;
    }

    /** The cookie the handler gave, by name. */
    public String given(String name) {
        return sentCookies.stream()
                .filter(c -> c.getName().equals(name))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    /** The {@code code} field of a JSON error body, or null. */
    public String code() {
        return body == null || !body.contains("\"code\":\"")
                ? null
                : body.replaceAll(".*\"code\":\"([^\"]*)\".*", "$1");
    }
}
