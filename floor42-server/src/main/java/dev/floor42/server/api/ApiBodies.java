package dev.floor42.server.api;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Lends the API console the bodies of the calls it shows: the request as the resource read it, the response as it was
 * written. Only for calls the console keeps, and capped ({@link ApiCalls#BODY_LIMIT}): the daemon's memory, never the
 * journal.
 */
@Provider
public class ApiBodies implements ReaderInterceptor, WriterInterceptor {

    static final String REQUEST = "floor42.console.request";
    static final String RESPONSE = "floor42.console.response";

    @Inject
    CurrentVertxRequest current;

    @Override
    public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
        RoutingContext rc = watched();
        if (rc == null) return context.proceed();
        byte[] body = context.getInputStream().readAllBytes();
        rc.put(REQUEST, body);
        context.setInputStream(new ByteArrayInputStream(body));
        return context.proceed();
    }

    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
        RoutingContext rc = watched();
        if (rc == null) {
            context.proceed();
            return;
        }
        Copy copy = new Copy(context.getOutputStream());
        rc.put(RESPONSE, copy);
        context.setOutputStream(copy);
        context.proceed();
    }

    /** The request, if the console records it. */
    private RoutingContext watched() {
        RoutingContext rc = current.getCurrent();
        return rc != null && rc.get(ApiCalls.WATCHED) != null ? rc : null;
    }

    /** Writes through, keeping a copy of the first bytes for the console. */
    static final class Copy extends FilterOutputStream {
        final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        long total;

        Copy(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            total++;
            if (kept.size() < ApiCalls.BODY_LIMIT) kept.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            total += len;
            int room = ApiCalls.BODY_LIMIT - kept.size();
            if (room > 0) kept.write(b, off, Math.min(room, len));
        }
    }
}
