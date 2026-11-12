package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The issuer's signing keys by {@code kid}. The first miss after a good fetch always refetches (a legitimate rotation
 * is seen at once); after a refetch that still lacked the kid, no other refetch happens for {@link #MIN_REFETCH}, so
 * forged kids cannot flood the issuer. Limit: a real rotation within that window of a forged kid waits for the end of
 * the window. Keys have no TTL: {@link #refresh()} from the 60 s background check keeps them current.
 */
public final class Jwks {

    public static final Duration MIN_REFETCH = Duration.ofSeconds(30);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI certsUri;
    private final HttpClient http;
    private final Clock clock;
    private volatile Map<String, PublicKey> keys = Map.of();
    private volatile boolean reachable = true;
    /** The fetch lock: private, so no other code can hold it (a lock on the shared instance could stall every miss). */
    private final Object fetching = new Object();

    private Instant lastFruitlessFetch;
    private volatile IdpExchanges exchanges;

    public Jwks(URI certsUri, HttpClient http, Clock clock) {
        this.certsUri = certsUri;
        this.http = http;
        this.clock = clock;
    }

    /** Each fetch of the keys is shown among the exchanges with Keycloak from now on. */
    public Jwks recordInto(IdpExchanges exchanges) {
        this.exchanges = exchanges;
        return this;
    }

    public Optional<PublicKey> key(String kid) {
        PublicKey k = keys.get(kid);
        if (k != null) return Optional.of(k);
        synchronized (fetching) {
            k = keys.get(kid);
            if (k != null) return Optional.of(k);
            Instant now = clock.instant();
            if (lastFruitlessFetch != null
                    && Duration.between(lastFruitlessFetch, now).compareTo(MIN_REFETCH) < 0) return Optional.empty();
            fetch();
            k = keys.get(kid);
            lastFruitlessFetch = k == null ? now : null;
            return Optional.ofNullable(k);
        }
    }

    /**
     * Fetches the keys now, with the failure rules of {@link #key}. Called at daemon startup and by the 60 s
     * background check (T8/T12 wire it), so the hook path is normally a cache read.
     */
    public void refresh() {
        synchronized (fetching) {
            fetch();
            lastFruitlessFetch = null;
        }
    }

    /** False when the last fetch failed; the keys already known stay usable. */
    public boolean reachable() {
        return reachable;
    }

    private void fetch() {
        Instant at = Instant.now();
        long start = System.nanoTime();
        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(certsUri)
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            record(at, start, r.statusCode(), r.body(), null);
            if (r.statusCode() != 200) throw new IOException("certs answered " + r.statusCode());
            keys = parse(JSON.readTree(r.body()));
            reachable = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Handled: the old keys stay, the unknown kid is refused as unreachable.
            reachable = false;
        } catch (java.net.ConnectException | java.net.http.HttpTimeoutException e) {
            record(at, start, 0, null, e.getClass().getSimpleName());
            // Handled: the old keys stay, the unknown kid is refused as unreachable, never accepted.
            reachable = false;
        } catch (IOException | RuntimeException | GeneralSecurityException e) {
            // Handled: the old keys stay, the unknown kid is refused as unreachable, never accepted.
            reachable = false;
        }
    }

    /** One fetch of the keys among the exchanges with Keycloak, when they are recorded. */
    private void record(Instant at, long start, int status, String body, String error) {
        IdpExchanges log = exchanges;
        long millis = (System.nanoTime() - start) / 1_000_000;
        if (log != null) log.record(at, "signing keys", "GET", certsUri, null, status, millis, body, error);
    }

    private static Map<String, PublicKey> parse(JsonNode doc) throws GeneralSecurityException {
        Map<String, PublicKey> out = new HashMap<>();
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        for (JsonNode k : doc.path("keys")) {
            if (!"RSA".equals(k.path("kty").asText())
                    || !"sig".equals(k.path("use").asText("sig"))
                    || k.path("kid").asText("").isBlank()) continue;
            BigInteger n = new BigInteger(1, Jwts.fromBase64url(k.path("n").asText()));
            if (n.bitLength() < 2048) continue;
            BigInteger e = new BigInteger(1, Jwts.fromBase64url(k.path("e").asText()));
            out.put(k.path("kid").asText(), rsa.generatePublic(new RSAPublicKeySpec(n, e)));
        }
        return Map.copyOf(out);
    }
}
