package dev.floor42.server.iam;

import dev.floor42.server.iam.McpServers.McpServer;
import java.util.LinkedHashMap;
import java.util.Map;

/** Ticks tools as the director's screen does: each with the hash of the definition as pinned. */
public final class McpTicks {

    private McpTicks() {}

    public static McpServer tick(McpServers servers, String resource, String... names) {
        McpServer pinned = servers.find(resource).orElseThrow();
        Map<String, String> ticks = new LinkedHashMap<>();
        for (String n : names) ticks.put(n, pinned.tool(n).orElseThrow().sha256());
        return servers.tick(resource, ticks);
    }
}
