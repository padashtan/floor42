package dev.floor42.server.iam.fga;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * The HTTP part shared by the client and the store: the preshared key as a bearer token (never logged), 2 s for the
 * whole answer, no redirect. A 2xx, a 400 and a 404 come back as a {@link Reply} for the caller to read; anything else
 * (and a transport failure) is {@link FgaException#UNAVAILABLE}.
 * <p>shortcut: the answer is not size-capped (OpenFGA is on loopback and trusted with the key); cap it if the url ever
 * points off-machine.
 */
final class FgaHttp {

    private static final Logger LOG = Logger.getLogger(FgaHttp.class);
    static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** The status and the parsed body ({@code null} when empty). */
    record Reply(int status, JsonNode body) {
        boolean ok() {
            return status / 100 == 2;
        }
    }

    private final Supplier<URI> base;
    private final Supplier<Optional<String>> key;
    private final ObjectMapper json;

    FgaHttp(Supplier<URI> base, Supplier<Optional<String>> key, ObjectMapper json) {
        this.base = base;
        this.key = key;
        this.json = json;
    }

    ObjectMapper json() {
        return json;
    }

    Reply call(String method, String path, JsonNode body) {
        var future = HTTP.sendAsync(request(method, path, body), HttpResponse.BodyHandlers.ofByteArray());
        return reply(await(future));
    }

    private HttpRequest request(String method, String path, JsonNode body) {
        String root = base.get().toString().replaceAll("/+$", "");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(root + path)).timeout(TIMEOUT);
        key.get().ifPresent(k -> b.header("Authorization", "Bearer " + k));
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        }
        return b.build();
    }

    private static HttpResponse<byte[]> await(CompletableFuture<HttpResponse<byte[]>> future) {
        try {
            return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new FgaException(
                    FgaException.UNAVAILABLE, "OpenFGA did not answer in " + TIMEOUT.toSeconds() + " s", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new FgaException(FgaException.UNAVAILABLE, "The OpenFGA call was interrupted", e);
        } catch (ExecutionException e) {
            throw new FgaException(
                    FgaException.UNAVAILABLE,
                    "OpenFGA is unreachable: " + e.getCause().getClass().getSimpleName(),
                    e);
        }
    }

    private Reply reply(HttpResponse<byte[]> r) {
        int s = r.statusCode();
        if (s == 401 || s == 403) {
            LOG.warn("OpenFGA refused Floor42's key (HTTP " + s + ")");
            throw new FgaException(FgaException.UNAVAILABLE, "OpenFGA refused Floor42's key");
        }
        if (s / 100 != 2 && s != 400 && s != 404)
            throw new FgaException(FgaException.UNAVAILABLE, "OpenFGA answered HTTP " + s);
        try {
            return new Reply(s, r.body().length == 0 ? null : json.readTree(r.body()));
        } catch (IOException e) {
            throw new FgaException(FgaException.UNAVAILABLE, "OpenFGA answered something that is not JSON", e);
        }
    }
}
