package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.iam.McpClient.Called;
import dev.floor42.server.iam.McpServers.Listed;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Speaking MCP through the broker against a local stub of a remote server: the handshake, the headers, an event-stream
 * answer, what is refused before any request leaves (internal addresses, plain http), and what is not trusted (a
 * redirect, an answer that is not JSON-RPC). The stub is the only server: no network.
 */
class McpClientTest {

    private static final MissionId M = new MissionId("M-1");
    private static final SessionClaims ADA =
            new SessionClaims("11111111-1111-1111-1111-111111111111", new AgentId("ada"), M, new TicketId("T-1"));
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    FakeKeycloak kc;
    HttpServer server;
    ResourceCatalogue catalogue;
    Delegations delegations;
    McpServers servers;
    McpClient client;
    String mode = "plain";
    /** Set by notifications/initialized: the stub refuses any other request before it, as a strict server does. */
    volatile boolean initialized;

    final AtomicInteger exchanges = new AtomicInteger();
    /** "METHOD path" of every request the stub received. */
    final List<String> requests = new CopyOnWriteArrayList<>();
    /** The JSON-RPC method of every request, in order. */
    final List<String> rpcMethods = new CopyOnWriteArrayList<>();

    final List<Map<String, String>> sentHeaders = new CopyOnWriteArrayList<>();
    final List<String> authorizations = new CopyOnWriteArrayList<>();
    String lastToken;

    @BeforeEach
    void start() throws Exception {
        kc = FakeKeycloak.start("floor42");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/mcp", this::stub);
        server.start();
        delegations = new Delegations(
                dir.resolve("iam").resolve("delegations.json"),
                new SecretVault(dir),
                new KeycloakClient(kc.issuer().toString(), new SecretVault(dir), JSON),
                () -> true,
                Optional::empty,
                null,
                JSON);
        catalogue = new ResourceCatalogue(dir, JSON, delegations);
        catalogue.put(local("mcp-demo"));
        catalogue.grant(new AgentId("ada"), List.of("mcp-demo"));
        delegations.grant(M, new Tokens("a", "refresh", null, Instant.now().plusSeconds(60)), List.of("mcp-demo"));
        servers = new McpServers(dir, JSON, catalogue);
        servers.put("mcp-demo", "/mcp");
        client = client(new SafeUrls());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        kc.close();
    }

    private McpClient client(SafeUrls urls) {
        ResourceBroker broker =
                new ResourceBroker(catalogue, urls, new PinnedClients(), Duration.ofSeconds(2), (c, r) -> {
                    exchanges.incrementAndGet();
                    return lastToken = kc.forge(
                            kc.exchangeForTest(
                                    "floor42-agent-ada",
                                    kc.directorToken("director"),
                                    Map.of("audience", "target-api")),
                            b -> {});
                });
        return new McpClient(broker, servers, catalogue, JSON);
    }

    private Resource local(String name) {
        return new Resource(
                name,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "target-api",
                List.of(),
                List.of("POST"),
                List.of("/mcp"),
                4000,
                true);
    }

    private static Listed tool(String name, String description) {
        return new Listed(name, description, JSON.createObjectNode().put("type", "object"));
    }

    /** Pins the stub's tools and ticks {@code echo}, as the director would. */
    private void pinAndTick() {
        servers.pin("mcp-demo", client.listTools(ADA, "mcp-demo"), Instant.now());
        McpTicks.tick(servers, "mcp-demo", "echo");
        requests.clear();
        rpcMethods.clear();
        sentHeaders.clear();
        authorizations.clear();
        exchanges.set(0);
    }

    // ---- the stub: a minimal remote MCP server

