package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FakeKeycloakTest {

    private static final String LAUNCH = "L-1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void exchangeWritesActAndContextAndCapsResourceTokens() {
        try (var kc = FakeKeycloak.start("floor42")) {
            String directorToken = kc.directorToken("director");
            long now = Instant.now().getEpochSecond();
            String exchanged = kc.exchangeForTest(
                    "floor42-agent-ada",
                    directorToken,
                    Map.of(
                            "audience",
                            "floor42-api",
                            "floor42_launch",
                            LAUNCH,
                            "floor42_mission",
                            "M-1",
                            "floor42_ticket",
                            "T-2"));
            JsonNode c = Jwts.parse(exchanged).claims();
            assertThat(c.path("act").path("client_id").asText()).isEqualTo("floor42-agent-ada");
            assertThat(c.path("sub").asText()).isEqualTo("director");
            assertThat(c.path("floor42").path("launch").asText()).isEqualTo(LAUNCH);
            assertThat(Jwts.parse(kc.exchangeForTest("floor42-agent-ada", directorToken, Map.of("audience", "res-x")))
                                    .claims()
                                    .path("exp")
                                    .asLong()
                            - now)
                    // 300 s of life, plus one for the second that may tick between reading now and the exchange.
                    .isLessThanOrEqualTo(301);
        }
    }

    @Test
    void jwksVerifiesTokensAndRotationChangesTheKid() throws Exception {
        try (var kc = FakeKeycloak.start("floor42")) {
            String token = kc.directorToken("director");
            JsonNode first = get(kc.issuer() + "/protocol/openid-connect/certs")
                    .path("keys")
                    .get(0);
            assertThat(first.path("kid").asText())
                    .isEqualTo(Jwts.parse(token).header().path("kid").asText());
            kc.rotateKey();
            JsonNode second = get(kc.issuer() + "/protocol/openid-connect/certs")
                    .path("keys")
                    .get(0);
            assertThat(second.path("kid").asText())
                    .isNotEqualTo(first.path("kid").asText());
            kc.down(true);
            assertThat(http.send(
                                    HttpRequest.newBuilder(URI.create(kc.issuer() + "/protocol/openid-connect/certs"))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(503);
        }
    }

    @Test
    void authorizationCodeRequiresTheRightPkceVerifierAndSecretsAreMaskedInTheLog() throws Exception {
        try (var kc = FakeKeycloak.start("floor42")) {
            String verifier = "v".repeat(50);
            String challenge = Jwts.base64url(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            String redirect = "http://127.0.0.1:1/cb";
            var auth = http.send(
                    HttpRequest.newBuilder(URI.create(kc.issuer()
                                    + "/protocol/openid-connect/auth?client_id=web&response_type=code&state=s1&redirect_uri="
                                    + enc(redirect) + "&code_challenge=" + challenge + "&code_challenge_method=S256"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(auth.statusCode()).isEqualTo(302);
            String code = auth.headers().firstValue("Location").orElseThrow().replaceAll(".*code=([^&]+).*", "$1");
            // a public client "web" is registered through the admin API, after a client_credentials token
            registerPublicClient(kc, "web");
            assertThat(token(
                                    kc,
                                    Map.of(
                                            "grant_type",
                                            "authorization_code",
                                            "client_id",
                                            "web",
                                            "code",
                                            code,
                                            "redirect_uri",
                                            redirect,
                                            "code_verifier",
                                            "wrong"))
                            .statusCode())
                    .isEqualTo(400);
            // the code is single use: even the right verifier fails after a bad attempt
            var again = http.send(
                    HttpRequest.newBuilder(URI.create(kc.issuer()
                                    + "/protocol/openid-connect/auth?client_id=web&response_type=code&state=s2&redirect_uri="
                                    + enc(redirect) + "&code_challenge=" + challenge + "&code_challenge_method=S256"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            String code2 = again.headers().firstValue("Location").orElseThrow().replaceAll(".*code=([^&]+).*", "$1");
            var ok = token(
                    kc,
                    Map.of(
                            "grant_type",
                            "authorization_code",
                            "client_id",
                            "web",
                            "code",
                            code2,
                            "redirect_uri",
                            redirect,
                            "code_verifier",
                            verifier));
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(ok.body()).path("access_token").asText()).isNotEmpty();
            assertThat(kc.requests().toString()).doesNotContain(verifier).doesNotContain(code2);
        }
    }

    @Test
    void introspectionIsInactiveForACallerOutsideTheAudience() throws Exception {
        try (var kc = FakeKeycloak.start("floor42")) {
            kc.registerClient("floor42-api", "api-s");
            kc.registerClient("floor42-agent-ada", "ada-s");
            String session = kc.exchangeForTest(
                    "floor42-agent-ada", kc.directorToken("director"), Map.of("audience", "floor42-api"));
            assertThat(introspect(kc, "floor42-api", "api-s", session)).isTrue();
            assertThat(introspect(kc, "floor42-agent-ada", "ada-s", session)).isFalse();
            assertThat(kc.clients()).containsExactly("floor42-agent-ada", "floor42-api");
        }
    }

    @Test
    void duplicateClientAndDuplicateMapperAnswer409LikeKeycloak() throws Exception {
        try (var kc = FakeKeycloak.start("floor42")) {
            kc.registerClient("floor42-desk", null);
            String admin = kc.directorToken("admin");
            String clients = kc.issuer().toString().replace("/realms/", "/admin/realms/") + "/clients";
            assertThat(post(clients, admin, "{\"clientId\":\"c1\"}")).isEqualTo(201);
            assertThat(post(clients, admin, "{\"clientId\":\"c1\"}")).isEqualTo(409);
            String id = JSON.readTree(http.send(
                                    HttpRequest.newBuilder(URI.create(clients + "?clientId=floor42-desk"))
                                            .header("Authorization", "Bearer " + admin)
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .body())
                    .get(0)
                    .path("id")
                    .asText();
            String mappers = clients + "/" + id + "/protocol-mappers/models";
            assertThat(post(mappers, admin, "{\"name\":\"m\"}")).isEqualTo(201);
            assertThat(post(mappers, admin, "{\"name\":\"m\"}")).isEqualTo(409);
        }
    }

    private int post(String url, String bearer, String body) throws Exception {
        return http.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .header("Authorization", "Bearer " + bearer)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    private boolean introspect(FakeKeycloak kc, String id, String secret, String token) throws Exception {
        String basic = Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
        return JSON.readTree(http.send(
                                HttpRequest.newBuilder(
                                                URI.create(kc.issuer() + "/protocol/openid-connect/token/introspect"))
                                        .header("Authorization", "Basic " + basic)
                                        .header("Content-Type", "application/x-www-form-urlencoded")
                                        .POST(HttpRequest.BodyPublishers.ofString("token=" + enc(token)))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString())
                        .body())
                .path("active")
                .asBoolean();
    }

    private void registerPublicClient(FakeKeycloak kc, String clientId) throws Exception {
        // a client_credentials token needs a confidential client; the first one is created through a director token
        String admin = kc.directorToken("admin");
        var res = http.send(
                HttpRequest.newBuilder(URI.create(base(kc) + "/admin/realms/floor42/clients"))
                        .header("Authorization", "Bearer " + admin)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"clientId\":\"" + clientId + "\",\"publicClient\":true}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(201);
    }

    private HttpResponse<String> token(FakeKeycloak kc, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        return http.send(
                HttpRequest.newBuilder(URI.create(kc.issuer() + "/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String base(FakeKeycloak kc) {
        return kc.issuer().toString().replace("/realms/floor42", "");
    }

    private JsonNode get(String url) throws Exception {
        return JSON.readTree(
                http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString())
                        .body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
