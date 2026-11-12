package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.iam.ResourceBroker.BrokerResult;
import dev.floor42.server.iam.ResourceBroker.Refusal;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The resource access boundary: who may call what, the token that goes out (audience, life, actor), what comes back
 * (a few headers, a capped body, never the token), and no redirect, no slow server holding the session.
 */
class ResourceBrokerUnitTest {

    private static final MissionId M = new MissionId("M-1");
    private static final SessionClaims ADA =
            new SessionClaims("11111111-1111-1111-1111-111111111111", new AgentId("ada"), M, new TicketId("T-1"));

    @TempDir
    Path dir;

    FakeKeycloak kc;
    HttpServer server;
    ResourceCatalogue catalogue;
    Delegations delegations;
    ResourceBroker broker;
    Consumer<ObjectNode> edit = b -> {};
    String lastToken;
    int cap = 1000;
    final List<String> authorizations = new CopyOnWriteArrayList<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        kc = FakeKeycloak.start("floor42");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/", x -> {
            requests.add(x.getRequestMethod() + " " + x.getRequestURI());
            authorizations.add(String.valueOf(x.getRequestHeaders().getFirst("Authorization")));
            bodies.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String p = x.getRequestURI().getPath();
            if (p.equals("/api/slow")) sleep(3000);
            if (p.equals("/api/redirect")) {
                x.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
                x.sendResponseHeaders(302, -1);
                x.close();
                return;
            }
            String body = p.equals("/api/big")
                    ? "x".repeat(3000)
                    : p.equals("/api/echo") ? "you sent " + authorizations.getLast() : "ok";
            x.getResponseHeaders().add("Content-Type", "text/plain");
            x.getResponseHeaders().add("ETag", "\"abc\"");
            x.getResponseHeaders().add("Set-Cookie", "session=1");
            x.getResponseHeaders().add("X-Secret", "nope");
            x.getResponseHeaders().add("Retry-After", "7");
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        server.start();
        ObjectMapper json = new ObjectMapper();
        SecretVault vault = new SecretVault(dir);
        delegations = new Delegations(
                dir.resolve("iam").resolve("delegations.json"),
                vault,
                new KeycloakClient(kc.issuer().toString(), vault, json),
                () -> true,
                Optional::empty,
                null,
                json);
        catalogue = new ResourceCatalogue(dir, json, delegations);
        catalogue.put(resource("demo-api", cap));
        catalogue.grant(new AgentId("ada"), List.of("demo-api"));
        delegations.grant(M, new Tokens("a", "refresh", null, Instant.now().plusSeconds(60)), List.of("demo-api"));
        broker = new ResourceBroker(
                catalogue, new SafeUrls(), new PinnedClients(), Duration.ofSeconds(1), (c, r) -> lastToken = token());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        kc.close();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Resource resource(String name, int max) {
        return new Resource(
                name,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "target-api",
                List.of(),
                List.of("get", "POST", "delete"),
                List.of("/api/"),
                max,
                true);
    }

    private String token() {
        return kc.forge(
                kc.exchangeForTest("floor42-agent-ada", kc.directorToken("director"), Map.of("audience", "target-api")),
                edit);
    }

    private Refusal refused(String resource, String method, String path) {
        Throwable t =
                org.assertj.core.api.Assertions.catchThrowable(() -> broker.call(ADA, resource, method, path, null));
        assertThat(t).isInstanceOf(Refusal.class);
        return (Refusal) t;
    }

    // ---- who may call what

    @Test
    void aResourceThatIsNotInTheCatalogueIsUnknown() {
        assertThat(refused("nope", "GET", "/api/x").code()).isEqualTo("RESOURCE_UNKNOWN");
        assertThat(refused(null, "GET", "/api/x").code()).isEqualTo("RESOURCE_UNKNOWN");
        assertThat(requests).isEmpty();
    }

    @Test
    void theAgentGrantAndTheMissionDelegationMustBothNameTheResource() throws Exception {
        catalogue.grant(new AgentId("ada"), List.of());
        assertThat(refused("demo-api", "GET", "/api/x").code()).isEqualTo("RESOURCE_NOT_GRANTED");
        catalogue.grant(new AgentId("ada"), List.of("demo-api"));
        delegations.grant(M, new Tokens("a", "refresh2", null, Instant.now().plusSeconds(60)), List.of());
        assertThat(refused("demo-api", "GET", "/api/x").code()).isEqualTo("RESOURCE_NOT_GRANTED");
        delegations.grant(M, new Tokens("a", "refresh3", null, Instant.now().plusSeconds(60)), List.of("demo-api"));
        assertThat(broker.call(ADA, "demo-api", "GET", "/api/x", null).status()).isEqualTo(200);
        delegations.revokeLocally(M);
        assertThat(refused("demo-api", "GET", "/api/x").code()).isEqualTo("RESOURCE_NOT_GRANTED");
        assertThat(requests).hasSize(1);
    }