    private void stub(HttpExchange x) throws IOException {
        requests.add(x.getRequestMethod() + " " + x.getRequestURI().getPath());
        authorizations.add(String.valueOf(x.getRequestHeaders().getFirst("Authorization")));
        sentHeaders.add(Map.of(
                "session", String.valueOf(x.getRequestHeaders().getFirst("Mcp-session-id")),
                "version", String.valueOf(x.getRequestHeaders().getFirst("Mcp-protocol-version")),
                "accept", String.valueOf(x.getRequestHeaders().getFirst("Accept"))));
        JsonNode in = JSON.readTree(x.getRequestBody().readAllBytes());
        String method = in.path("method").asText();
        rpcMethods.add(method);
        int id = in.path("id").asInt();
        if (mode.equals("redirect")) {
            x.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
            x.sendResponseHeaders(307, -1);
            x.close();
            return;
        }
        if (mode.equals("html")) {
            reply(x, "text/html", "<html>not json-rpc</html>");
            return;
        }
        if (method.equals("notifications/initialized")) {
            initialized = true;
            x.sendResponseHeaders(202, -1);
            x.close();
            return;
        }
        if (!method.equals("initialize") && !initialized) {
            reply(x, "application/json", "{}", 400);
            return;
        }
        String escaped = null;
        ObjectNode out = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id);
        switch (method) {
            case "initialize" -> {
                initialized = false;
                x.getResponseHeaders().add("Mcp-Session-Id", "sess-1");
                out.putObject("result").put("protocolVersion", "2025-06-18").putObject("capabilities");
            }
            case "tools/list" -> {
                var tools = out.putObject("result").putArray("tools");
                tools.addObject()
                        .put("name", "echo")
                        .put("description", "Echoes")
                        .putObject("inputSchema")
                        .put("type", "object");
                tools.addObject()
                        .put("name", "other")
                        .put("description", "Another")
                        .putObject("inputSchema")
                        .put("type", "object");
                tools.addObject().put("name", "bad name\nwith a line").put("description", "Never pinned");
                tools.addObject().put("name", "dup").put("description", "First definition");
                tools.addObject().put("name", "dup").put("description", "Second definition");
            }
            case "tools/call" -> {
                String name = in.path("params").path("name").asText();
                String bearer = String.valueOf(x.getRequestHeaders().getFirst("Authorization"));
                // "leakescaped" writes the token back with its first character as a JSON unicode escape
                if (name.equals("leakescaped")) escaped = bearer.substring("Bearer ".length());
                String text = name.equals("leak")
                        ? "you sent " + bearer
                        : name.equals("leakescaped")
                                ? "you sent " + escaped
                                : "echo " + in.path("params").path("arguments");
                var result = out.putObject("result");
                result.putArray("content").addObject().put("type", "text").put("text", text);
                result.put("isError", name.equals("fail"));
            }
            default -> out.putObject("error").put("code", -32601).put("message", "no such method");
        }
        if (mode.equals("sse") && method.equals("tools/call")) {
            String decoy =
                    "{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"decoy\"}]}}";
            reply(
                    x,
                    "text/event-stream",
                    "event: message\ndata: " + decoy + "\n\nevent: message\ndata: " + out + "\n\n");
        } else if (mode.equals("status500")) {
            x.sendResponseHeaders(500, -1);
            x.close();
        } else {
            String body = out.toString();
            if (escaped != null) body = body.replace(escaped, "\\u0065" + escaped.substring(1));
            reply(x, "application/json", body);
        }
    }

    private static void reply(HttpExchange x, String type, String body) throws IOException {
        reply(x, type, body, 200);
    }

    private static void reply(HttpExchange x, String type, String body, int status) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", type);
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    private Refusal refused(Runnable call) {
        Throwable t = catchThrowable(call::run);
        assertThat(t).isInstanceOf(Refusal.class);
        return (Refusal) t;
    }

    // ---- the handshake

    @Test
    void listsToolsAfterInitialize() {
        List<Listed> tools = client.listTools(ADA, "mcp-demo");

        assertThat(tools).extracting(Listed::name).containsExactly("echo", "other");
        assertThat(rpcMethods)
                .as("the stub refuses anything before the notification")
                .containsExactly("initialize", "notifications/initialized", "tools/list");
        assertThat(exchanges.get()).as("one token exchange per HTTP request").isEqualTo(3);
        assertThat(authorizations).hasSize(3).allMatch(a -> a.startsWith("Bearer ey"));
        assertThat(sentHeaders.get(0)).containsEntry("session", "null").containsEntry("version", "null");
        for (int i = 1; i <= 2; i++)
            assertThat(sentHeaders.get(i))
                    .containsEntry("session", "sess-1")
                    .containsEntry("version", "2025-06-18")
                    .containsEntry("accept", "application/json, text/event-stream");
    }

    @Test
    void aCallOfATickedToolIsThreeRequestsAndTheSessionComesBack() {
        pinAndTick();

        Called c =
                client.callTool(ADA, "mcp-demo", "echo", JSON.createObjectNode().put("q", "hi"));

        assertThat(rpcMethods).containsExactly("initialize", "notifications/initialized", "tools/call");
        assertThat(exchanges.get()).isEqualTo(3);
        assertThat(sentHeaders.get(2)).containsEntry("session", "sess-1");
        assertThat(c.error()).isFalse();
        assertThat(c.text()).isEqualTo("echo {\"q\":\"hi\"}");
        assertThat(c.firstLine("mcp-demo", "echo"))
                .matches("MCP mcp-demo echo -> ok \\(exchanged token [0-9a-f-]+ for target-api\\)");
        assertThat(c.firstLine("mcp-demo", "echo")).doesNotContain("eyJ");
    }

    @Test
    void theServersOwnErrorFlagComesBackAsAnError() {
        servers.pin("mcp-demo", List.of(tool("fail", "x")), Instant.now());
        McpTicks.tick(servers, "mcp-demo", "fail");

        Called c = client.callTool(ADA, "mcp-demo", "fail", null);

        assertThat(c.error()).isTrue();
        assertThat(c.firstLine("mcp-demo", "fail")).contains("-> error (");
    }

    @Test
    void readsAnEventStreamAnswer() {
        pinAndTick();
        mode = "sse";

        Called c =
                client.callTool(ADA, "mcp-demo", "echo", JSON.createObjectNode().put("q", "hi"));

        assertThat(c.text())
                .as("the result of request id 2, not the decoy of id 99")
                .isEqualTo("echo {\"q\":\"hi\"}");
    }

    @Test
    void anAnswerThatIsNotJsonRpcIsRefused() {
        mode = "html";
        Refusal r = refused(() -> client.listTools(ADA, "mcp-demo"));
        assertThat(r.code()).isEqualTo("MCP_PROTOCOL");
        assertThat(rpcMethods).as("it stopped at the first answer").containsExactly("initialize");

        mode = "status500";
        assertThat(refused(() -> client.listTools(ADA, "mcp-demo")).code()).isEqualTo("MCP_PROTOCOL");
    }

    @Test
    void aNameListedTwiceIsPinnedNeitherTime() {
        assertThat(client.listTools(ADA, "mcp-demo")).extracting(Listed::name).doesNotContain("dup");
    }

    @Test
    void aServerThatFailsTheHandshakeIsRefusedNotUsed() {
        mode = "status500";
        assertThat(refused(() -> client.listTools(ADA, "mcp-demo")).code()).isEqualTo("MCP_PROTOCOL");
    }

    @Test
    void aToolNameThatIsNotOneLineOfPlainCharactersIsNeverPinned() {
        assertThat(client.listTools(ADA, "mcp-demo")).extracting(Listed::name).doesNotContain("bad name\nwith a line");
    }

    // ---- what is not trusted

    @Test
    void aRedirectToAnInternalAddressIsNotFollowed() {
        mode = "redirect";

        Refusal r = refused(() -> client.listTools(ADA, "mcp-demo"));

        assertThat(r.code()).isEqualTo("MCP_PROTOCOL");
        assertThat(r.getMessage()).contains("HTTP 307");
        assertThat(requests).as("the Location was not visited").hasSize(1);
    }

    @Test
    void theTokenTheServerEchoesIsRedactedInTheAnswer() {
        servers.pin("mcp-demo", List.of(tool("leak", "x")), Instant.now());
        McpTicks.tick(servers, "mcp-demo", "leak");

        Called c = client.callTool(ADA, "mcp-demo", "leak", null);

        assertThat(c.text()).isEqualTo("you sent Bearer [redacted]").doesNotContain(lastToken);
    }

    @Test
    void aTokenWrittenBackWithAUnicodeEscapeIsRedactedAfterDecoding() {
        servers.pin("mcp-demo", List.of(tool("leakescaped", "x")), Instant.now());
        McpTicks.tick(servers, "mcp-demo", "leakescaped");

        Called c = client.callTool(ADA, "mcp-demo", "leakescaped", null);

        assertThat(c.text()).isEqualTo("you sent [redacted]").doesNotContain(lastToken, lastToken.substring(1));
    }

    @Test
    void anUndeclaredServerIsNotEchoedBackToTheAgent() {
        Refusal r = refused(() -> client.callTool(ADA, "token=abc123secret", "echo", null));

        assertThat(r.code()).isEqualTo("MCP_SERVER_UNKNOWN");
        assertThat(r.getMessage()).doesNotContain("abc123secret", "token");
    }

    @Test
    void ticksDoNotSurviveAnotherAddressForTheSameResource() throws Exception {
        pinAndTick();
        catalogue.put(new Resource(
                "mcp-demo",
                URI.create("http://127.0.0.1:" + (server.getAddress().getPort() + 1)),
                "target-api",
                List.of(),
                List.of("POST"),
                List.of("/mcp"),
                4000,
                true));

        assertThat(refused(() -> client.callTool(ADA, "mcp-demo", "echo", null)).code())
                .isEqualTo("MCP_TOOL_NOT_ALLOWED");
        assertThat(requests).as("nothing was sent to the new address").isEmpty();
    }

    @Test
    void argumentsThatAreNotAnObjectAreRefusedBeforeAnyRequest() {
        pinAndTick();
        assertThat(refused(() -> client.callTool(
                                ADA, "mcp-demo", "echo", JSON.getNodeFactory().textNode("x")))
                        .code())
                .isEqualTo("INVALID_ARGUMENTS");
        assertThat(requests).isEmpty();
    }

    // ---- what is refused before any request leaves

    private void remote(String name, String base) throws IOException {
        List<Resource> all = new ArrayList<>(catalogue.all());
        all.removeIf(r -> r.name().equals(name));
        all.add(new Resource(
                name, URI.create(base), "target-api", List.of(), List.of("POST"), List.of("/mcp"), 4000, false));
        // Written as the catalogue's own file: put() itself would refuse plain http for a remote resource.
        JSON.writeValue(dir.resolve("iam").resolve("resources.json").toFile(), all);
        catalogue.grant(new AgentId("ada"), List.of("mcp-demo", name));
        servers.put(name, "/mcp");
    }

    private void refusedBeforeAnyRequest(String resolved, String code) throws Exception {
        remote("far-mcp", "https://mcp.example.test");
        servers.pin("far-mcp", List.of(tool("echo", "x")), Instant.now());
        McpTicks.tick(servers, "far-mcp", "echo");
        McpClient far = client(new SafeUrls(host -> new InetAddress[] {InetAddress.getByName(resolved)}));
        // far-mcp is granted to the agent, and its mission delegation names it
        delegations.grant(
                M,
                new Tokens(
                        "a", "refresh-" + System.nanoTime(), null, Instant.now().plusSeconds(60)),
                List.of("mcp-demo", "far-mcp"));
        exchanges.set(0);

        Refusal r = refused(() -> far.callTool(ADA, "far-mcp", "echo", null));

        assertThat(r.code()).isEqualTo(code);
        assertThat(exchanges.get()).as("no token was exchanged").isZero();
        assertThat(requests).as("nothing reached any server").isEmpty();
    }

    @Test
    void aServerOnAnInternalAddressIsRefused() throws Exception {
        for (String internal : List.of("10.0.0.5", "169.254.169.254", "192.168.1.9", "100.64.0.1"))
            refusedBeforeAnyRequest(internal, "HOST_NOT_ALLOWED");
    }

    @Test
    void aLoopbackAddressIsRefusedForAResourceThatIsNotLocal() throws Exception {
        refusedBeforeAnyRequest("127.0.0.1", "HOST_NOT_ALLOWED");
    }

    @Test
    void plainHttpIsRefusedForARemoteServer() throws Exception {
        remote("plain-mcp", "http://mcp.example.test");
        servers.pin("plain-mcp", List.of(tool("echo", "x")), Instant.now());
        McpTicks.tick(servers, "plain-mcp", "echo");
        delegations.grant(
                M,
                new Tokens(
                        "a", "refresh-" + System.nanoTime(), null, Instant.now().plusSeconds(60)),
                List.of("mcp-demo", "plain-mcp"));
        exchanges.set(0);

        Refusal r = refused(() -> client.callTool(ADA, "plain-mcp", "echo", null));

        assertThat(r.code()).isEqualTo("SCHEME_NOT_ALLOWED");
        assertThat(exchanges.get()).isZero();
        assertThat(requests).isEmpty();
    }

    @Test
    void anUndeclaredServerAndAnUntickedToolAreRefusedBeforeAnyRequest() {
        assertThat(refused(() -> client.callTool(ADA, "no-such", "echo", null)).code())
                .isEqualTo("MCP_SERVER_UNKNOWN");
        servers.pin("mcp-demo", List.of(tool("echo", "x")), Instant.now());
        assertThat(refused(() -> client.callTool(ADA, "mcp-demo", "echo", null)).code())
                .isEqualTo("MCP_TOOL_NOT_ALLOWED");
        assertThat(refused(() -> client.callTool(ADA, "mcp-demo", "never-listed", null))
                        .code())
                .isEqualTo("MCP_TOOL_NOT_ALLOWED");
        assertThat(exchanges.get()).isZero();
        assertThat(requests).isEmpty();
    }

    @Test
    void anAgentWithoutTheGrantIsRefusedBeforeAnyRequest() {
        pinAndTick();
        SessionClaims bob = new SessionClaims(ADA.launch(), new AgentId("bob"), M, ADA.ticket());

        assertThat(refused(() -> client.callTool(bob, "mcp-demo", "echo", null)).code())
                .isEqualTo("RESOURCE_NOT_GRANTED");
        assertThat(requests).isEmpty();
    }
}
