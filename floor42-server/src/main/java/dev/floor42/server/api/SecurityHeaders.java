package dev.floor42.server.api;

import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.regex.Pattern;

/**
 * The application page's own protection (ADR-0007, ADR-0044): a strict Content-Security-Policy on every HTML page
 * Floor42 serves, no inline script, no plugin, never framed; and {@code nosniff} and no referrer on every answer.
 *
 * <p>Set when the headers go out, and only where none is set yet: a deliverable keeps its own sandbox policy
 * ({@link DeliverablesResource}), and the API documentation under {@code /q/} (Swagger UI, which needs inline scripts)
 * keeps what Quarkus gives it.
 */
@ApplicationScoped
public class SecurityHeaders {

    /** A Host the guard accepted is a loopback name and a port; anything else never reaches the policy. */
    private static final Pattern HOST =
            Pattern.compile("[A-Za-z0-9.\\-]+(:[0-9]{1,5})?|\\[[0-9A-Fa-f:]+](:[0-9]{1,5})?");

    /**
     * The device features the page never uses are denied; the clipboard stays for the copy buttons and the terminal's
     * paste (checked in floor42-web/src: navigator.clipboard, no camera, microphone, location, USB or payment).
     * Cross-Origin-Embedder-Policy is left out on purpose: {@code require-corp} would also bind the PDF viewer's frame and
     * the sandboxed deliverables, which load without CORS from an opaque origin, and nothing needs cross-origin isolation.
     */
    static final String PERMISSIONS =
            "camera=(), microphone=(), geolocation=(), usb=(), payment=(), bluetooth=(), serial=(),"
                    + " hid=(), midi=(), display-capture=(), accelerometer=(), gyroscope=(), magnetometer=(),"
                    + " clipboard-read=(self), clipboard-write=(self)";

    /**
     * No inline script, no eval. Styles come from the bundle's own file; the one exception is {@code <style>} elements,
     * which xterm.js 6's DOM renderer (the director's terminal) creates with computed cell sizes and colours: no hash can
     * match them and xterm offers no nonce, so {@code style-src-elem} allows inline elements, while inline style
     * attributes stay refused ({@code style-src 'self'}). CSS cannot run script.
     */
    public static String policy(String host) {
        String socket = host != null && HOST.matcher(host).matches() ? " ws://" + host : "";
        return "default-src 'self'; script-src 'self'; style-src 'self'; style-src-elem 'self' 'unsafe-inline';"
                + " img-src 'self' data: blob:;"
                + " font-src 'self' data:; connect-src 'self'" + socket + "; worker-src 'self' blob:;"
                + " frame-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none';"
                + " form-action 'self'";
    }

    void register(@Observes Router router) {
        // Before the access guard (MIN_VALUE + 1), so its refusals carry the headers too.
        router.route().order(Integer.MIN_VALUE).handler(this::mark);
    }

    private void mark(RoutingContext rc) {
        String path = rc.normalizedPath();
        String host = rc.request().getHeader(HttpHeaders.HOST);
        rc.addHeadersEndHandler(v -> {
            HttpServerResponse r = rc.response();
            putIfAbsent(r, "X-Content-Type-Options", "nosniff");
            putIfAbsent(r, "Referrer-Policy", "no-referrer");
            putIfAbsent(r, "Permissions-Policy", PERMISSIONS);
            putIfAbsent(r, "Cross-Origin-Opener-Policy", "same-origin");
            // A deliverable or file answer carries its own sandbox policy: its page has an opaque origin, so a
            // same-origin
            // resource policy would block its own images and styles. Everything else is for the page's origin only.
            String csp = r.headers().get("Content-Security-Policy");
            if (csp == null || !csp.contains("sandbox")) putIfAbsent(r, "Cross-Origin-Resource-Policy", "same-origin");
            String type = r.headers().get(HttpHeaders.CONTENT_TYPE);
            boolean page = type != null
                    && type.startsWith("text/html")
                    && !path.startsWith("/q/")
                    && !path.startsWith("/api/");
            if (page) {
                putIfAbsent(r, "Content-Security-Policy", policy(host));
                putIfAbsent(r, "X-Frame-Options", "DENY");
            }
        });
        rc.next();
    }

    private static void putIfAbsent(HttpServerResponse r, String name, String value) {
        if (!r.headers().contains(name)) r.putHeader(name, value);
    }
}
