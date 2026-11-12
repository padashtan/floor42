package dev.floor42.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** Real Keycloak 26.8.1 with the mapper jar and the real realm file plus a test overlay (agent, director, resource). */
class MappersIT {

  static final ObjectMapper JSON = new ObjectMapper();
  static final String LAUNCH = "6f1c2c0e-8a43-4f55-9d52-0a1b2c3d4e5f";
  static final String AGENT_SECRET = "00112233445566778899aabbccddeeff";
  static final String DAEMON_SECRET = "ffeeddccbbaa99887766554433221100";
  static final String ENV_KEY = "FLOOR42_DAEMON_SECRET";

  static GenericContainer<?> keycloak;
  static String base;
  static final HttpClient HTTP = HttpClient.newHttpClient();

  @BeforeAll
  static void start() throws Exception {
    Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "no Docker");
    Path realm = Files.createTempFile("floor42-realm-it", ".json");
    Files.writeString(realm, overlay(Files.readString(Path.of("../realm/floor42-realm.json"))));
    keycloak =
        new GenericContainer<>("quay.io/keycloak/keycloak:26.8.1")
            .withEnv(ENV_KEY, DAEMON_SECRET)
            .withCopyFileToContainer(
                MountableFile.forHostPath("target/floor42-keycloak-extensions.jar"),
                "/opt/keycloak/providers/floor42-keycloak-extensions.jar")
            .withCopyFileToContainer(
                MountableFile.forHostPath(realm), "/opt/keycloak/data/import/floor42-realm.json")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/floor42").forPort(8080).withStartupTimeout(Duration.ofMinutes(4)));
    keycloak.start();
    base = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080) + "/realms/floor42/protocol/openid-connect/token";
  }

  @AfterAll
  static void stop() {
    if (keycloak != null) keycloak.stop();
  }

  /** The test realm: real file + a director, a direct-grant desk client (audience mapper for the agent), two agents, a resource. */
  static String overlay(String realmJson) throws Exception {
    ObjectNode r = (ObjectNode) JSON.readTree(realmJson);
    ArrayNode clients = (ArrayNode) r.get("clients");
    clients.add(JSON.readTree("""
        {"clientId":"floor42-desk-test","enabled":true,"publicClient":true,"directAccessGrantsEnabled":true,
         "standardFlowEnabled":false,
         "protocolMappers":[
           {"name":"aud ada","protocol":"openid-connect","protocolMapper":"oidc-audience-mapper",
            "config":{"included.client.audience":"floor42-agent-ada","access.token.claim":"true"}},
           {"name":"aud public","protocol":"openid-connect","protocolMapper":"oidc-audience-mapper",
            "config":{"included.client.audience":"floor42-agent-pub","access.token.claim":"true"}}]}"""));
    clients.add(agent("floor42-agent-ada", false));
    clients.add(agent("floor42-agent-pub", true));
    clients.add(JSON.readTree("""
        {"clientId":"res-demo","enabled":true,"bearerOnly":true,"publicClient":false,
         "standardFlowEnabled":false}"""));
    ArrayNode users = (ArrayNode) r.get("users");
    users.add(JSON.readTree("""
        {"username":"director","enabled":true,"email":"d@example.test","emailVerified":true,"firstName":"Di","lastName":"Rector","credentials":[{"type":"password","value":"director-pw","temporary":false}],
         "clientRoles":{"res-demo":["use"]}}"""));
    users.add(JSON.readTree("{\"username\":\"service-account-floor42-agent-ada\",\"enabled\":true,\"serviceAccountClientId\":\"floor42-agent-ada\"}"));
    r.set("roles", JSON.readTree("{\"client\":{\"res-demo\":[{\"name\":\"use\"}]}}"));
    return JSON.writeValueAsString(r);
  }

  /** An agent client; "pub" is the same shape but a public client (must never be trusted for the context). */
  static JsonNode agent(String id, boolean pub) throws Exception {
    ObjectNode c = (ObjectNode) JSON.readTree("""
        {"enabled":true,"standardFlowEnabled":false,"directAccessGrantsEnabled":false,
         "serviceAccountsEnabled":true,
         "defaultClientScopes":["basic","profile","email","roles","web-origins","acr","service_account","floor42-agent"],
         "attributes":{"standard.token.exchange.enabled":"true","access.token.lifespan":"28800"}}""");
    c.put("clientId", id);
    c.put("publicClient", pub);
    if (pub) c.put("serviceAccountsEnabled", false);
    else c.put("secret", AGENT_SECRET);
    return c;
  }

  // ---- HTTP helpers

  static JsonNode post(Map<String, String> form, String basicId, String basicSecret) throws Exception {
    // a value with "\n" is sent as a repeated parameter
    String body = form.entrySet().stream()
        .flatMap(e -> java.util.Arrays.stream(e.getValue().split("\n"))
            .map(v -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)))
        .collect(Collectors.joining("&"));
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body));
    if (basicId != null) {
      b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((basicId + ":" + basicSecret).getBytes(StandardCharsets.UTF_8)));
    }
    return JSON.readTree(HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()).body());
  }

  static String passwordGrant() throws Exception {
    JsonNode t = post(Map.of("grant_type", "password", "client_id", "floor42-desk-test", "username", "director", "password", "director-pw"), null, null);
    assertThat(t.path("access_token").asText()).as("director token: %s", t).isNotEmpty();
    return t.get("access_token").asText();
  }

  static JsonNode exchangeRaw(String client, String secret, String subject, Map<String, String> extra) throws Exception {
    Map<String, String> f = new LinkedHashMap<>();
    f.put("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
    f.put("subject_token", subject);
    f.put("subject_token_type", "urn:ietf:params:oauth:token-type:access_token");
    f.put("requested_token_type", "urn:ietf:params:oauth:token-type:access_token");
    if (secret == null) f.put("client_id", client);
    f.putAll(extra); // an "audience" with "\n" is sent repeated
    return post(f, secret == null ? null : client, secret);
  }

  static JsonNode exchange(String client, String secret, String subject, Map<String, String> extra) throws Exception {
    JsonNode t = exchangeRaw(client, secret, subject, extra);
    assertThat(t.path("access_token").asText()).as("exchange answer: %s", t).isNotEmpty();
    return claims(t.get("access_token").asText());
  }

  static JsonNode claims(String jwt) throws Exception {
    return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
  }

  static JsonNode clientCredentials(String client, String secret) throws Exception {
    JsonNode t = post(Map.of("grant_type", "client_credentials"), client, secret);
    return claims(t.get("access_token").asText());
  }

  static String userId(String username) throws Exception {
    return claims(passwordGrant()).path("sub").asText();
  }

  static String adminToken() throws Exception {
    HttpRequest rq = HttpRequest.newBuilder(URI.create(base.replace("/realms/floor42/", "/realms/master/")))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString("grant_type=password&client_id=admin-cli&username=admin&password=admin")).build();
    return JSON.readTree(HTTP.send(rq, HttpResponse.BodyHandlers.ofString()).body()).get("access_token").asText();
  }

  static String serviceAccountId(String clientId) throws Exception {
    String api = base.substring(0, base.indexOf("/realms/")) + "/admin/realms/floor42/";
    String tok = adminToken();
    JsonNode cs = JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(api + "clients?clientId=" + clientId)).header("Authorization", "Bearer " + tok).build(), HttpResponse.BodyHandlers.ofString()).body());
    String id = cs.get(0).get("id").asText();
    return JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(api + "clients/" + id + "/service-account-user")).header("Authorization", "Bearer " + tok).build(), HttpResponse.BodyHandlers.ofString()).body()).get("id").asText();
  }

  // ---- tests

  @Test
  void exchangeWritesActAndContext() throws Exception {
    JsonNode t = exchange("floor42-agent-ada", AGENT_SECRET, passwordGrant(),
        Map.of("audience", "floor42-api", "floor42_launch", LAUNCH, "floor42_mission", "M-3", "floor42_ticket", "T-7"));
    assertThat(t.path("act").path("client_id").asText()).isEqualTo("floor42-agent-ada");
    assertThat(t.path("act").path("sub").asText()).isEqualTo(serviceAccountId("floor42-agent-ada"));
    assertThat(t.path("sub").asText()).isEqualTo(userId("director"));
    assertThat(t.path("azp").asText()).isEqualTo("floor42-agent-ada");
    assertThat(t.path("floor42").path("launch").asText()).isEqualTo(LAUNCH);
    assertThat(t.path("floor42").path("mission").asText()).isEqualTo("M-3");
    assertThat(t.path("floor42").path("ticket").asText()).isEqualTo("T-7");
    // floor42-api audience: no 300 s cap, the client lifespan (28800) stays
    assertThat(t.path("exp").asLong() - t.path("iat").asLong()).isGreaterThan(300);
  }

  @Test
  void clientCredentialsTokenHasNoActNorContext() throws Exception {
    JsonNode t = clientCredentials("floor42-agent-ada", AGENT_SECRET);
    assertThat(t.has("act")).isFalse();
    assertThat(t.has("floor42")).isFalse();
  }

  @Test
  void partialOrInvalidContextWritesNothing() throws Exception {
    String d = passwordGrant();
    assertThat(exchange("floor42-agent-ada", AGENT_SECRET, d, Map.of("audience", "floor42-api", "floor42_mission", "M-3")).has("floor42")).isFalse();
    assertThat(exchange("floor42-agent-ada", AGENT_SECRET, d,
        Map.of("audience", "floor42-api", "floor42_launch", "not-a-uuid", "floor42_mission", "M-3", "floor42_ticket", "T-7")).has("floor42")).isFalse();
    assertThat(exchange("floor42-agent-ada", AGENT_SECRET, d,
        Map.of("audience", "floor42-api", "floor42_launch", LAUNCH, "floor42_mission", "M-3x", "floor42_ticket", "T-7")).has("floor42")).isFalse();
  }

  @Test
  void actComesFromTheAuthenticatedClientNeverFromAParam() throws Exception {
    JsonNode t = exchange("floor42-agent-ada", AGENT_SECRET, passwordGrant(),
        Map.of("audience", "floor42-api", "act", "{\"sub\":\"evil\",\"client_id\":\"evil\"}", "act_sub", "evil"));
    assertThat(t.path("act").path("client_id").asText()).isEqualTo("floor42-agent-ada");
    assertThat(t.path("act").path("sub").asText()).isNotEqualTo("evil");
  }

  @Test
  void publicClientGetsNeitherActNorContext() throws Exception {
    // a public client cannot authenticate: its context params are never trusted (and it has no service account)
    JsonNode r = exchangeRaw("floor42-agent-pub", null, passwordGrant(),
        Map.of("audience", "floor42-api", "floor42_launch", LAUNCH, "floor42_mission", "M-3", "floor42_ticket", "T-7"));
    assertThat(r.has("access_token")).as("public client answer: %s", r).isFalse();
    assertThat(r.path("error").asText()).as("public client answer: %s", r).isNotEmpty();
  }

  static long life(JsonNode claims) {
    return claims.path("exp").asLong() - claims.path("iat").asLong();
  }

  @Test
  void tokensNotForTheApiAreCappedAt300Seconds() throws Exception {
    JsonNode r = exchange("floor42-agent-ada", AGENT_SECRET, passwordGrant(), Map.of("audience", "res-demo"));
    assertThat(life(r)).as("claims: %s", r).isLessThanOrEqualTo(300);
  }

  @Test
  void noAudienceOrSeveralAudiencesAreCapped() throws Exception {
    String d = passwordGrant();
    assertThat(life(exchange("floor42-agent-ada", AGENT_SECRET, d, Map.of()))).isLessThanOrEqualTo(300);
    assertThat(life(exchange("floor42-agent-ada", AGENT_SECRET, d, Map.of("audience", "floor42-api\nres-demo")))).isLessThanOrEqualTo(300);
    assertThat(life(exchange("floor42-agent-ada", AGENT_SECRET, d, Map.of("audience", "res-demo\nfloor42-api")))).isLessThanOrEqualTo(300);
    assertThat(life(exchange("floor42-agent-ada", AGENT_SECRET, d, Map.of("audience", "floor42-api")))).isGreaterThan(300);
  }

  @Test
  void agentCannotGetARefreshTokenFromAnExchange() throws Exception {
    JsonNode r = exchangeRaw("floor42-agent-ada", AGENT_SECRET, passwordGrant(),
        Map.of("audience", "floor42-api", "requested_token_type", "urn:ietf:params:oauth:token-type:refresh_token"));
    assertThat(r.has("refresh_token")).as("answer: %s", r).isFalse();
    assertThat(r.path("error").asText()).as("answer: %s", r).isNotEmpty();
  }
}