    @Test
    void anotherAgentOrMissionIsNotGranted() {
        SessionClaims bob = new SessionClaims(ADA.launch(), new AgentId("bob"), M, ADA.ticket());
        SessionClaims other = new SessionClaims(ADA.launch(), ADA.agent(), new MissionId("M-2"), ADA.ticket());
        for (SessionClaims c : List.of(bob, other))
            assertThatThrownBy(() -> broker.call(c, "demo-api", "GET", "/api/x", null))
                    .isInstanceOf(Refusal.class)
                    .extracting(e -> ((Refusal) e).code())
                    .isEqualTo("RESOURCE_NOT_GRANTED");
    }

    @Test
    void theAddressRefusalsNameTheMethodAndTheBarePathOnly() {
        Refusal method = refused("demo-api", "PUT", "/api/x");
        assertThat(method.code()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(method.getMessage()).isEqualTo("PUT is not allowed for demo-api");
        assertThat(refused("demo-api", "P0ST!", "/api/x").getMessage()).startsWith("P?ST? is not");
        assertThat(refused("demo-api", null, "/api/x").getMessage()).startsWith("No method is not");
        Refusal path = refused("demo-api", "GET", "/other/x?token=secret");
        assertThat(path.code()).isEqualTo("PATH_NOT_ALLOWED");
        assertThat(path.getMessage()).isEqualTo("/other/x is outside the paths allowed for demo-api");
        assertThat(refused("demo-api", "GET", "/oé/x").getMessage()).startsWith("/o?/x is outside");
        String longPath = "/o" + "a".repeat(200);
        assertThat(refused("demo-api", "GET", longPath).getMessage())
                .startsWith("/o" + "a".repeat(78) + "... is outside");
        String exactly80 = "/o" + "a".repeat(78);
        assertThat(refused("demo-api", "GET", exactly80).getMessage()).startsWith(exactly80 + " is outside");
        assertThat(requests).isEmpty();
    }

    // ---- the call

    @Test
    void theCallCarriesTheBearerOfTheExactTokenAndGivesBackOnlyWhatIsSafe() {
        BrokerResult r = broker.call(ADA, "demo-api", "get", "/api/echo", null);
        assertThat(authorizations).containsExactly("Bearer " + lastToken);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.audience()).isEqualTo("target-api");
        assertThat(r.jti()).isNotBlank();
        assertThat(r.headers())
                .containsOnlyKeys("content-type", "etag", "retry-after", "content-length")
                .containsEntry("etag", "\"abc\"")
                .containsEntry("retry-after", "7");
        assertThat(r.body()).startsWith("you sent Bearer [redacted]").doesNotContain(lastToken);
        assertThat(r.truncated()).isFalse();
        assertThat(r.toString())
                .contains("status=200", "truncated=false", "audience=target-api")
                .doesNotContain("you sent");
    }

    @Test
    void aBodyIsSentOnlyWithAVerbThatCarriesOne() {
        broker.call(ADA, "demo-api", "POST", "/api/x", "{\"a\":1}");
        broker.call(ADA, "demo-api", "POST", "/api/x", "");
        broker.call(ADA, "demo-api", "POST", "/api/x", null);
        broker.call(ADA, "demo-api", "DELETE", "/api/x", "{\"a\":1}");
        broker.call(ADA, "demo-api", "GET", "/api/x", "{\"a\":1}");
        assertThat(requests)
                .containsExactly("POST /api/x", "POST /api/x", "POST /api/x", "DELETE /api/x", "GET /api/x");
        assertThat(bodies).containsExactly("{\"a\":1}", "", "", "", "");
    }

    @Test
    void aRedirectIsNotFollowed() {
        BrokerResult r = broker.call(ADA, "demo-api", "GET", "/api/redirect", null);
        assertThat(r.status()).isEqualTo(302);
        assertThat(requests).containsExactly("GET /api/redirect");
    }

    @Test
    void theBodyIsCutAtTheResourcesCapAndSaysSo() {
        BrokerResult r = broker.call(ADA, "demo-api", "GET", "/api/big", null);
        assertThat(r.body()).hasSize(1000);
        assertThat(r.truncated()).isTrue();
        BrokerResult small = broker.call(ADA, "demo-api", "GET", "/api/x", null);
        assertThat(small.body()).isEqualTo("ok");
        assertThat(small.truncated()).isFalse();
    }

