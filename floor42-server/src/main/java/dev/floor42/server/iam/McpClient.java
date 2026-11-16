package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.floor42.server.iam.McpServers.Listed;
import dev.floor42.server.iam.McpServers.McpServer;
import dev.floor42.server.iam.ResourceBroker.BrokerResult;
import dev.floor42.server.iam.ResourceBroker.Refusal;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Speaks MCP (JSON-RPC over streamable HTTP) to a declared server, only through {@link ResourceBroker}: the grant, the
 * delegation, the address checks and one token exchange per HTTP request are the broker's (ADR-0072). Refusals are
 * {@link Refusal}s with their code: MCP_SERVER_UNKNOWN, MCP_TOOL_NOT_ALLOWED, MCP_PROTOCOL, INVALID_ARGUMENTS, and the
 * broker's own.
 *
 * <p>Shortcuts, each with its limit: one handshake per call (three exchanges: {@code initialize}, {@code
 * notifications/initialized}, then the request), no session reuse, upgrade when a measured call is too slow; the
 * session is never closed with a DELETE, so a server that keeps sessions holds one until its own timeout, upgrade if a
 * real server's limit on open sessions is reached; only the first page of {@code tools/list} is read.
 */
@Singleton
public class McpClient {

    private static final String ACCEPT = "application/json, text/event-stream";
    private static final String DEFAULT_VERSION = "2025-06-18";
    private static final Pattern VERSION = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern SESSION = Pattern.compile("[!-~]{1,200}");

    /** What a tool call gave: the server's own text, flagged when it says it failed; the token facts of the call. */
    public record Called(boolean error, String text, boolean truncated, String jti, String audience) {
        /** The only line the journal keeps; the token itself is never in it. */
        public String firstLine(String server, String tool) {
            return "MCP " + server + " " + tool + " -> " + (error ? "error" : "ok") + " (exchanged token " + jti
                    + " for " + audience + ")";
        }
    }

    private final ResourceBroker broker;
    private final McpServers servers;
    private final ResourceCatalogue catalogue;
    private final ObjectMapper json;

    @Inject
    McpClient(ResourceBroker broker, McpServers servers, ResourceCatalogue catalogue, ObjectMapper json) {
        this.broker = broker;
        this.servers = servers;
        this.catalogue = catalogue;
        this.json = json;
    }

    /** The tools the server lists now, for the director to pin; no tick is read or needed. */
    public List<Listed> listTools(SessionClaims caller, String server) {
        McpServer s = declared(caller, server);
        Map<String, String> headers = handshake(caller, s);
        Answer a = rpc(caller, s, 2, "tools/list", json.createObjectNode(), headers);
        if (a.error() != null) throw new Refusal("MCP_PROTOCOL", s.resource() + " refused to list its tools");
        JsonNode result = a.result();
        List<Listed> out = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (JsonNode t : result.path("tools")) seen.merge(t.path("name").asText(""), 1, Integer::sum);
        for (JsonNode t : result.path("tools")) {
            String name = t.path("name").asText("");
            // A name is one line the journal keeps and the agent types back: no other shape is pinned.
            // A name listed twice is ambiguous (which definition did the director read?): neither is pinned.
            if (!NAME.matcher(name).matches() || seen.get(name) > 1) continue;
            JsonNode schema = t.path("inputSchema").isObject() ? t.path("inputSchema") : json.createObjectNode();
            out.add(new Listed(name, t.path("description").asText(""), schema));
        }
        return out;
    }

    /** One call of a ticked tool; the caller is the session, the tool is checked against what the director ticked. */
    public Called callTool(SessionClaims caller, String server, String tool, JsonNode arguments) {
        McpServer s = declared(caller, server);
        if (s.tool(tool == null ? "" : tool).filter(t -> t.ticked()).isEmpty())
            throw new Refusal(
                    "MCP_TOOL_NOT_ALLOWED", "The director has not allowed " + s.resource() + " to run that tool");
        if (arguments != null && !arguments.isMissingNode() && !arguments.isNull() && !arguments.isObject())
            throw new Refusal("INVALID_ARGUMENTS", "arguments is a JSON object");
        ObjectNode params = json.createObjectNode().put("name", tool);
        params.set("arguments", arguments == null || !arguments.isObject() ? json.createObjectNode() : arguments);
        Map<String, String> headers = handshake(caller, s);
        Answer a = rpc(caller, s, 2, "tools/call", params, headers);
        // The server may write the token back in an escaped form the broker could not see in the raw body.
        if (a.error() != null)
            return new Called(
                    true,
                    a.scrub()
                            .apply("JSON-RPC error " + a.error().path("code").asText("?") + ": "
                                    + a.error().path("message").asText("")),
                    a.truncated(),
                    a.jti(),
                    a.audience());
        JsonNode result = a.result();
        return new Called(
                result.path("isError").asBoolean(false),
                a.scrub().apply(contentText(result)),
                a.truncated(),
                a.jti(),
                a.audience());
    }

    /** The text blocks of a result, one per line; any other kind of block is named, not shown. */
    private static String contentText(JsonNode result) {
        if (!result.path("content").isArray()) return result.toString();
        StringBuilder text = new StringBuilder();
        for (JsonNode c : result.path("content")) {
            if (!text.isEmpty()) text.append('\n');
            boolean plain = c.path("type").asText().equals("text");
            text.append(
                    plain ? c.path("text").asText("") : "[" + c.path("type").asText("unknown") + " content not shown]");
        }
        return text.toString();
    }

