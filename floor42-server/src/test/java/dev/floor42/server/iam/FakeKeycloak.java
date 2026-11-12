package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in for Keycloak in tests: the endpoints Floor42 uses, RS256 tokens, a rotatable key and the knobs that make
 * it misbehave. It checks what the real one checks for the flows under test (PKCE, secrets, a live subject token).
 */
public final class FakeKeycloak implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String API = "floor42-api";
    private static final Set<String> SECRET_PARAMS =
            Set.of("client_secret", "subject_token", "token", "refresh_token", "code", "code_verifier");

    private record Client(
            String id, String clientId, boolean publicClient, boolean enabled, String secret, boolean exchange) {
        Client withEnabled(boolean on) {
            return new Client(id, clientId, publicClient, on, secret, exchange);
        }

        Client withSecret(String value) {
            return new Client(id, clientId, publicClient, enabled, value, exchange);
        }
    }

    private record Code(
            String clientId,
            String redirect,
            String challenge,
            String subject,
            String nonce,
            String family,
            Instant authTime) {}

    private record Refresh(String subject, String clientId, String family) {}

    private final String realm;
    private final HttpServer server;
    private final Map<String, KeyPair> keys = new LinkedHashMap<>();
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    /** access.token.lifespan per client id, as the admin API stores it (a string, absent until set). */
    private final Map<String, String> lifespans = new ConcurrentHashMap<>();

    private final Map<String, Code> codes = new ConcurrentHashMap<>();
    private final Map<String, Refresh> refreshTokens = new ConcurrentHashMap<>();
    private final Map<String, String> jtiFamily = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> mappers = new ConcurrentHashMap<>();
    private final Set<String> liveJtis = ConcurrentHashMap.newKeySet();
    private final List<Map<String, String>> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile String currentKid;
    private volatile boolean down;
    private final java.util.Set<String> refusedIntrospections = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile String idTokenNonce;
    private volatile String omittedClaim;
    private volatile Duration offset = Duration.ZERO;
    private int rotations;
    private volatile boolean ignorePromptLogin;
    private volatile int revokeStatus = 200;
    private volatile int logoutStatus = 200;
    private volatile String subjectOverride;
    /** The browser's single sign-on session: flows inside it share one offline session (measured on Keycloak 26.8.1). */
    private final String ssoFamily = UUID.randomUUID().toString();

    private final Instant ssoAuthTime = Instant.now().minus(Duration.ofHours(1));

    private FakeKeycloak(String realm) throws IOException {
        this.realm = realm;
        addKey();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public static FakeKeycloak start(String realm) {
        try {
            return new FakeKeycloak(realm);
        } catch (IOException e) {
            throw new IllegalStateException("cannot start the fake Keycloak", e);
        }
    }

    public URI issuer() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/realms/" + realm);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- test knobs

    public void rotateKey() {
        addKey();
    }

    /** The nonce the next id_tokens carry instead of the one the sign-in sent (null: the right one). */
    /** True: {@code prompt=login} is ignored, as when a proxy dropped it (the id_token keeps the old auth_time). */
    public void ignorePromptLogin(boolean value) {
        ignorePromptLogin = value;
    }

    /** The HTTP status the end-session endpoint answers (200 unless a test says otherwise). */
    public void logoutStatus(int status) {
        logoutStatus = status;
    }

    /** The HTTP status the revocation endpoint answers (200 unless a test says otherwise; the token is revoked on 200). */
    public void revokeStatus(int status) {
        revokeStatus = status;
    }

    /** The user the next authorizations sign in (null: the requested login_hint, else "director"). */
    public void signInAs(String subject) {
        subjectOverride = subject;
    }

    /** The next ID tokens carry no claim of this name. */
    public void omitIdTokenClaim(String name) {
        omittedClaim = name;
    }

    public void idTokenNonce(String value) {
        idTokenNonce = value;
    }

    private String idToken(String subject, String clientId, String nonce, Instant authTime) {
        ObjectNode header =
                JSON.createObjectNode().put("alg", "RS256").put("typ", "JWT").put("kid", currentKid);
        ObjectNode body = JSON.createObjectNode()
                .put("iss", issuer().toString())
                .put("sub", subject)
                .put("aud", clientId)
                .put("azp", clientId)
                .put("nonce", nonce)
                .put("auth_time", authTime.getEpochSecond())
                .put("iat", now().getEpochSecond())
                .put("exp", now().plus(Duration.ofHours(1)).getEpochSecond());
        if (omittedClaim != null) body.remove(omittedClaim);
        return Jwts.sign(header, body, keys.get(currentKid).getPrivate());
    }

    private volatile Duration delay = Duration.ZERO;

    /** Every answer takes this long: a slow Keycloak. */
    public void delay(Duration value) {
        delay = value;
    }

    /** Introspection of this token is answered 400 (a Keycloak that answers but refuses). */
    public void refuseIntrospectionOf(String token) {
        refusedIntrospections.add(token);
    }

    public void acceptAllIntrospections() {
        refusedIntrospections.clear();
    }

    public void down(boolean value) {
        down = value;
    }

    public void disableClient(String clientId) {
        clients.computeIfPresent(clientId, (k, c) -> c.withEnabled(false));
    }

    /** A client registered without the admin API: public when {@code secret} is null. Exchange is allowed. */
    public void registerClient(String clientId, String secret) {
        clients.put(clientId, new Client(UUID.randomUUID().toString(), clientId, secret == null, true, secret, true));
    }

    /** The lifespan attribute of a client, or "" when none was set. */
    public String lifespan(String clientId) {
        Client c = clients.get(clientId);
        return c == null ? "" : lifespans.getOrDefault(c.id, "");
    }

    /** Sets a client's lifespan as an administrator would in the console. */
    public void setLifespan(String clientId, String seconds) {
        lifespans.put(clients.get(clientId).id, seconds);
    }

    public List<String> clients() {
        return clients.keySet().stream().sorted().toList();
    }

    /** The audiences the audience mappers of a client add to the tokens it gets. */
    public List<String> mapperAudiences(String clientId) {
        return audiencesOf(clientId);
    }

    /** A refresh token as an offline sign-in of {@code subject} on the public client {@code clientId} gives it. */
    public String refreshTokenFor(String subject, String clientId) {
        return newRefresh(subject, clientId, UUID.randomUUID().toString());
    }

    private List<String> audiencesOf(String clientId) {
        return mappers.getOrDefault(clientId, List.of()).stream()
                .map(m -> m.path("config").path("included.client.audience").asText())
                .toList();
    }

    public void revokeAll() {
        liveJtis.clear();
        refreshTokens.clear();
        jtiFamily.clear();
    }

    public void clockOffset(Duration value) {
        offset = value;
    }

    /** Form parameters of every request received, the secret ones replaced by {@code [masked]}. */
    public List<Map<String, String>> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    /** A director's token as a sign-in would give it, for tests that start from one. */
    public String directorToken(String subject) {
        return issue(subject, "floor42-web", API, null, null, Duration.ofHours(1));
    }

    /** The exchange without the HTTP round trip or the client authentication. */
    public String exchangeForTest(String clientId, String subjectToken, Map<String, String> params) {
        var parsed = Jwts.parse(subjectToken);
        if (!liveJtis.contains(parsed.claims().path("jti").asText()) || !signedByAKnownKey(parsed))
            throw new IllegalArgumentException("invalid_grant: subject token not active");
        String audience = params.getOrDefault("audience", API);
        ObjectNode act = JSON.createObjectNode().put("sub", "sa-" + clientId).put("client_id", clientId);
        ObjectNode ctx = JSON.createObjectNode();
        ctx.put("launch", params.getOrDefault("floor42_launch", ""));
        ctx.put("mission", params.getOrDefault("floor42_mission", ""));
        ctx.put("ticket", params.getOrDefault("floor42_ticket", ""));
        Duration life = API.equals(audience) ? Duration.ofHours(1) : Duration.ofSeconds(300);
        String token = issue(parsed.claims().path("sub").asText(), clientId, audience, act, ctx, life);
        String family = jtiFamily.get(parsed.claims().path("jti").asText());
        return family == null ? token : linked(token, family);
    }

    /** The token, remembered as derived from a delegation (refresh token family): revoking that kills it. */
    private String linked(String token, String family) {
        jtiFamily.put(Jwts.parse(token).claims().path("jti").asText(), family);
        return token;
    }

    /** The token with its claims changed by {@code edit}, signed again with the current key (a hostile issuer's work). */
    public String forge(String token, java.util.function.Consumer<ObjectNode> edit) {
        var parsed = Jwts.parse(token);
        ObjectNode body = ((ObjectNode) parsed.claims()).deepCopy();
        edit.accept(body);
        return Jwts.sign(parsed.header(), body, keys.get(currentKid).getPrivate());
    }

    // ---- tokens

    private Instant now() {
        return Instant.now().plus(offset);
    }

    private void addKey() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            currentKid = "fake-" + (++rotations);
            keys.put(currentKid, g.generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean signedByAKnownKey(Jwts.Parsed jwt) {
        KeyPair pair = keys.get(jwt.header().path("kid").asText());
        return pair != null && Jwts.verify(jwt, pair.getPublic());
    }

    private String issue(String sub, String azp, String aud, JsonNode act, JsonNode ctx, Duration life) {
        String jti = UUID.randomUUID().toString();
        liveJtis.add(jti);
        ObjectNode header =
                JSON.createObjectNode().put("alg", "RS256").put("typ", "JWT").put("kid", currentKid);
        ObjectNode body = JSON.createObjectNode()
                .put("iss", issuer().toString())
                .put("sub", sub)
                .put("azp", azp)
                .put("iat", now().getEpochSecond())
                .put("exp", now().plus(life).getEpochSecond())
                .put("jti", jti);
        List<String> extra = audiencesOf(azp);
        if (extra.isEmpty()) {
            body.put("aud", aud);
        } else {
            ArrayNode all = body.putArray("aud").add(aud);
            extra.forEach(all::add);
        }
        if (act != null) body.set("act", act);
        if (ctx != null) body.set("floor42", ctx);
        return Jwts.sign(header, body, keys.get(currentKid).getPrivate());
    }

    // ---- HTTP

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            // Handled: a test is stopping the fake; the answer goes out at once
            Thread.currentThread().interrupt();
        }
    }

    private void handle(HttpExchange x) throws IOException {
        try {
            String path = x.getRequestURI().getPath();
            Map<String, String> form = x.getRequestMethod().equals("POST") && !path.contains("/admin/")
                    ? parseForm(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    : parseForm(x.getRequestURI().getRawQuery());
            record(path, form);
            if (!delay.isZero()) sleep(delay);
            if (down) {
                send(x, 503, "{\"error\":\"down\"}");
            } else if (path.contains("/admin/realms/")) {
                admin(x, path);
            } else {
                oidc(x, path, form);
            }
        } catch (IllegalArgumentException e) {
            send(x, 400, "{\"error\":\"invalid_request\",\"error_description\":\"" + e.getMessage() + "\"}");
        } finally {
            x.close();
        }
    }

    private void record(String path, Map<String, String> form) {
        Map<String, String> seen = new LinkedHashMap<>();
        form.forEach((k, v) -> seen.put(k, SECRET_PARAMS.contains(k) ? "[masked]" : v));
        seen.put("_path", path);
        requests.add(seen);
    }

    private void oidc(HttpExchange x, String path, Map<String, String> form) throws IOException {
        String base = "/realms/" + realm;
        String protocol = base + "/protocol/openid-connect";
        if (path.equals(base + "/.well-known/openid-configuration")) {
            String p = issuer() + "/protocol/openid-connect";
            send(
                    x,
                    200,
                    JSON.createObjectNode()
                            .put("issuer", issuer().toString())
                            .put("authorization_endpoint", p + "/auth")
                            .put("token_endpoint", p + "/token")
                            .put("jwks_uri", p + "/certs")
                            .put("introspection_endpoint", p + "/token/introspect")
                            .put("revocation_endpoint", p + "/revoke")
                            .toString());
        } else if (path.equals(protocol + "/certs")) {
            send(x, 200, jwks().toString());
        } else if (path.equals(protocol + "/auth")) {
            authorize(x, form);
        } else if (path.equals(protocol + "/token")) {
            send(x, 200, token(x, form).toString());
        } else if (path.equals(protocol + "/token/introspect")) {
            if (refusedIntrospections.contains(form.getOrDefault("token", ""))) {
                send(x, 400, "{\"error\":\"invalid_request\"}");
                return;
            }
            String caller;
            try {
                caller = authenticate(x, form);
            } catch (IllegalArgumentException e) {
                // As Keycloak answers a caller it does not know: 401, never a verdict on the token
                send(x, 401, "{\"error\":\"invalid_client\"}");
                return;
            }
            send(x, 200, introspectAs(caller, form.getOrDefault("token", "")).toString());
        } else if (path.equals(protocol + "/logout")) {
            revoke(form.getOrDefault("refresh_token", ""));
            send(x, logoutStatus, "");
        } else if (path.equals(protocol + "/revoke")) {
            if (revokeStatus == 200) revoke(form.getOrDefault("token", ""));
            send(x, revokeStatus, revokeStatus == 200 ? "" : "{\"error\":\"invalid_request\"}");
        } else {
            send(x, 404, "{\"error\":\"not found\"}");
        }
    }

    private ObjectNode jwks() {
        ArrayNode arr = JSON.createArrayNode();
        RSAPublicKey pub = (RSAPublicKey) keys.get(currentKid).getPublic();
        arr.addObject()
                .put("kty", "RSA")
                .put("kid", currentKid)
                .put("use", "sig")
                .put("alg", "RS256")
                .put("n", unsigned(pub.getModulus()))
                .put("e", unsigned(pub.getPublicExponent()));
        return JSON.createObjectNode().set("keys", arr);
    }

    private static String unsigned(BigInteger n) {
        byte[] b = n.toByteArray();
        return Jwts.base64url(b[0] == 0 ? java.util.Arrays.copyOfRange(b, 1, b.length) : b);
    }

    private void authorize(HttpExchange x, Map<String, String> q) throws IOException {
        String redirect = q.getOrDefault("redirect_uri", "");
        if (redirect.isEmpty() || !"S256".equals(q.getOrDefault("code_challenge_method", "S256")))
            throw new IllegalArgumentException("redirect_uri and S256 are required");
        String code = UUID.randomUUID().toString();
        // prompt=login: the password is typed again, which opens a new SSO session (and so a new offline session).
        boolean again = "login".equals(q.get("prompt")) && !ignorePromptLogin;
        codes.put(
                code,
                new Code(
                        q.get("client_id"),
                        redirect,
                        q.get("code_challenge"),
                        subjectOverride != null ? subjectOverride : q.getOrDefault("login_hint", "director"),
                        q.getOrDefault("nonce", ""),
                        again ? UUID.randomUUID().toString() : ssoFamily,
                        again ? now() : ssoAuthTime));
        String sep = redirect.contains("?") ? "&" : "?";
        x.getResponseHeaders()
                .set(
                        "Location",
                        redirect + sep + "code=" + code + "&state="
                                + URLEncoder.encode(q.getOrDefault("state", ""), StandardCharsets.UTF_8));
        x.sendResponseHeaders(302, -1);
    }

    private ObjectNode token(HttpExchange x, Map<String, String> f) {
        String clientId = authenticate(x, f);
        String grant = f.getOrDefault("grant_type", "");
        ObjectNode out = JSON.createObjectNode().put("token_type", "Bearer");
        switch (grant) {
            case "authorization_code" -> {
                Code c = codes.remove(f.getOrDefault("code", ""));
                if (c == null || !c.clientId.equals(clientId) || !c.redirect.equals(f.get("redirect_uri")))
                    throw new IllegalArgumentException("invalid_grant: code");
                if (!pkceMatches(c.challenge, f.getOrDefault("code_verifier", "")))
                    throw new IllegalArgumentException("invalid_grant: PKCE verification failed");
                String family = c.family;
                out.put(
                        "access_token",
                        linked(issue(c.subject, clientId, API, null, null, Duration.ofHours(1)), family));
                out.put("refresh_token", newRefresh(c.subject, clientId, family));
                out.put(
                        "id_token",
                        idToken(c.subject, clientId, idTokenNonce != null ? idTokenNonce : c.nonce, c.authTime));
            }
            case "refresh_token" -> {
                Refresh held = refreshTokens.remove(f.getOrDefault("refresh_token", ""));
                if (held == null || !held.clientId.equals(clientId))
                    throw new IllegalArgumentException("invalid_grant");
                out.put(
                        "access_token",
                        linked(issue(held.subject, clientId, API, null, null, Duration.ofHours(1)), held.family));
                out.put("refresh_token", newRefresh(held.subject, clientId, held.family));
            }
            case "client_credentials" ->
                out.put("access_token", issue("sa-" + clientId, clientId, API, null, null, Duration.ofHours(1)));
            case EXCHANGE -> out.put("access_token", exchange(clientId, f));
            default -> throw new IllegalArgumentException("unsupported_grant_type: " + grant);
        }
        out.put(
                "expires_in",
                Jwts.parse(out.get("access_token").asText())
                                .claims()
                                .path("exp")
                                .asLong()
                        - now().getEpochSecond());
        return out;
    }

    private String newRefresh(String subject, String clientId, String family) {
        String r = UUID.randomUUID().toString();
        refreshTokens.put(r, new Refresh(subject, clientId, family));
        return r;
    }

    /** The exchange over HTTP, with the two rules Keycloak adds: exchange enabled, requester in the token's aud. */
    private String exchange(String clientId, Map<String, String> f) {
        if (!clients.get(clientId).exchange) throw new IllegalArgumentException("unauthorized_client: no exchange");
        if (f.getOrDefault("scope", "").contains("offline_access"))
            throw new IllegalArgumentException("invalid_scope: Scope offline_access not allowed for token exchange");
        String subject = f.getOrDefault("subject_token", "");
        if (!audienceOf(Jwts.parse(subject).claims()).contains(clientId))
            throw new IllegalArgumentException("access_denied: Client is not within the token audience");
        return exchangeForTest(clientId, subject, f);
    }

    private static List<String> audienceOf(JsonNode claims) {
        JsonNode aud = claims.path("aud");
        List<String> out = new ArrayList<>();
        if (aud.isArray()) aud.forEach(a -> out.add(a.asText()));
        else if (aud.isTextual()) out.add(aud.asText());
        return out;
    }

    private static boolean pkceMatches(String challenge, String verifier) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return challenge != null && challenge.equals(Jwts.base64url(sha));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The client of a token request: a registered, enabled one, with its secret unless it is a public client. */
    private String authenticate(HttpExchange x, Map<String, String> f) {
        String clientId = f.get("client_id");
        String secret = f.get("client_secret");
        String basic = x.getRequestHeaders().getFirst("Authorization");
        if (basic != null && basic.startsWith("Basic ")) {
            String[] p =
                    new String(Base64.getDecoder().decode(basic.substring(6)), StandardCharsets.UTF_8).split(":", 2);
            clientId = URLDecoder.decode(p[0], StandardCharsets.UTF_8);
            secret = p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : null;
        }
        Client c = clientId == null ? null : clients.get(clientId);
        if (c == null || !c.enabled || (!c.publicClient && !c.secret.equals(secret)))
            throw new IllegalArgumentException("invalid_client");
        return clientId;
    }

    /** Introspection as Keycloak 26.8 answers it: inactive to any caller that is not in the token's audience. */
    private ObjectNode introspectAs(String caller, String token) {
        ObjectNode out = introspect(token);
        if (out.path("active").asBoolean() && !audienceOf(out.path("claims")).contains(caller))
            return JSON.createObjectNode().put("active", false);
        return out;
    }

    private ObjectNode introspect(String token) {
        ObjectNode out = JSON.createObjectNode().put("active", false);
        try {
            var p = Jwts.parse(token);
            Client c = clients.get(p.claims().path("azp").asText());
            if (signedByAKnownKey(p)
                    && (liveJtis.contains(p.claims().path("jti").asText())
                            || "floor42-daemon".equals(p.claims().path("azp").asText()))
                    && p.claims().path("exp").asLong() > now().getEpochSecond()
                    && (c == null || c.enabled)) {
                out.put("active", true);
                out.set("claims", p.claims());
            }
        } catch (IllegalArgumentException e) {
            // Handled: an unreadable token is simply inactive, which is the answer introspection gives.
            out.put("active", false);
        }
        return out;
    }

    private void revoke(String token) {
        Refresh held = refreshTokens.remove(token);
        if (held != null) {
            // RFC 7009 in Keycloak: the delegation dies, and so does every token derived from it.
            refreshTokens.values().removeIf(r -> r.family.equals(held.family));
            jtiFamily.entrySet().removeIf(e -> {
                boolean mine = e.getValue().equals(held.family);
                if (mine) liveJtis.remove(e.getKey());
                return mine;
            });
            return;
        }
        try {
            liveJtis.remove(Jwts.parse(token).claims().path("jti").asText());
        } catch (IllegalArgumentException e) {
            // Handled: revoking an unreadable token is a no-op, as in Keycloak.
        }
    }

    // ---- admin

    private void admin(HttpExchange x, String path) throws IOException {
        String bearer = x.getRequestHeaders().getFirst("Authorization");
        if (bearer == null
                || !bearer.startsWith("Bearer ")
                || !introspect(bearer.substring(7)).path("active").asBoolean()) {
            send(x, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        String tail = path.substring(("/admin/realms/" + realm).length());
        String method = x.getRequestMethod();
        if (tail.equals("/clients") && method.equals("GET")) {
            String wanted = parseForm(x.getRequestURI().getRawQuery()).get("clientId");
            ArrayNode arr = JSON.createArrayNode();
            clients.values().stream()
                    .filter(c -> wanted == null || c.clientId.equals(wanted))
                    .forEach(c -> arr.add(describe(c)));
            send(x, 200, arr.toString());
        } else if (tail.equals("/clients") && method.equals("POST")) {
            JsonNode in = JSON.readTree(x.getRequestBody());
            if (clients.containsKey(in.path("clientId").asText())) {
                send(x, 409, "{\"errorMessage\":\"Client already exists\"}");
                return;
            }
            Client c = new Client(
                    UUID.randomUUID().toString(),
                    in.path("clientId").asText(),
                    in.path("publicClient").asBoolean(false),
                    in.path("enabled").asBoolean(true),
                    in.path("secret").asText(secret()),
                    exchangeOn(in, false));
            clients.put(c.clientId, c);
            String life = in.path("attributes").path("access.token.lifespan").asText("");
            if (!life.isEmpty()) lifespans.put(c.id, life);
            x.getResponseHeaders()
                    .set(
                            "Location",
                            issuer().resolve("/admin/realms/" + realm + "/clients/" + c.id)
                                    .toString());
            send(x, 201, "");
        } else {
            adminOne(x, tail, method);
        }
    }

    private void adminOne(HttpExchange x, String tail, String method) throws IOException {
        String[] seg = tail.split("/");
        Client c = seg.length < 3
                ? null
                : clients.values().stream()
                        .filter(k -> k.id.equals(seg[2]))
                        .findFirst()
                        .orElse(null);
        if (c == null) {
            send(x, 404, "{\"error\":\"not found\"}");
        } else if (seg.length == 3 && method.equals("GET")) {
            ObjectNode one = describe(c);
            String life = lifespans.get(c.id);
            if (life != null) one.putObject("attributes").put("access.token.lifespan", life);
            send(x, 200, one.toString());
        } else if (seg.length == 3 && method.equals("PUT")) {
            JsonNode in = JSON.readTree(x.getRequestBody());
            String life = in.path("attributes").path("access.token.lifespan").asText("");
            if (!life.isEmpty()) lifespans.put(c.id, life);
            clients.put(
                    c.clientId,
                    new Client(
                            c.id,
                            c.clientId,
                            c.publicClient,
                            in.path("enabled").asBoolean(c.enabled),
                            in.path("secret").asText(c.secret),
                            exchangeOn(in, c.exchange)));
            send(x, 204, "");
        } else if (seg.length == 5 && seg[3].equals("protocol-mappers") && method.equals("GET")) {
            send(
                    x,
                    200,
                    JSON.valueToTree(mappers.getOrDefault(c.clientId, List.of()))
                            .toString());
        } else if (seg.length == 5 && seg[3].equals("protocol-mappers") && method.equals("POST")) {
            JsonNode mapper = JSON.readTree(x.getRequestBody());
            var list = mappers.computeIfAbsent(c.clientId, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
            if (list.stream().anyMatch(m -> m.path("name").equals(mapper.path("name")))) {
                send(x, 409, "{\"errorMessage\":\"Protocol mapper exists with same name\"}");
                return;
            }
            list.add(mapper);
            send(x, 201, "");
        } else if (seg.length == 4 && seg[3].equals("client-secret") && method.equals("POST")) {
            Client renewed = c.withSecret(secret());
            clients.put(c.clientId, renewed);
            send(
                    x,
                    200,
                    JSON.createObjectNode()
                            .put("type", "secret")
                            .put("value", renewed.secret)
                            .toString());
        } else if (seg.length == 4 && seg[3].equals("service-account-user") && method.equals("GET")) {
            send(
                    x,
                    200,
                    JSON.createObjectNode()
                            .put("id", "sa-" + c.clientId)
                            .put("username", "service-account-" + c.clientId)
                            .toString());
        } else {
            send(x, 404, "{\"error\":\"not found\"}");
        }
    }

    private static boolean exchangeOn(JsonNode in, boolean fallback) {
        JsonNode v = in.path("attributes").path("standard.token.exchange.enabled");
        return v.isMissingNode() ? fallback : v.asText().equals("true");
    }

    private static ObjectNode describe(Client c) {
        return JSON.createObjectNode()
                .put("id", c.id)
                .put("clientId", c.clientId)
                .put("enabled", c.enabled)
                .put("publicClient", c.publicClient);
    }

    private static String secret() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ---- plumbing

    private static Map<String, String> parseForm(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            out.put(k, i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) x.getResponseBody().write(bytes);
    }
}
