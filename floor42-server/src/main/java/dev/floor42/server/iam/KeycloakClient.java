package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.floor42.domain.AgentId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Floor42's side of Keycloak: the user-facing grants (code, refresh, exchange, introspection, revocation) and the few
 * admin calls that manage agent clients. JDK HTTP client (see IdpHttp), no redirect ever followed, every network failure an
 * {@link IdpUnavailableException}, every "no" an {@link IdpRefusedException}. Nothing secret reaches a log or a message:
 * only the operation, the HTTP status and Keycloak's short error code.
 *
 * <p>Secrets used here live in the {@link SecretVault}: {@code floor42-daemon} (admin calls), {@code floor42-api}
 * (introspection) and {@code agent-<id>} (one per agent client).
 */
@ApplicationScoped
public class KeycloakClient {

    private static final String OIDC = "/protocol/openid-connect/";
    private static final String DESK = "floor42-desk";
    private static final String DAEMON = "floor42-daemon";
    private static final String API = "floor42-api";
    private static final String ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Tokens of a grant; {@code toString} masks every one of them. */
    public record Tokens(String accessToken, String refreshToken, String idToken, Instant expiresAt) {
        @Override
        public String toString() {
            return "Tokens[access=***, refresh=***, id=***, expiresAt=" + expiresAt + "]";
        }
    }

    public record AgentClient(String clientId, String internalId, String serviceAccountId, boolean enabled) {}

    private record Admin(String token, Instant until) {}

    private final String issuer;
    private final SecretVault vault;
    private final ObjectMapper json;
    private final IdpHttp wire;
    private final Map<String, Object> locks = new java.util.concurrent.ConcurrentHashMap<>();
    private Admin admin;

    /** The issuer is pinned by KC_HOSTNAME: every URL is derived from it, never from the address a request used. */
    @Inject
    KeycloakClient(IamSettings settings, SecretVault vault, ObjectMapper json, IdpExchanges exchanges) {
        this(settings.issuer().toString(), vault, json, exchanges);
    }

    KeycloakClient(String issuer, SecretVault vault, ObjectMapper json) {
        this(issuer, vault, json, new IdpExchanges());
    }

    KeycloakClient(String issuer, SecretVault vault, ObjectMapper json, IdpExchanges exchanges) {
        this.issuer = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        this.vault = vault;
        this.json = json;
        this.wire = new IdpHttp(json, exchanges);
    }

    // ---- user-facing grants

    public Tokens redeemCode(String code, String verifier, URI redirect) {
        return tokens(form(
                "redeem",
                null,
                Map.of(
                        "grant_type", "authorization_code",
                        "client_id", DESK,
                        "code", code,
                        "code_verifier", verifier,
                        "redirect_uri", redirect.toString())));
    }

    /** For public clients (the desk): no secret. The refresh token rotates, keep the one in the answer. */
    public Tokens refresh(String refreshToken, String clientId) {
        return tokens(form(
                "refresh",
                null,
                Map.of("grant_type", "refresh_token", "client_id", clientId, "refresh_token", refreshToken)));
    }

