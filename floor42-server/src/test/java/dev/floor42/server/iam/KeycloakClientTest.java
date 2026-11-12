package dev.floor42.server.iam;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.floor42.domain.AgentId;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeycloakClientTest {

    private static final AgentId ADA = new AgentId("ada");
    private static final String ADA_CLIENT = "floor42-agent-ada";
    private static final String DESK = "floor42-desk";
    private static final String API = "floor42-api";
    private static final byte[] DAEMON_SECRET = "daemon-secret-0123".getBytes(UTF_8);
    private static final byte[] API_SECRET = "api-secret-4567".getBytes(UTF_8);

    @TempDir
    Path dataDir;

    FakeKeycloak kc;
    SecretVault vault;
    KeycloakClient client;

    @BeforeEach
    void start() {
        kc = FakeKeycloak.start("floor42");
        kc.registerClient("floor42-daemon", new String(DAEMON_SECRET, UTF_8));
        kc.registerClient(API, new String(API_SECRET, UTF_8));
        kc.registerClient(DESK, null);
        vault = new SecretVault(dataDir);
        vault.put("floor42-daemon", DAEMON_SECRET);
        vault.put(API, API_SECRET);
        client = new KeycloakClient(kc.issuer().toString(), vault, new ObjectMapper());
    }

    @AfterEach
    void stop() {
        kc.close();
    }

    /** A director token (online or offline) that carries the agent client in its audience, as Floor42's setup does. */
    private String directorToken() {
        return client.refresh(kc.refreshTokenFor("director", DESK), DESK).accessToken();
    }

    private String exchanged(Map<String, String> extra) {
        client.ensureAgentClient(ADA, 28800);
        return client.exchange(ADA_CLIENT, vault.get("agent-ada").orElseThrow(), directorToken(), API, extra);
    }

    @Test
    void ensureAlignsTheLifespanOfAClientMadeUnderAnotherMaxTokenHours() {
        client.ensureAgentClient(ADA, 28800);
        assertThat(kc.lifespan(ADA_CLIENT)).isEqualTo("28800");
        kc.setLifespan(ADA_CLIENT, "86400");
        client.ensureAgentClient(ADA, 14400);
        assertThat(kc.lifespan(ADA_CLIENT)).isEqualTo("14400");
    }

    @Test
    void ensureCreatesOnceAndStoresTheSecretInTheVault() {
        var first = client.ensureAgentClient(ADA, 28800);
        client.ensureAgentClient(ADA, 28800);
        assertThat(kc.clients()).containsOnlyOnce(ADA_CLIENT);
        assertThat(first.clientId()).isEqualTo(ADA_CLIENT);
        assertThat(first.enabled()).isTrue();
        assertThat(first.serviceAccountId()).isNotBlank();
        // hex: Keycloak URL-decodes HTTP Basic credentials, so "+" "/" "=" would break the authentication
        assertThat(new String(vault.get("agent-ada").orElseThrow(), UTF_8)).matches("[0-9a-f]{64}");
        assertThat(client.find(ADA))
                .get()
                .extracting(KeycloakClient.AgentClient::clientId)
                .isEqualTo(ADA_CLIENT);
        assertThat(client.find(new AgentId("nobody"))).isEmpty();
    }

    @Test
    void ensureAddsTheAudienceMapperOnTheDeskOnceAndMakesTheExchangeWork() {
        client.ensureAgentClient(ADA, 28800);
        client.ensureAgentClient(ADA, 28800);
        assertThat(kc.mapperAudiences(DESK)).containsExactly(ADA_CLIENT);
        String token = exchanged(Map.of("floor42_launch", "L-1"));
        assertThat(Jwts.parse(token).claims().path("act").path("client_id").asText())
                .isEqualTo(ADA_CLIENT);
        assertThat(Jwts.parse(token).claims().path("floor42").path("launch").asText())
                .isEqualTo("L-1");
    }

    @Test
    void withoutTheAudienceMapperTheExchangeIsRefused() {
        kc.registerClient("floor42-agent-bob", "bobsecret");
        assertThatThrownBy(() -> client.exchange(
                        "floor42-agent-bob", "bobsecret".getBytes(UTF_8), directorToken(), API, Map.of()))
                .isInstanceOf(IdpRefusedException.class)
                .hasMessageContaining("HTTP 400");
    }

    @Test
    void activeIntrospectsAsTheApiClientAndAnAgentSwitchedOffIsInactiveAtOnce() throws Exception {
        String token = exchanged(Map.of());
        assertThat(client.active(token)).isTrue();
        // the agent itself is not in the audience: Keycloak says inactive to it, hence floor42-api introspects
        assertThat(introspectAs(ADA_CLIENT, new String(vault.get("agent-ada").orElseThrow(), UTF_8), token))
                .isFalse();
        client.setEnabled(ADA, false);
        assertThat(client.active(token)).isFalse();
        assertThat(client.find(ADA).orElseThrow().enabled()).isFalse();
        client.setEnabled(ADA, true);
        assertThat(client.active(token)).isTrue();
        assertThat(client.active("not-a-jwt")).isFalse();
    }

    @Test
    void delegationRefreshExchangeAndRevokeKillsDerivedTokens() {
        client.ensureAgentClient(ADA, 28800);
        var tokens = client.refresh(kc.refreshTokenFor("director", DESK), DESK);
        String session =
                client.exchange(ADA_CLIENT, vault.get("agent-ada").orElseThrow(), tokens.accessToken(), API, Map.of());
        assertThat(client.active(session)).isTrue();
        assertThat(tokens.refreshToken()).isNotBlank();
        client.revoke(tokens.refreshToken());
        assertThat(client.active(session)).isFalse();
        assertThatThrownBy(() -> client.refresh(tokens.refreshToken(), DESK)).isInstanceOf(IdpRefusedException.class);
    }

    @Test
    void offlineAccessInAnExchangeIsRefused() {
        client.ensureAgentClient(ADA, 28800);
        byte[] secret = vault.get("agent-ada").orElseThrow();
        String director = directorToken();
        assertThatThrownBy(() ->
                        client.exchange(ADA_CLIENT, secret, director, API, Map.of("scope", "openid offline_access")))
                .isInstanceOf(IdpRefusedException.class);
    }

    @Test
    void rotateSecretReplacesTheVaultEntryAndTheOldOneStopsWorking() {
        client.ensureAgentClient(ADA, 28800);
        byte[] old = vault.get("agent-ada").orElseThrow();
        client.rotateSecret(ADA);
        byte[] fresh = vault.get("agent-ada").orElseThrow();
        assertThat(fresh).isNotEqualTo(old);
        assertThat(new String(fresh, UTF_8)).matches("[0-9a-zA-Z]{20,}");
        String director = directorToken();
        assertThat(client.exchange(ADA_CLIENT, fresh, director, API, Map.of())).isNotBlank();
        assertThatThrownBy(() -> client.exchange(ADA_CLIENT, old, director, API, Map.of()))
                .isInstanceOf(IdpRefusedException.class);
    }

    /** A vault whose writes fail on demand, as a full disk would. */
    static final class FlakyVault extends SecretVault {
        int failures;

        FlakyVault(Path dir) {
            super(dir);
        }

        @Override
        public synchronized void put(String name, byte[] secret) {
            if (failures > 0 && name.startsWith("agent-")) {
                failures--;
                throw new IllegalStateException("disk full");
            }
            super.put(name, secret);
        }
    }

    @Test
    void aRotationWhoseVaultWriteFailedIsHealedByTheNextEnsure() {
        var flaky = new FlakyVault(dataDir);
        flaky.put("floor42-daemon", DAEMON_SECRET);
        flaky.put(API, API_SECRET);
        var c = new KeycloakClient(kc.issuer().toString(), flaky, new ObjectMapper());
        c.ensureAgentClient(ADA, 28800);
        flaky.failures = 2; // the write and its one retry
        assertThatThrownBy(() -> c.rotateSecret(ADA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rotate again");
        String director = directorToken();
        byte[] stale = flaky.get("agent-ada").orElseThrow();
        assertThatThrownBy(() -> c.exchange(ADA_CLIENT, stale, director, API, Map.of()))
                .isInstanceOf(IdpRefusedException.class);
        c.ensureAgentClient(ADA, 28800);
        assertThat(c.exchange(ADA_CLIENT, flaky.get("agent-ada").orElseThrow(), director, API, Map.of()))
                .isNotBlank();
    }

    @Test
    void oneVaultWriteFailureIsRetriedOnce() {
        var flaky = new FlakyVault(dataDir);
        flaky.put("floor42-daemon", DAEMON_SECRET);
        var c = new KeycloakClient(kc.issuer().toString(), flaky, new ObjectMapper());
        c.ensureAgentClient(ADA, 28800);
        flaky.failures = 1;
        c.rotateSecret(ADA);
        assertThat(c.exchange(ADA_CLIENT, flaky.get("agent-ada").orElseThrow(), directorToken(), API, Map.of()))
                .isNotBlank();
    }

    @Test
    void concurrentEnsuresMakeOneClientOneMapperAndAWorkingSecret() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) jobs.add(pool.submit(() -> client.ensureAgentClient(ADA, 28800)));
            for (var j : jobs) j.get();
        } finally {
            pool.shutdown();
        }
        assertThat(kc.clients()).containsOnlyOnce(ADA_CLIENT);
        assertThat(kc.mapperAudiences(DESK)).containsExactly(ADA_CLIENT);
        assertThat(client.exchange(ADA_CLIENT, vault.get("agent-ada").orElseThrow(), directorToken(), API, Map.of()))
                .isNotBlank();
    }

    @Test
    void anAuthorizationCodeIsRedeemedWithItsVerifier() throws Exception {
        String verifier = "v".repeat(50);
        String challenge = Jwts.base64url(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        URI redirect = URI.create("http://127.0.0.1:1/cb");
        var auth = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(
                        HttpRequest.newBuilder(
                                        URI.create(kc.issuer() + "/protocol/openid-connect/auth?client_id=" + DESK
                                                + "&response_type=code&state=s&redirect_uri=" + enc(redirect.toString())
                                                + "&code_challenge=" + challenge + "&code_challenge_method=S256"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        String code = auth.headers().firstValue("Location").orElseThrow().replaceAll(".*code=([^&]+).*", "$1");
        var tokens = client.redeemCode(code, verifier, redirect);
        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.expiresAt()).isAfter(java.time.Instant.now());
        assertThatThrownBy(() -> client.redeemCode(code, verifier, redirect)).isInstanceOf(IdpRefusedException.class);
    }

    @Test
    void downThrowsNeverDefaults() {
        String token = exchanged(Map.of());
        kc.down(true);
        assertThatThrownBy(() -> client.active(token)).isInstanceOf(IdpUnavailableException.class);
        assertThatThrownBy(() -> client.ensureAgentClient(ADA, 28800)).isInstanceOf(IdpUnavailableException.class);
        assertThatThrownBy(() -> client.refresh("r", DESK)).isInstanceOf(IdpUnavailableException.class);
        assertThatThrownBy(() -> client.revoke("r")).isInstanceOf(IdpUnavailableException.class);
        kc.close();
        assertThatThrownBy(() -> client.active(token)).isInstanceOf(IdpUnavailableException.class);
    }

    @Test
    void aRedirectIsNeverFollowed() throws Exception {
        AtomicInteger elsewhere = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/elsewhere", x -> {
            elsewhere.incrementAndGet();
            x.sendResponseHeaders(200, -1);
            x.close();
        });
        server.createContext("/", x -> {
            x.getResponseHeaders().set("Location", "/elsewhere");
            x.sendResponseHeaders(302, -1);
            x.close();
        });
        server.start();
        try {
            var redirecting = new KeycloakClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/floor42", vault, new ObjectMapper());
            assertThatThrownBy(() -> redirecting.refresh("r", DESK)).isInstanceOf(IdpRefusedException.class);
            assertThat(elsewhere).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theAdminTokenIsCachedBetweenCalls() {
        client.ensureAgentClient(ADA, 28800);
        client.setEnabled(ADA, true);
        client.find(ADA);
        long grants = kc.requests().stream()
                .filter(r -> "client_credentials".equals(r.get("grant_type")))
                .count();
        // one for the admin token (cached over four admin calls), one for the secret self-check of ensure
        assertThat(grants).isEqualTo(2);
    }

    @Test
    void noSecretInToStringOrLogs() {
        List<String> lines = new ArrayList<>();
        Logger log = Logger.getLogger(KeycloakClient.class.getName());
        Level before = log.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                lines.add(r.getMessage() + " " + java.util.Arrays.toString(r.getParameters()));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        log.setLevel(Level.ALL);
        log.addHandler(handler);
        try {
            var tokens = client.refresh(kc.refreshTokenFor("director", DESK), DESK);
            String session = exchanged(Map.of());
            client.active(session);
            assertThatThrownBy(() -> client.refresh("bad", DESK))
                    .satisfies(
                            e -> assertThat(e.toString()).doesNotContain("eyJ").doesNotContain("bad"));
            assertThat(tokens.toString()).doesNotContain("eyJ").doesNotContain(tokens.refreshToken());
            String secret = new String(vault.get("agent-ada").orElseThrow(), UTF_8);
            assertThat(lines).as("the logger was captured").isNotEmpty();
            assertThat(String.join("\n", lines)).doesNotContain("eyJ").doesNotContain(secret);
            assertThat(client.find(ADA).orElseThrow().toString()).doesNotContain(secret);
        } finally {
            log.removeHandler(handler);
            log.setLevel(before);
        }
    }

    private boolean introspectAs(String clientId, String secret, String token) throws Exception {
        String basic = java.util.Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(UTF_8));
        var res = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(kc.issuer() + "/protocol/openid-connect/token/introspect"))
                                .header("Authorization", "Basic " + basic)
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString("token=" + enc(token)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        return new ObjectMapper().readTree(res.body()).path("active").asBoolean();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, UTF_8);
    }
}
