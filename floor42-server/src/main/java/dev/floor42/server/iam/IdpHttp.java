package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * The wire of {@link KeycloakClient}: one JDK client that never follows a redirect, and the mapping of every outcome
 * to an exception. Nothing from a body, a token or a secret is put in a message or a log line.
 */
final class IdpHttp {

    private static final Logger LOG = Logger.getLogger(KeycloakClient.class);
    private static final Pattern ERROR_CODE = Pattern.compile("[a-z_]{1,40}");

    private final ObjectMapper json;
    private final IdpExchanges exchanges;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    IdpHttp(ObjectMapper json, IdpExchanges exchanges) {
        this.json = json;
        this.exchanges = exchanges;
    }

    /**
     * The parsed JSON answer (empty object for an empty body). 5xx and network errors: unavailable; other non-2xx:
     * refused. {@code form} is the url-encoded body sent, for the exchanges log (masked there), or null.
     */
    JsonNode call(String op, HttpRequest.Builder request, String form) {
        HttpResponse<String> res =
                send(op, request.timeout(Duration.ofSeconds(10)).build(), form);
        return res.body().isBlank() ? json.createObjectNode() : read(op, res.body());
    }

    private HttpResponse<String> send(String op, HttpRequest request, String form) {
        HttpResponse<String> res;
        Instant at = Instant.now();
        long start = System.nanoTime();
        try {
            res = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log(op, request, form, at, start, 0, null, e.getClass().getSimpleName());
            throw new IdpUnavailableException("Keycloak unreachable during " + op, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdpUnavailableException("Interrupted during " + op, e);
        }
        log(op, request, form, at, start, res.statusCode(), res.body(), null);
        int status = res.statusCode();
        LOG.debugf("keycloak %s: HTTP %d", op, status);
        if (status >= 500) throw new IdpUnavailableException("Keycloak failed during " + op + ": HTTP " + status, null);
        if (status < 200 || status >= 300)
            throw new IdpRefusedException(
                    "Keycloak refused " + op + ": HTTP " + status + errorCode(res.body()), status);
        return res;
    }

    private void log(
            String op, HttpRequest req, String form, Instant at, long start, int status, String body, String error) {
        long millis = (System.nanoTime() - start) / 1_000_000;
        exchanges.record(at, op, req.method(), req.uri(), form, status, millis, body, error);
    }

    /** Keycloak's {@code error} field, only when it is a plain short code: never free text that could echo input. */
    private String errorCode(String body) {
        try {
            String code = json.readTree(body).path("error").asText("");
            return ERROR_CODE.matcher(code).matches() ? " " + code : "";
        } catch (IOException e) {
            // Handled: an unreadable body gives no code; the status already says what happened.
            return "";
        }
    }

    private JsonNode read(String op, String body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new IdpUnavailableException("Keycloak answered " + op + " with something unreadable", e);
        }
    }
}