    /**
     * RFC 8693 by the agent client. {@code subjectToken} must have {@code clientId} in its audience (the mapper
     * {@link #ensureAgentClient} adds on the desk); {@code extra} are further form parameters. Never ask for
     * offline_access here: Keycloak refuses it.
     */
    public String exchange(
            String clientId, byte[] secret, String subjectToken, String audience, Map<String, String> extra) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        body.put("subject_token", subjectToken);
        body.put("subject_token_type", ACCESS_TOKEN);
        body.put("requested_token_type", ACCESS_TOKEN);
        body.put("audience", audience);
        extra.forEach(body::putIfAbsent);
        String token = form("exchange", basic(clientId, secret), body)
                .path("access_token")
                .asText("");
        if (token.isBlank()) throw new IdpRefusedException("Keycloak answered exchange without an access token");
        return token;
    }

    /**
     * Introspection as {@code floor42-api}, not as the agent: Keycloak answers {@code active:false} to any caller that
     * is not in the token's audience, and an agent session token's audience is {@code floor42-api}. Needs the
     * {@code floor42-api} secret in the vault. The answer's claims are not trusted; only the boolean is used.
     */
    public boolean active(String token) {
        byte[] secret =
                vault.get(API).orElseThrow(() -> new IllegalStateException("No floor42-api secret in the vault"));
        return form("introspect", basic(API, secret), Map.of("token", token))
                .path("active")
                .asBoolean(false);
    }

    /**
     * One introspection of a token that is not one: a Keycloak that accepts Floor42's {@code floor42-api} credential
     * answers 200 {@code active:false}; anything else is thrown (a refusal about the caller is a 401).
     * No {@code floor42-api} secret in the vault is an {@link ApiSecretMissingException}.
     */
    public void probeIntrospection() {
        if (vault.get(API).isEmpty()) throw new ApiSecretMissingException();
        active("floor42-start-probe");
    }

    /** RFC 7009 on a delegation's refresh token (issued to the desk): derived tokens become inactive. */
    public void revoke(String refreshToken) {
        form("revoke", null, Map.of("token", refreshToken, "client_id", DESK));
    }

    /** Ends the Keycloak session of the desk's refresh token (the end-session endpoint): the director signed out. */
    public void logout(String refreshToken) {
        form("logout", null, Map.of("client_id", DESK, "refresh_token", refreshToken));
    }

    // ---- admin (floor42-daemon)

    /**
     * Creates the agent client if missing (409 from a concurrent creator counts as "exists"), adds the desk audience
     * mapper if missing, and heals the secret: the vault entry absent, or one that Keycloak no longer accepts (a
     * rotation whose vault write failed), is replaced by a rotation. One extra client_credentials call per ensure.
     * Serialised per agent inside this process.
     */
    public AgentClient ensureAgentClient(AgentId a, int lifespanSeconds) {
        synchronized (lock(a)) {
            return ensure(a, lifespanSeconds);
        }
    }

    private Object lock(AgentId a) {
        return locks.computeIfAbsent(a.value(), k -> new Object());
    }

    private AgentClient ensure(AgentId a, int lifespanSeconds) {
        Optional<AgentClient> existing = find(a);
        if (existing.isEmpty()) {
            String secret = hexSecret();
            ObjectNode body = json.createObjectNode()
                    .put("clientId", clientId(a))
                    .put("protocol", "openid-connect")
                    .put("enabled", true)
                    .put("publicClient", false)
                    .put("serviceAccountsEnabled", true)
                    .put("standardFlowEnabled", false)
                    .put("directAccessGrantsEnabled", false)
                    .put("secret", secret);
            body.putArray("defaultClientScopes").add("floor42-agent");
            body.putObject("attributes")
                    .put("access.token.lifespan", Integer.toString(lifespanSeconds))
                    .put("standard.token.exchange.enabled", "true");
            if (created("create agent client", "/clients", body)) {
                vault.put(vaultName(a), secret.getBytes(StandardCharsets.UTF_8));
            }
        }
        AgentClient client = find(a).orElseThrow(() -> new IdpUnavailableException("agent client not visible", null));
        if (vault.get(vaultName(a)).isEmpty() || (client.enabled() && !secretWorks(client.clientId(), a))) {
            rotateSecret(a);
        }
        ensureAudienceMapper(client.clientId());
        syncLifespan(client, lifespanSeconds);
        return client;
    }

    /**
     * A client made under an older max-token-hours keeps its lifespan until told: Floor42 then refuses its tokens
     * (AgentTokenVerifier caps the lifetime), so the client is aligned on every ensure, at most one PUT.
     */
    private void syncLifespan(AgentClient client, int lifespanSeconds) {
        String path = "/clients/" + client.internalId();
        String now = admin("read lifespan", "GET", path, null)
                .path("attributes")
                .path("access.token.lifespan")
                .asText("");
        if (now.equals(Integer.toString(lifespanSeconds))) return;
        ObjectNode body = json.createObjectNode();
        body.putObject("attributes").put("access.token.lifespan", Integer.toString(lifespanSeconds));
        admin("set lifespan", "PUT", path, body);
    }

    private boolean secretWorks(String clientId, AgentId a) {
        try {
            form(
                    "secret check",
                    basic(clientId, vault.get(vaultName(a)).orElseThrow()),
                    Map.of("grant_type", "client_credentials"));
            return true;
        } catch (IdpRefusedException e) {
            // Handled: Keycloak does not know this secret, the caller rotates it; outages still propagate.
            return false;
        }
    }

    /** POST that treats 409 (it exists already, e.g. a concurrent creator) as a normal answer: false. */
    private boolean created(String op, String path, JsonNode body) {
        try {
            admin(op, "POST", path, body);
            return true;
        } catch (IdpRefusedException e) {
            if (e.status() == 409) return false; // Handled: someone else created it first; the caller re-reads.
            throw e;
        }
    }

    public void setEnabled(AgentId a, boolean on) {
        synchronized (lock(a)) {
            AgentClient c = find(a).orElseThrow(() -> new IllegalStateException("No client for agent " + a));
            admin(
                    "set enabled",
                    "PUT",
                    "/clients/" + c.internalId(),
                    json.createObjectNode().put("enabled", on));
        }
    }

    /**
     * A new secret generated by Keycloak (POST client-secret, never sent by us) and stored in the vault. If the vault
     * write fails twice, Keycloak holds a secret the vault does not: the exception says so, and the next
     * {@link #ensureAgentClient} heals it.
     */
    public void rotateSecret(AgentId a) {
        synchronized (lock(a)) {
            AgentClient c = find(a).orElseThrow(() -> new IllegalStateException("No client for agent " + a));
            String secret = admin("rotate secret", "POST", "/clients/" + c.internalId() + "/client-secret", null)
                    .path("value")
                    .asText("");
            if (secret.isEmpty()) throw new IdpRefusedException("Keycloak answered rotate secret without a secret");
            store(a, secret);
        }
    }

    private void store(AgentId a, String secret) {
        try {
            vault.put(vaultName(a), secret.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException first) {
            try {
                vault.put(vaultName(a), secret.getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException second) {
                second.addSuppressed(first);
                throw new IllegalStateException(
                        "Secret out of sync for agent " + a + " (Keycloak rotated, vault write failed): rotate again",
                        second);
            }
        }
    }

    public Optional<AgentClient> find(AgentId a) {
        JsonNode list = admin("find client", "GET", "/clients?clientId=" + OidcFlow.enc(clientId(a)), null);
        for (JsonNode n : list) {
            if (clientId(a).equals(n.path("clientId").asText())) return Optional.of(describe(n));
        }
        return Optional.empty();
    }

    private AgentClient describe(JsonNode n) {
        String id = n.path("id").asText();
        JsonNode sa = admin("service account", "GET", "/clients/" + id + "/service-account-user", null);
        return new AgentClient(
                n.path("clientId").asText(),
                id,
                sa.path("id").asText(""),
                n.path("enabled").asBoolean(false));
    }

    /** The agent's clientId must be in the director token's audience for the exchange: a mapper on the desk. */
    private void ensureAudienceMapper(String agentClientId) {
        String desk = admin("find desk", "GET", "/clients?clientId=" + DESK, null)
                .path(0)
                .path("id")
                .asText();
        if (desk.isEmpty()) throw new IllegalStateException("The realm has no client " + DESK);
        String name = "aud-" + agentClientId;
        String path = "/clients/" + desk + "/protocol-mappers/models";
        for (JsonNode m : admin("list mappers", "GET", path, null)) {
            if (name.equals(m.path("name").asText())) return;
        }
        ObjectNode mapper = json.createObjectNode()
                .put("name", name)
                .put("protocol", "openid-connect")
                .put("protocolMapper", "oidc-audience-mapper");
        mapper.putObject("config")
                .put("included.client.audience", agentClientId)
                .put("access.token.claim", "true")
                .put("id.token.claim", "false");
        created("add audience mapper", path, mapper);
    }

    private JsonNode admin(String op, String method, String path, JsonNode body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(adminBase() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + adminToken());
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        }
        // A JSON body may hold a client's secret: the exchanges log never sees it.
        return wire.call(op, b, null);
    }

    private synchronized String adminToken() {
        if (admin == null || Instant.now().isAfter(admin.until().minusSeconds(30))) {
            byte[] secret = vault.get(DAEMON)
                    .orElseThrow(() -> new IllegalStateException("No floor42-daemon secret in the vault"));
            JsonNode t = form("admin token", basic(DAEMON, secret), Map.of("grant_type", "client_credentials"));
            admin = new Admin(
                    t.path("access_token").asText(),
                    Instant.now().plusSeconds(t.path("expires_in").asLong(0)));
        }
        return admin.token();
    }

    // ---- plumbing

    private String adminBase() {
        return issuer.replace("/realms/", "/admin/realms/");
    }

    static String clientId(AgentId a) {
        return "floor42-agent-" + a.value();
    }

    static String vaultName(AgentId a) {
        return "agent-" + a.value();
    }

    /**
     * The secret given at creation is hex: Keycloak URL-decodes HTTP Basic credentials, so a base64 secret with "+" "/"
     * "=" broke the login. Rotated secrets are generated by Keycloak (alphanumeric).
     */
    private static String hexSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String basic(String clientId, byte[] secret) {
        String pair = OidcFlow.enc(clientId) + ":" + OidcFlow.enc(new String(secret, StandardCharsets.UTF_8));
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode form(String op, String authorization, Map<String, String> fields) {
        String endpoint = switch (op) {
            case "introspect" -> "token/introspect";
            case "revoke" -> "revoke";
            case "logout" -> "logout";
            default -> "token";
        };
        String body = fields.entrySet().stream()
                .map(e -> OidcFlow.enc(e.getKey()) + "=" + OidcFlow.enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(issuer + OIDC + endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) b.header("Authorization", authorization);
        return wire.call(op, b, body);
    }

    private static Tokens tokens(JsonNode t) {
        String access = t.path("access_token").asText("");
        if (access.isEmpty()) throw new IdpRefusedException("Keycloak answered without an access token");
        return new Tokens(
                access,
                t.hasNonNull("refresh_token") ? t.get("refresh_token").asText() : null,
                t.hasNonNull("id_token") ? t.get("id_token").asText() : null,
                Instant.now().plusSeconds(t.path("expires_in").asLong(0)));
    }
}
