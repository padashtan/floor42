package dev.floor42.server.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
import dev.floor42.server.security.OwnerOnly;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The remote MCP servers agents may use (ADR-0072): a catalogue resource plus the path of its endpoint, and the tools
 * the director pinned and ticked. One file, {@code <data-dir>/iam/mcp-servers.json}, owner only, read at each call, not
 * journaled (ADR-0064 point 12). An unreadable file reads as empty: nothing is callable (fail closed). A tool is
 * pinned by the SHA-256 of its name, description and input schema: when a refresh shows another definition for a
 * ticked tool, it is unticked and flagged {@code changed} until the director ticks it again.
 */
@ApplicationScoped
public class McpServers {

    /** A listed tool as the remote server describes it, before pinning. */
    public record Listed(String name, String description, JsonNode inputSchema) {}

    /** @param ticked the director allows agents to call it; @param changed its definition changed since it was ticked */
    public record McpTool(
            String name, String description, JsonNode inputSchema, String sha256, boolean ticked, boolean changed) {}

    /**
     * @param baseUrl the resource's address when the tools were pinned; @param audience its audience then: the tools
     *     are what the director read at that endpoint, and mean nothing at another one
     * @param pinnedAt when the tools were last refreshed (ISO-8601), null before the first refresh
     */
    public record McpServer(
            String resource, String path, String baseUrl, String audience, String pinnedAt, List<McpTool> tools) {
        public McpServer {
            tools = tools == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(tools));
        }

