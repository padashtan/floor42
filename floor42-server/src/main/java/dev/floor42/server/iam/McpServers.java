package dev.floor42.server.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.floor42.server.iam.ResourceCatalogue.Resource;
import dev.floor42.server.iam.fga.FgaException;
import dev.floor42.server.iam.fga.GrantTuples;
import dev.floor42.server.iam.fga.GrantTuples.Pin;
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
 * the director pinned. One file, {@code <data-dir>/iam/mcp-servers.json}, owner only, read at each call: the pinned
 * definitions. Whether a tool is ticked is not in it any more: a tick is the two tuples
 * {@code mcp_tool:S/T/H#server@resource:S} and {@code mcp_tool:S/T/H#enabled@agent:*} in OpenFGA, journaled as
 * {@code AuthorizationChanged} (ADR-0080). An unreadable file reads as empty: nothing is callable (fail closed). A tool
 * is pinned by the SHA-256 of its name, description and input schema, and the hash is in the tuples' object id: when a
 * refresh shows another definition for a ticked tool, the old tuples are deleted, the new object has none, and the tool
 * is flagged {@code changed} until the director ticks it again.
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
    private final GrantTuples grants;

    @Inject
    McpServers(
            @ConfigProperty(name = "floor42.data-dir") Path dataDir,
            ObjectMapper json,
            ResourceCatalogue catalogue,
            GrantTuples grants) {
        this.file = dataDir.resolve("iam").resolve("mcp-servers.json");
        this.json = json;
        this.catalogue = catalogue;
        this.grants = grants;
    }

    /** The servers with {@code ticked} read from OpenFGA; nothing is ticked on a server pinned at another endpoint. */
    public synchronized List<McpServer> all() {
        return stored().stream().map(this::withTicks).toList();
    }

    private McpServer withTicks(McpServer s) {
        boolean moved = stale(s);
        List<McpTool> tools = s.tools().stream()
                .map(t -> new McpTool(
                        t.name(),
                        t.description(),
                        t.inputSchema(),
                        t.sha256(),
                        !moved && isTicked(s.resource(), t),
                        t.changed()))
                .toList();
        return new McpServer(s.resource(), s.path(), s.baseUrl(), s.audience(), s.pinnedAt(), tools);
    }

    /** A display only: OpenFGA out of reach shows nothing ticked, and the broker's own check refuses the call anyway. */
    private boolean isTicked(String server, McpTool t) {
        try {
            return grants.ticked(server, t.name(), t.sha256());
        } catch (FgaException e) {
            // Handled: shown unticked; the decision is the broker's check, which fails closed on its own
            return false;
        }
    }

    /** A tool the 1.2.0 file recorded as ticked, still pinned at the endpoint it was ticked at. */
    public record LegacyTick(String server, String tool, String sha256) {}

    /** The ticks of the old file, for the one-shot import; none for a server pinned at another endpoint. */
    public synchronized List<LegacyTick> legacyTicks() {
        List<LegacyTick> out = new ArrayList<>();
        for (McpServer s : stored())
            if (!stale(s))
                for (McpTool t : s.tools()) if (t.ticked()) out.add(new LegacyTick(s.resource(), t.name(), t.sha256()));
        return out;
    }

    /** After the import: the file keeps the definitions only. */
    public synchronized void dropStoredTicks() {
        write(strict());
    }

    /** The pinned definitions as the file holds them ({@code ticked} is never stored). */
    public synchronized List<McpServer> stored() {
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

    /** The server as declared (ticks are OpenFGA's: see {@link #pinnedTool}); the broker decides the call. */
    public synchronized Optional<McpServer> usable(String resource) {
        Optional<McpServer> found =
                stored().stream().filter(s -> s.resource().equals(resource)).findFirst();
        if (found.isEmpty() || !stale(found.get())) return found;
        // A server whose resource moved: what was approved at the old endpoint is deleted now, and the tools say so
        try {
            McpServer cleared = unticked(found.get());
            return Optional.of(cleared.equals(found.get()) ? found.get() : replace(cleared));
        } catch (FgaException e) {
            // Handled: the tuples stay for now, and pinnedTool refuses a server pinned at another endpoint all the same
            return found;
        }
    }

    /**
     * The tool as pinned, when its server still has the endpoint it was pinned at; empty otherwise, whatever OpenFGA
     * holds for it. Whether it is ticked is {@link ResourceCatalogue#toolAllowed}'s answer, for this definition's hash.
     */
    public Optional<McpTool> pinnedTool(String resource, String tool) {
        return usable(resource).filter(s -> !stale(s)).flatMap(s -> s.tool(tool == null ? "" : tool));
    }

    private boolean stale(McpServer s) {
        return catalogue
                .find(s.resource())
                .map(r -> !r.baseUrl().toString().equals(s.baseUrl())
                        || !r.audience().equals(s.audience()))
                .orElse(true);
    }

    /**
     * The server with nothing ticked: the tuples of its ticked tools are deleted first (OpenFGA out of reach refuses the
     * write, so an old approval can never come back), and those tools are flagged {@code changed}.
     */
    private McpServer unticked(McpServer s) {
        List<McpTool> tools = new ArrayList<>();
        List<Pin> gone = new ArrayList<>();
        for (McpTool t : s.tools()) {
            boolean was = grants.ticked(s.resource(), t.name(), t.sha256());
            if (was) gone.add(new Pin(t.name(), t.sha256()));
            tools.add(new McpTool(t.name(), t.description(), t.inputSchema(), t.sha256(), false, was || t.changed()));
        }
        grants.untick(s.resource(), gone);
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
        checkPath(path);
        if (!r.methods().contains("POST") || r.pathPrefixes().stream().noneMatch(p -> under(path, p)))
            throw refused("MCP_RESOURCE_UNFIT");
        List<McpServer> next = new ArrayList<>(strict());
        Optional<McpServer> before =
                next.stream().filter(s -> s.resource().equals(r.name())).findFirst();
        next.removeIf(s -> s.resource().equals(r.name()));
        String base = r.baseUrl().toString();
        McpServer fresh = new McpServer(r.name(), path, base, r.audience(), null, List.of());
        // Another path is another endpoint: what was approved at the old one goes with it
        before.filter(s -> !s.path().equals(path)).ifPresent(this::unticked);
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
        return withTicks(kept);
    }

    private static void checkPath(String path) {
        if (path == null
                || !PATH.matcher(path).matches()
                || path.contains("//")
                || List.of(path.split("/")).stream().anyMatch(NO_STEP::contains)) throw refused("INVALID_MCP_SERVER");
    }

    /** The server and its ticks go: its tuples are deleted first, so a server declared again starts with nothing ticked. */
    public synchronized void remove(String resource) {
        List<McpServer> next = new ArrayList<>(strict());
        next.stream().filter(s -> s.resource().equals(resource)).findFirst().ifPresent(this::unticked);
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
        List<Pin> gone = new ArrayList<>();
        for (Listed l : listed) {
            String hash = hash(l);
            McpTool old = server.tool(l.name()).orElse(null);
            boolean same = old != null && !moved && old.sha256().equals(hash);
            boolean wasTicked = old != null && grants.ticked(resource, old.name(), old.sha256());
            if (wasTicked && !same) gone.add(new Pin(old.name(), old.sha256()));
            tools.add(new McpTool(
                    l.name(),
                    l.description(),
                    l.inputSchema(),
                    hash,
                    false,
                    old != null && (same ? old.changed() : wasTicked || old.changed())));
        }
        // A tool the server no longer lists keeps no approval: it could come back with the same definition
        for (McpTool old : server.tools())
            if (listed.stream().noneMatch(l -> l.name().equals(old.name()))
                    && grants.ticked(resource, old.name(), old.sha256())) gone.add(new Pin(old.name(), old.sha256()));
        grants.untick(resource, gone);
        return withTicks(replace(new McpServer(
                server.resource(), server.path(), r.baseUrl().toString(), r.audience(), now.toString(), tools)));
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
        McpServer server = checkTicks(resource, ticks);
        grants.setTicks(
                resource,
                server.tools().stream().map(t -> new Pin(t.name(), t.sha256())).toList(),
                ticks.keySet());
        List<McpTool> tools = server.tools().stream()
                .map(t -> new McpTool(
                        t.name(),
                        t.description(),
                        t.inputSchema(),
                        t.sha256(),
                        false,
                        !ticks.containsKey(t.name()) && t.changed()))
                .toList();
        return withTicks(replace(new McpServer(
                server.resource(), server.path(), server.baseUrl(), server.audience(), server.pinnedAt(), tools)));
    }

    /** The refusals of {@link #tick}, without writing: a step-up is asked only for a tick that would be accepted. */
    public synchronized McpServer checkTicks(String resource, Map<String, String> ticks) {
        McpServer server = strictFind(resource);
        if (stale(server)) throw refused("MCP_ENDPOINT_CHANGED");
        for (var t : ticks.entrySet()) {
            McpTool pinned = server.tool(t.getKey()).orElseThrow(() -> refused("UNKNOWN_MCP_TOOL"));
            if (!pinned.sha256().equals(t.getValue())) throw refused("MCP_TOOL_CHANGED");
        }
        return server;
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

    /** What the file keeps of a server: ticks are OpenFGA's, a stored {@code ticked} would be a second truth. */
    private static McpServer withoutTicks(McpServer s) {
        List<McpTool> tools = s.tools().stream()
                .map(t -> new McpTool(t.name(), t.description(), t.inputSchema(), t.sha256(), false, t.changed()))
                .toList();
        return new McpServer(s.resource(), s.path(), s.baseUrl(), s.audience(), s.pinnedAt(), tools);
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
            Files.writeString(
                    tmp,
                    json.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(
                                    value.stream().map(McpServers::withoutTicks).toList()));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