    /** The server must be declared and granted to this caller before anything else is said about it. */
    private McpServer declared(SessionClaims caller, String server) {
        // A fixed message: what the agent typed is not echoed into an error the journal keeps in clear.
        McpServer s = servers.usable(server == null ? "" : server)
                .orElseThrow(() -> new Refusal("MCP_SERVER_UNKNOWN", "No such MCP server is declared"));
        if (!catalogue.allowed(caller.agent(), caller.mission(), s.resource()))
            throw new Refusal("RESOURCE_NOT_GRANTED", "This agent or mission may not use " + s.resource());
        return s;
    }

    /** {@code initialize}: the headers the next request carries (protocol version, the session id if one is given). */
    private Map<String, String> handshake(SessionClaims caller, McpServer s) {
        ObjectNode params = json.createObjectNode().put("protocolVersion", DEFAULT_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "floor42").put("version", "1");
        Answer a = rpc(caller, s, 1, "initialize", params, Map.of());
        if (a.error() != null) throw new Refusal("MCP_PROTOCOL", s.resource() + " refused to initialize");
        Map<String, String> next = new LinkedHashMap<>();
        String version = a.result().path("protocolVersion").asText("");
        next.put("MCP-Protocol-Version", VERSION.matcher(version).matches() ? version : DEFAULT_VERSION);
        if (a.session() != null) {
            if (!SESSION.matcher(a.session()).matches())
                throw new Refusal("MCP_PROTOCOL", s.resource() + " gave a session id that is not usable");
            next.put("Mcp-Session-Id", a.session());
        }
        // shortcut: the session is never closed with a DELETE, upgrade if a real server limits open sessions
        initialized(caller, s, next);
        return next;
    }

    /** The notification the specification asks for before any other request; the server answers 202 and no body. */
    private void initialized(SessionClaims caller, McpServer s, Map<String, String> headers) {
        Map<String, String> sent = new LinkedHashMap<>(headers);
        sent.put("Accept", ACCEPT);
        String body = json.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized")
                .toString();
        BrokerResult r = broker.call(caller, s.resource(), "POST", s.path(), body, sent);
        if (r.status() < 200 || r.status() > 299)
            throw new Refusal("MCP_PROTOCOL", s.resource() + " answered HTTP " + r.status() + " to initialized");
    }

    private record Answer(
            JsonNode result,
            JsonNode error,
            String session,
            boolean truncated,
            String jti,
            String audience,
            UnaryOperator<String> scrub) {}

    private Answer rpc(
            SessionClaims caller, McpServer s, int id, String method, JsonNode params, Map<String, String> more) {
        ObjectNode request =
                json.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        request.set("params", params);
        Map<String, String> headers = new LinkedHashMap<>(more);
        headers.put("Accept", ACCEPT);
        BrokerResult r = broker.call(caller, s.resource(), "POST", s.path(), request.toString(), headers);
        if (r.status() < 200 || r.status() > 299)
            throw new Refusal("MCP_PROTOCOL", s.resource() + " answered HTTP " + r.status() + " to " + method);
        JsonNode message = message(r, id);
        if (message == null)
            throw new Refusal("MCP_PROTOCOL", s.resource() + " did not answer " + method + " in JSON-RPC");
        JsonNode error = message.path("error").isObject() ? message.path("error") : null;
        if (error == null && !message.path("result").isObject())
            throw new Refusal("MCP_PROTOCOL", s.resource() + " answered " + method + " without a result");
        return new Answer(
                message.path("result"),
                error,
                r.headers().get("mcp-session-id"),
                r.truncated(),
                r.jti(),
                r.audience(),
                r::scrub);
    }

    /** The data of each event of a server-sent stream: its {@code data:} lines joined, one entry per blank line. */
    private static List<String> events(String body) {
        List<String> out = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : (body + "\n\n").split("\r?\n", -1)) {
            if (line.startsWith("data:"))
                data.append(data.isEmpty() ? "" : "\n").append(line.substring(5).strip());
            else if (line.isEmpty() && !data.isEmpty()) {
                out.add(data.toString());
                data.setLength(0);
            }
        }
        return out;
    }

    /** The JSON-RPC response of request {@code id}, from a plain JSON body or from an event stream; null when none. */
    private JsonNode message(BrokerResult r, int id) {
        String type = r.headers().getOrDefault("content-type", "").toLowerCase(java.util.Locale.ROOT);
        for (String c : type.contains("text/event-stream") ? events(r.body()) : List.of(r.body())) {
            JsonNode node;
            try {
                node = json.readTree(c);
            } catch (IOException e) {
                // Handled: an event that is not JSON is not the answer; the next one may be
                continue;
            }
            List<JsonNode> messages = new ArrayList<>();
            if (node != null && node.isArray()) node.forEach(messages::add);
            else if (node != null) messages.add(node);
            for (JsonNode m : messages)
                if (m.isObject() && m.path("id").asInt(-1) == id && (m.has("result") || m.has("error"))) return m;
        }
        return null;
    }
}