        public Optional<McpTool> tool(String name) {
            return tools.stream().filter(t -> t.name().equals(name)).findFirst();
        }
    }

    private static final Pattern PATH = Pattern.compile("/[!-~&&[^?#\\\\@%]]*");
    private static final Set<String> NO_STEP = Set.of("..", ".");
    private static final ObjectMapper CANON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final Path file;
    private final ObjectMapper json;
    private final ResourceCatalogue catalogue;

    @Inject
    McpServers(
            @ConfigProperty(name = "floor42.data-dir") Path dataDir, ObjectMapper json, ResourceCatalogue catalogue) {
        this.file = dataDir.resolve("iam").resolve("mcp-servers.json");
        this.json = json;
        this.catalogue = catalogue;
    }

    public synchronized List<McpServer> all() {
        try {
            return Files.exists(file)
                    ? json.readValue(file.toFile(), new TypeReference<List<McpServer>>() {})
                    : List.of();
        } catch (IOException | RuntimeException e) {
            // Handled: a damaged file reads as empty, so nothing is callable
            return List.of();
        }
    }

    public Optional<McpServer> find(String resource) {
        return all().stream().filter(s -> s.resource().equals(resource)).findFirst();
    }

    /** The server as it may be used now: when its resource no longer has the endpoint it was pinned at, nothing is ticked. */
    public synchronized Optional<McpServer> usable(String resource) {
        Optional<McpServer> found = find(resource);
        if (found.isEmpty() || !stale(found.get())) return found;
        McpServer s = found.get();
        return Optional.of(s.tools().stream().noneMatch(McpTool::ticked) ? s : replace(unticked(s)));
    }

    private boolean stale(McpServer s) {
        return catalogue
                .find(s.resource())
                .map(r -> !r.baseUrl().toString().equals(s.baseUrl())
                        || !r.audience().equals(s.audience()))
                .orElse(true);
    }

    private static McpServer unticked(McpServer s) {
        List<McpTool> tools = s.tools().stream()
                .map(t -> new McpTool(
                        t.name(), t.description(), t.inputSchema(), t.sha256(), false, t.ticked() || t.changed()))
                .toList();
        return new McpServer(s.resource(), s.path(), s.baseUrl(), s.audience(), s.pinnedAt(), tools);
    }

    /** The file as it is, for a write: a damaged one is refused, never overwritten (MCP_CATALOGUE_DAMAGED). */
    private List<McpServer> strict() {
        try {
            return Files.exists(file)
                    ? json.readValue(file.toFile(), new TypeReference<List<McpServer>>() {})
                    : List.of();
        } catch (IOException | RuntimeException e) {
            // Handled: refused with a code; the director repairs or deletes the file
            throw new IllegalArgumentException("MCP_CATALOGUE_DAMAGED", e);
        }
    }

    /**
     * Declares (or moves) the server of a catalogue resource; its tools stay when only the path is the same.
     * {@code IllegalArgumentException} carries the refusal code: UNKNOWN_RESOURCE, INVALID_MCP_SERVER, MCP_RESOURCE_UNFIT.
     */
    public synchronized McpServer put(String resource, String path) {
        Resource r = catalogue.find(resource == null ? "" : resource).orElseThrow(() -> refused("UNKNOWN_RESOURCE"));
        if (path == null
                || !PATH.matcher(path).matches()
                || path.contains("//")
                || List.of(path.split("/")).stream().anyMatch(NO_STEP::contains)) throw refused("INVALID_MCP_SERVER");
        if (!r.methods().contains("POST") || r.pathPrefixes().stream().noneMatch(p -> under(path, p)))
            throw refused("MCP_RESOURCE_UNFIT");
        List<McpServer> next = new ArrayList<>(strict());
        Optional<McpServer> before =
                next.stream().filter(s -> s.resource().equals(r.name())).findFirst();
        next.removeIf(s -> s.resource().equals(r.name()));
        String base = r.baseUrl().toString();
        McpServer fresh = new McpServer(r.name(), path, base, r.audience(), null, List.of());
        McpServer kept = before.filter(s -> s.path().equals(path))
                .map(s -> stale(s)
                        ? new McpServer(
                                s.resource(),
                                path,
                                base,
                                r.audience(),
                                s.pinnedAt(),
                                unticked(s).tools())
                        : s)
                .orElse(fresh);
        next.add(kept);
        write(next);
        return kept;
    }

    public synchronized void remove(String resource) {
        List<McpServer> next = new ArrayList<>(strict());
        next.removeIf(s -> s.resource().equals(resource));
        write(next);
    }

    /** What a refresh found: new tools arrive unticked; a ticked tool whose definition changed is unticked and flagged. */
    public synchronized McpServer pin(String resource, List<Listed> listed, Instant now) {
        McpServer server = strictFind(resource);
        Resource r = catalogue.find(resource).orElseThrow(() -> refused("UNKNOWN_RESOURCE"));
        // Tools read at another endpoint than the one the director ticked at are all to be approved again.
        boolean moved = stale(server);
        List<McpTool> tools = new ArrayList<>();
        for (Listed l : listed) {
            String hash = hash(l);
            McpTool old = server.tool(l.name()).orElse(null);
            boolean same = old != null && !moved && old.sha256().equals(hash);
            tools.add(new McpTool(
                    l.name(),
                    l.description(),
                    l.inputSchema(),
                    hash,
                    same && old.ticked(),
                    old != null && (same ? old.changed() : old.ticked() || old.changed())));
        }
        return replace(new McpServer(
                server.resource(), server.path(), r.baseUrl().toString(), r.audience(), now.toString(), tools));
    }

    private McpServer strictFind(String resource) {
        return strict().stream()
                .filter(s -> s.resource().equals(resource))
                .findFirst()
                .orElseThrow(() -> refused("UNKNOWN_MCP_SERVER"));
    }

    /**
     * The ticked set replaced by {@code ticks} (tool name to the SHA-256 the director saw); ticking approves the
     * definition as pinned, so a tool that is not pinned or whose hash differs is refused: UNKNOWN_MCP_TOOL,
     * MCP_TOOL_CHANGED. A server pinned at another endpoint than its resource's has to be refreshed first.
     */
    public synchronized McpServer tick(String resource, Map<String, String> ticks) {
        McpServer server = strictFind(resource);
        if (stale(server)) throw refused("MCP_ENDPOINT_CHANGED");
        for (var t : ticks.entrySet()) {
            McpTool pinned = server.tool(t.getKey()).orElseThrow(() -> refused("UNKNOWN_MCP_TOOL"));
            if (!pinned.sha256().equals(t.getValue())) throw refused("MCP_TOOL_CHANGED");
        }
        List<McpTool> tools = server.tools().stream()
                .map(t -> new McpTool(
                        t.name(),
                        t.description(),
                        t.inputSchema(),
                        t.sha256(),
                        ticks.containsKey(t.name()),
                        !ticks.containsKey(t.name()) && t.changed()))
                .toList();
        return replace(new McpServer(
                server.resource(), server.path(), server.baseUrl(), server.audience(), server.pinnedAt(), tools));
    }

    private McpServer replace(McpServer s) {
        List<McpServer> next = new ArrayList<>(strict());
        next.removeIf(x -> x.resource().equals(s.resource()));
        next.add(s);
        write(next);
        return s;
    }

    /** SHA-256 of name, description and input schema (keys sorted, so a reordered schema is the same tool). */
    static String hash(Listed l) {
        try {
            String schema = CANON.writeValueAsString(CANON.convertValue(l.inputSchema(), Object.class));
            String text = l.name() + "\n" + (l.description() == null ? "" : l.description()) + "\n" + schema;
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("A tool cannot be hashed", e);
        }
    }

    private static boolean under(String path, String prefix) {
        return prefix.endsWith("/") ? path.startsWith(prefix) : path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static IllegalArgumentException refused(String code) {
        return new IllegalArgumentException(code);
    }

    private void write(List<McpServer> value) {
        try {
            Files.createDirectories(file.getParent());
            OwnerOnly.restrict(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            OwnerOnly.restrict(tmp);
            Files.writeString(tmp, json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
