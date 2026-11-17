package dev.floor42.server.iam;

import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;

/** The page a browser lands on after an assured act's sign-in. A page, not a 303: the session cookie is SameSite=Strict. */
final class ActPages {

    private ActPages() {}

    static void send(RoutingContext rc, String text, boolean backToDesk) {
        rc.response()
                .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
                .putHeader(HttpHeaders.CONTENT_TYPE, "text/html; charset=utf-8")
                .setStatusCode(200)
                .end("<!doctype html><meta charset=utf-8>"
                        + (backToDesk ? "<meta http-equiv=refresh content=\"0;url=/\">" : "")
                        + "<title>Floor42</title><p>" + text + "</p>"
                        + (backToDesk ? "<a href=\"/\">Back to Floor42</a>" : ""));
    }
}
