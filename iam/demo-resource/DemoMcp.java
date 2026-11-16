import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A stand-in remote MCP server for trying governed MCP (ADR-0072), dev only: java iam/demo-resource/DemoMcp.java
 * listens on 127.0.0.1:7498, path /mcp, plain JSON answers. It lists one tool, "echo", and answers a call with its
 * arguments. It does not check the token (DemoResource does that); it only says whether a Bearer header came, and
 * never logs it. Catalogue it with PUT /api/iam/resources (local true, methods ["POST"], pathPrefixes ["/mcp"]),
 * then PUT /api/iam/mcp-servers {"resource": "...", "path": "/mcp"}.
 */
public class DemoMcp {

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 7498), 0);
        server.createContext("/mcp", x -> {
            String in = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String auth = x.getRequestHeaders().getFirst("Authorization");
            String id = in.replaceAll("(?s).*\"id\"\s*:\s*(\d+).*", "$1");
            String result;
            if (in.contains("\"initialize\"")) {
                x.getResponseHeaders().add("Mcp-Session-Id", "demo-session");
                result = "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}}}";
            } else if (in.contains("\"tools/list\"")) {
                result = "{\"tools\":[{\"name\":\"echo\",\"description\":\"Echoes its arguments\","
                        + "\"inputSchema\":{\"type\":\"object\"}}]}";
            } else {
                result = "{\"content\":[{\"type\":\"text\",\"text\":\"bearer="
                        + (auth != null && auth.startsWith("Bearer ")) + " " + in.replace("\"", "'") + "\"}]}";
            }
            System.out.println(x.getRequestMethod() + " " + x.getRequestURI() + " bearer=" + (auth != null));
            byte[] out = ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(200, out.length);
            x.getResponseBody().write(out);
            x.close();
        });
        server.start();
        System.out.println("Demo MCP server on http://127.0.0.1:7498/mcp");
    }
}