    @Test
    void aSlowServerIsAbandonedAtTheDeadline() {
        long began = System.nanoTime();
        Refusal slow = refused("demo-api", "GET", "/api/slow");
        assertThat(slow.code()).isEqualTo("RESOURCE_UNREACHABLE");
        assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void aServerThatIsGoneIsUnreachableAndTheTokenIsNotNamed() throws IOException {
        server.stop(0);
        Refusal gone = refused("demo-api", "GET", "/api/x");
        assertThat(gone.code()).isEqualTo("RESOURCE_UNREACHABLE");
        assertThat(gone.getMessage()).isEqualTo("demo-api did not answer").doesNotContain("eyJ");
    }

    // ---- the token that came back

    @Test
    void aTokenThatIsNotForThisResourceOrThisAgentIsNeverSent() {
        List<Consumer<ObjectNode>> bad = List.of(
                b -> b.put("aud", "other-api"),
                b -> b.putArray("aud").add("target-api").add("account"),
                b -> ((ObjectNode) b.get("act")).put("client_id", "floor42-agent-bob"),
                b -> b.remove("act"),
                b -> b.put("jti", " "),
                b -> b.remove("jti"),
                b -> b.put("exp", b.get("iat").asLong() + ResourceBroker.MAX_TOKEN_SECONDS + 1),
                b -> b.put("exp", b.get("iat").asLong()),
                b -> b.put("exp", b.get("iat").asLong() - 5),
                b -> b.remove("exp"));
        for (Consumer<ObjectNode> e : bad) {
            edit = e;
            Refusal r = refused("demo-api", "GET", "/api/x");
            assertThat(r.code()).isEqualTo("IDENTITY_UNAVAILABLE");
        }
        assertThat(requests).isEmpty();
    }

    @Test
    void aTokenWhoseLifeIsExactlyTheMaximumAndAnArrayOfOneAudienceAreAccepted() {
        edit = b -> {
            b.put("exp", b.get("iat").asLong() + ResourceBroker.MAX_TOKEN_SECONDS);
            b.putArray("aud").add("target-api");
        };
        assertThat(broker.call(ADA, "demo-api", "GET", "/api/x", null).status()).isEqualTo(200);
    }

    @Test
    void anExpiredTokenAndAnUnreadableOneAreRefused() {
        long now = Instant.now().getEpochSecond();
        edit = b -> {
            b.put("iat", now - 200);
            b.put("exp", now - 31);
        };
        assertThat(refused("demo-api", "GET", "/api/x").getMessage()).contains("expired");
        edit = b -> {
            b.put("iat", now - 200);
            b.put("exp", now - 29);
        };
        assertThat(broker.call(ADA, "demo-api", "GET", "/api/x", null).status()).isEqualTo(200);
        assertThat(requests).hasSize(1);
        assertThatThrownBy(() -> ResourceBroker.checked("garbage", ADA, resource("demo-api", 1)))
                .isInstanceOf(Refusal.class)
                .hasMessageContaining("unreadable");
    }

    @Test
    void aFailingTokenSourceRefusesWithItsOwnCode() {
        broker = new ResourceBroker(catalogue, new SafeUrls(), new PinnedClients(), Duration.ofSeconds(1), (c, r) -> {
            throw new Refusal("IDENTITY_REFUSED", "revoked");
        });
        assertThat(refused("demo-api", "GET", "/api/x").code()).isEqualTo("IDENTITY_REFUSED");
        assertThat(requests).isEmpty();
    }

    // ---- scrubbing

    @Test
    void aTokenPrefixLeftAtTheCutIsMasked() {
        String token = "abcdefghijklmnopqrstuvwxyz";
        assertThat(ResourceBroker.cutAndScrub(("hello " + token).getBytes(StandardCharsets.UTF_8), 100, token))
                .isEqualTo("hello [redacted]");
        for (int n : new int[] {25, 12, 8})
            assertThat(ResourceBroker.cutAndScrub(
                            ("hello " + token.substring(0, n)).getBytes(StandardCharsets.UTF_8), 100, token))
                    .as("prefix of %d", n)
                    .isEqualTo("hello [redacted]");
        assertThat(ResourceBroker.cutAndScrub(
                        ("hello " + token.substring(0, 7)).getBytes(StandardCharsets.UTF_8), 100, token))
                .isEqualTo("hello abcdefg");
        assertThat(ResourceBroker.cutAndScrub(("hello " + token).getBytes(StandardCharsets.UTF_8), 5, token))
                .isEqualTo("hello");
        assertThat(ResourceBroker.cutAndScrub("plain".getBytes(StandardCharsets.UTF_8), 100, token))
                .isEqualTo("plain");
        assertThat(ResourceBroker.cutAndScrub(("x " + token + " y").getBytes(StandardCharsets.UTF_8), 100, token))
                .isEqualTo("x [redacted] y");
    }
}
