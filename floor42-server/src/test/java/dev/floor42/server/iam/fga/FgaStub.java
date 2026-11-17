package dev.floor42.server.iam.fga;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A local HTTP server standing for OpenFGA: records every request and answers what the test's handler says. */
final class FgaStub implements AutoCloseable {

    static final ObjectMapper JSON = new ObjectMapper();

    /** A request as received; {@code body} is null for an empty one. */
    record Req(String method, String path, String authorization, JsonNode body) {}

    /** What to answer. */
    record Res(int status, String body) {}

    final HttpServer server;
    final List<Req> requests = new CopyOnWriteArrayList<>();
    volatile Function<Req, Res> handler = r -> new Res(200, "{}");
    volatile long delayMillis;

    FgaStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void handle(HttpExchange x) throws IOException {
        byte[] in = x.getRequestBody().readAllBytes();
        String path = x.getRequestURI().getRawPath()
                + (x.getRequestURI().getRawQuery() == null
                        ? ""
                        : "?" + x.getRequestURI().getRawQuery());
        Req req = new Req(
                x.getRequestMethod(),
                path,
                x.getRequestHeaders().getFirst("Authorization"),
                in.length == 0 ? null : JSON.readTree(in));
        requests.add(req);
        try {
            if (delayMillis > 0) Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Res res = handler.apply(req);
        byte[] out = res.body().getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        try {
            x.sendResponseHeaders(res.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) x.getResponseBody().write(out);
        } catch (IOException e) {
            // The client gave up (a timeout test): nothing to answer to
        } finally {
            x.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
