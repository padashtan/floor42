package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.iam.ResourceBroker.Refusal;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FGA half of the outage lines. The refusal codes are covered by FgaBrokerTest and FgaMcpTest; what is new here is the
 * side-effect side: OpenFGA down while an agent calls a resource. The call is refused
 * with its own code, no token is asked from the issuer and the resource server sees nothing. No Quarkus: the broker
 * and the in-memory OpenFGA of the unit tests, so the real daemon wiring of OpenFGA is not covered here.
 */
class FgaOutageLabTest {

    private static final MissionId M = new MissionId("M-1");
    private static final AgentId ADA_ID = new AgentId("ada");
    private static final SessionClaims ADA =
            new SessionClaims("11111111-1111-1111-1111-111111111111", ADA_ID, M, new TicketId("T-1"));

    @TempDir
    Path dir;

    HttpServer server;
    FakeKeycloak kc;
    final AtomicInteger hits = new AtomicInteger();
    final AtomicInteger tokensAsked = new AtomicInteger();
    TestGrants grants;
    ResourceCatalogue catalogue;
    ResourceBroker broker;
    Delegations delegations;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/", x -> {
            hits.incrementAndGet();
            byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        server.start();
        ObjectMapper json = new ObjectMapper();
        kc = FakeKeycloak.start("floor42");
        SecretVault vault = new SecretVault(dir);
        delegations = new Delegations(
                dir.resolve("iam").resolve("delegations.json"),
                vault,
                new KeycloakClient(kc.issuer().toString(), vault, json),
                () -> true,
                Optional::empty,
                null,
                json);
        grants = new TestGrants();
        delegations.listen(grants.tuples);
        catalogue = new ResourceCatalogue(dir, json, delegations, grants.tuples);
        catalogue.put(new Resource(
                "demo-api",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "target-api",
                List.of(),
                List.of("GET"),
                List.of("/api/"),
                1000,
                true));
        broker = new ResourceBroker(catalogue, new SafeUrls(), new PinnedClients(), Duration.ofSeconds(2), (c, r) -> {
            tokensAsked.incrementAndGet();
            return kc.forge(
                    kc.exchangeForTest(
                            "floor42-agent-ada", kc.directorToken("director"), Map.of("audience", "target-api")),
                    b -> {});
        });
        catalogue.grant(ADA_ID, List.of("demo-api"));
        delegations.grant(
                M, new Tokens("a", "refresh", null, Instant.now().plusSeconds(60)), List.of("demo-api", "mcp-demo"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
        kc.close();
    }

    @Test
    void fgaOutage_aBrokerCallIsRefusedWithItsOwnCodeAndNothingLeavesTheDaemon() {
        grants.fga.unavailable(true);
        Throwable t = catchThrowable(() -> broker.call(ADA, "demo-api", "GET", "/api/x", null));
        assertThat(t)
                .isInstanceOfSatisfying(Refusal.class, r -> assertThat(r.code()).isEqualTo("FGA_UNAVAILABLE"));
        assertThat(tokensAsked).as("no token exchange").hasValue(0);
        assertThat(hits).as("no request to the resource").hasValue(0);
        grants.fga.unavailable(false);
        assertThat(broker.call(ADA, "demo-api", "GET", "/api/x", null).status())
                .as("it recovers")
                .isEqualTo(200);
    }

    @Test
    void fgaOutage_anMcpToolCallIsRefusedWithItsOwnCode() {
        McpServers servers = new McpServers(dir, new ObjectMapper(), catalogue, grants.tuples);
        catalogue.put(new Resource(
                "mcp-demo",
                URI.create("https://mcp.example.test"),
                "mcp-api",
                List.of(),
                List.of("POST"),
                List.of("/mcp"),
                1000,
                false));
        servers.put("mcp-demo", "/mcp");
        catalogue.grant(ADA_ID, List.of("demo-api", "mcp-demo"));
        servers.pin(
                "mcp-demo",
                List.of(new McpServers.Listed(
                        "echo", "Echoes", new ObjectMapper().createObjectNode().put("type", "object"))),
                Instant.now());
        grants.fga.unavailable(true);
        McpClient client = new McpClient(broker, servers, catalogue, new ObjectMapper());
        Throwable t = catchThrowable(() -> client.callTool(ADA, "mcp-demo", "echo", null));
        assertThat(t)
                .isInstanceOfSatisfying(Refusal.class, r -> assertThat(r.code()).isEqualTo("FGA_UNAVAILABLE"));
        assertThat(tokensAsked).as("no token exchange").hasValue(0);
        assertThat(hits).as("no request sent").hasValue(0);
    }
}
