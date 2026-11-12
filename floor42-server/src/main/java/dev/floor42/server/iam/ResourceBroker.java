package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.JsonNode;
import dev.floor42.server.iam.PinnedClients.Pinned;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
import dev.floor42.server.iam.SafeUrls.Target;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Calls an external resource for an agent that must never hold a credential: one Token Exchange per call to exactly
 * the resource's audience, the token checked, one HTTP call made here (no redirect, no cookie, capped), and only the
 * status, a few headers and the capped body come back. The token lives in a local variable of {@link #call} and is
 * scrubbed from anything the resource echoes.
 */
@Singleton
public class ResourceBroker {

    /** Longest life of a resource token; Keycloak's mapper caps it and this class checks it anyway. */
    static final long MAX_TOKEN_SECONDS = 300;

    static final Duration DEADLINE = Duration.ofSeconds(30);

    private static final Set<String> KEPT =
            Set.of("content-type", "content-length", "etag", "last-modified", "retry-after");
    private static final long SKEW = AgentTokenVerifier.SKEW.toSeconds();

    /** The refusal codes the tool shows: RESOURCE_UNKNOWN, RESOURCE_NOT_GRANTED, the SafeUrls ones, IDENTITY_REFUSED (Floor42's decision), IDENTITY_UNAVAILABLE (the provider failed)... */
    public static final class Refusal extends RuntimeException {
        private final String code;

        Refusal(String code, String message) {
            super(message);
            this.code = code;
        }

        Refusal(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Never prints the body: it is the resource's, and the answer builder is the only place that shows it. */
    public record BrokerResult(
            int status, Map<String, String> headers, String body, boolean truncated, String jti, String audience) {
        public BrokerResult {
            headers = headers == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        }

        @Override
        public String toString() {
            return "BrokerResult[status=" + status + ", truncated=" + truncated + ", jti=" + jti + ", audience="
                    + audience + "]";
        }
    }

    /** Where the resource token comes from: Keycloak in production, a stand-in in tests. */
    @FunctionalInterface
    public interface TokenSource {
        String exchange(SessionClaims caller, Resource resource);
    }

    private final ResourceCatalogue catalogue;
    private final SafeUrls urls;
    private final TokenSource tokens;
    private final PinnedClients pins;
    private final Duration timeLimit;

    @Inject
    ResourceBroker(
            ResourceCatalogue catalogue,
            SafeUrls urls,
            PinnedClients pins,
            KeycloakClient keycloak,
            SecretVault vault,
            Delegations d) {
        this(catalogue, urls, pins, DEADLINE, (c, r) -> exchange(keycloak, vault, d, c, r));
    }

    public ResourceBroker(
            ResourceCatalogue catalogue, SafeUrls urls, PinnedClients pins, Duration timeLimit, TokenSource tokens) {
        this.catalogue = catalogue;
        this.urls = urls;
        this.pins = pins;
        this.timeLimit = timeLimit;
        this.tokens = tokens;
    }

    private static String exchange(
            KeycloakClient keycloak, SecretVault vault, Delegations d, SessionClaims c, Resource r) {
        try {
            if (d.isClosed(c.mission()))
                throw new Refusal("IDENTITY_REFUSED", "The mission is completed: no resource for its agents");
            if (d.isRevoked(c.mission())) throw new Refusal("IDENTITY_REFUSED", "The mission's delegation is revoked");
            byte[] secret = vault.get(KeycloakClient.vaultName(c.agent()))
                    .orElseThrow(() -> new Refusal("IDENTITY_UNAVAILABLE", "The agent has no Keycloak secret"));
            Map<String, String> extra = r.scopes().isEmpty() ? Map.of() : Map.of("scope", String.join(" ", r.scopes()));
            return keycloak.exchange(
                    KeycloakClient.clientId(c.agent()), secret, d.subjectTokenFor(c.mission()), r.audience(), extra);
        } catch (Refusal e) {
            throw e;
        } catch (RuntimeException e) {
            // Handled: Keycloak said no or is away, or the vault failed; the agent is told only that identity failed
            throw new Refusal("IDENTITY_UNAVAILABLE", "Keycloak gave no token for " + r.name(), e);
        }
    }

    public BrokerResult call(SessionClaims caller, String resource, String method, String path, String body) {
        Resource r = catalogue
                .find(resource == null ? "" : resource)
                .orElseThrow(() -> new Refusal("RESOURCE_UNKNOWN", "No resource named " + resource));
        if (!catalogue.allowed(caller.agent(), caller.mission(), r.name()))
            throw new Refusal("RESOURCE_NOT_GRANTED", "This agent or mission may not use " + r.name());
        Target target = build(r, method, path);
        Pinned pinned = pins.pin(r, target);
        String token = tokens.exchange(caller, r);
        String jti = checked(token, caller, r);
        String verb = method.toUpperCase(Locale.ROOT);
        HttpRequest.Builder req = HttpRequest.newBuilder(pinned.uri())
                .timeout(timeLimit)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "*/*");
        if (pinned.hostHeader() != null) req.header("Host", pinned.hostHeader());
        boolean sends = body != null && !body.isEmpty() && !verb.equals("GET") && !verb.equals("DELETE");
        if (sends) req.header("Content-Type", "application/json");
        req.method(verb, sends ? BodyPublishers.ofString(body) : BodyPublishers.noBody());
        return fetch(pinned.client(), req.build(), r, token, jti);
    }

    /** The whole answer, body included, within the deadline: a server that drips bytes does not hold the session. */
    private BrokerResult fetch(HttpClient client, HttpRequest req, Resource r, String token, String jti) {
        CompletableFuture<HttpResponse<byte[]>> sent =
                client.sendAsync(req, info -> new CappedBody(r.maxResponseBytes() + 1));
        try {
            return result(
                    sent.orTimeout(timeLimit.toNanos(), TimeUnit.NANOSECONDS).get(), r, token, jti);
        } catch (ExecutionException e) {
            // Handled: too slow or no answer; the call is abandoned, the message never names the token
            sent.cancel(true);
            boolean slow = e.getCause() instanceof TimeoutException;
            throw new Refusal(
                    "RESOURCE_UNREACHABLE", r.name() + (slow ? " did not answer in time" : " did not answer"), e);
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller; the call is refused
            Thread.currentThread().interrupt();
            sent.cancel(true);
            throw new Refusal("RESOURCE_UNREACHABLE", r.name() + " call was interrupted", e);
        }
    }

    private Target build(Resource r, String method, String path) {
        try {
            return urls.resolve(r, method, path);
        } catch (IllegalArgumentException e) {
            // Handled: the code is the message
            throw new Refusal(e.getMessage(), addressRefusal(e.getMessage(), r, method, path), e);
        }
    }

    /** Says what was refused; only the method and the path without its query, never a body or a token. */
    private static String addressRefusal(String code, Resource r, String method, String path) {
        String bare = path == null ? "" : path.split("[?]", 2)[0].replaceAll("[^!-~]", "?");
        if (bare.length() > 80) bare = bare.substring(0, 80) + "...";
        return switch (code) {
            case "METHOD_NOT_ALLOWED" ->
                (method == null ? "No method" : method.replaceAll("[^A-Za-z]", "?")) + " is not allowed for "
                        + r.name();
            case "PATH_NOT_ALLOWED" -> bare + " is outside the paths allowed for " + r.name();
            default -> "The address is not allowed for " + r.name();
        };
    }

    /** The token Floor42 got is used only if it is what was asked for; returns its jti. */
    static String checked(String jwt, SessionClaims caller, Resource r) {
        JsonNode claims;
        try {
            claims = Jwts.parse(jwt).claims();
        } catch (RuntimeException e) {
            // Handled: not a token; refused
            throw new Refusal("IDENTITY_UNAVAILABLE", "Keycloak gave an unreadable token", e);
        }
        JsonNode aud = claims.path("aud");
        boolean audience = aud.isTextual()
                ? aud.asText().equals(r.audience())
                : aud.isArray() && aud.size() == 1 && aud.get(0).asText().equals(r.audience());
        long life = claims.path("exp").asLong(0) - claims.path("iat").asLong(0);
        boolean acts = ("floor42-agent-" + caller.agent().value())
                .equals(claims.path("act").path("client_id").asText());
        if (!audience
                || life <= 0
                || life > MAX_TOKEN_SECONDS
                || !acts
                || claims.path("jti").asText("").isBlank())
            throw new Refusal("IDENTITY_UNAVAILABLE", "Keycloak gave a token that is not for " + r.name());
        if (claims.path("exp").asLong() + SKEW < System.currentTimeMillis() / 1000)
            throw new Refusal("IDENTITY_UNAVAILABLE", "Keycloak gave an expired token");
        return claims.path("jti").asText();
    }

    private static BrokerResult result(HttpResponse<byte[]> res, Resource r, String token, String jti) {
        Map<String, String> headers = new LinkedHashMap<>();
        res.headers().map().forEach((k, v) -> {
            if (KEPT.contains(k.toLowerCase(Locale.ROOT)) && !v.isEmpty())
                headers.put(k.toLowerCase(Locale.ROOT), scrub(v.getFirst(), token));
        });
        byte[] bytes = res.body();
        boolean cut = bytes.length > r.maxResponseBytes();
        String body = cutAndScrub(bytes, r.maxResponseBytes(), token);
        return new BrokerResult(res.statusCode(), headers, body, cut, jti, r.audience());
    }

    /** The body for the agent: the token scrubbed first, then cut, then any token prefix left at the cut masked. */
    static String cutAndScrub(byte[] bytes, int max, String token) {
        String text = scrub(new String(bytes, StandardCharsets.UTF_8), token);
        String body = text.length() > max ? text.substring(0, max) : text;
        // A cut may leave the start of the token at the end: mask any trailing prefix of 8 characters or more.
        for (int n = Math.min(token.length() - 1, body.length()); n >= 8; n--)
            if (body.endsWith(token.substring(0, n))) return body.substring(0, body.length() - n) + "[redacted]";
        return body;
    }

    /** A resource that echoes the Authorization header must not hand the token to the agent. */
    private static String scrub(String text, String token) {
        return text.replace(token, "[redacted]");
    }
}
