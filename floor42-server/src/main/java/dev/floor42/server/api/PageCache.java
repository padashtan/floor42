package dev.floor42.server.api;

import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * The page itself is never cached: after a redeploy the browser must load the new bundle (its assets are
 * content-hashed, so they may be). Set when the headers go out, after the static handler: in the packaged daemon it
 * marks every file {@code public, immutable, max-age=86400}, and a header filter set in the configuration lost to it.
 */
@ApplicationScoped
public class PageCache {

    void register(@Observes Router router) {
        router.get().order(Integer.MIN_VALUE + 3).handler(this::mark);
    }

    private void mark(RoutingContext rc) {
        rc.addHeadersEndHandler(v -> {
            String type = rc.response().headers().get(HttpHeaders.CONTENT_TYPE);
            if (type != null && type.startsWith("text/html")) {
                rc.response().putHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
            }
        });
        rc.next();
    }
}
