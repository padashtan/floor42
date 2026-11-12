package dev.floor42.server.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.app.PluginViews.Contents;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jboss.logging.Logger;

/**
 * Reads what plugins and marketplaces declare on disk (ADR-0040): JSON files read with a size cap, a plugin folder's
 * standard layout, its licence's first line. What cannot be read is "not there" for the caller, and logged.
 */
@ApplicationScoped
public class ManifestReader {

    private static final Logger LOG = Logger.getLogger(ManifestReader.class);

    private final ObjectMapper json;

    @Inject
    public ManifestReader(ObjectMapper json) {
        this.json = json;
    }

    /** The JSON in {@code file}, or null when it is missing, larger than {@code max} or not JSON. */
    JsonNode readJson(Path file, int max) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] b = in.readNBytes(max + 1);
            if (b.length > max) return null;
            return json.readTree(b);
        } catch (IOException | RuntimeException e) {
            // Handled: an unreadable or broken file is read as absent (the view shows nothing from it); logged.
            LOG.debugf("Cannot read %s as JSON: %s", file, e.getMessage());
            return null;
        }
    }

    /** JSON bytes fetched from elsewhere, or null when they are not JSON. */
    JsonNode parse(byte[] body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            // Handled: the caller refuses what is not a listing (FETCH_FAILED); the parser's message is logged.
            LOG.debugf("Not JSON: %s", e.getMessage());
            return null;
        }
    }

    /** The official marketplaces Floor42 ships in {@code catalogue.json}. */
    List<JsonNode> officialMarketplaces() {
        try (InputStream in = ManifestReader.class.getResourceAsStream("/catalogue.json")) {
            if (in == null) return List.of();
            List<JsonNode> out = new ArrayList<>();
            json.readTree(in).path("marketplaces").forEach(out::add);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("catalogue.json of the build cannot be read", e);
        }
    }

    /** A plugin folder's contents by its standard layout; {@code entry} null reads its own manifest only. */
    Contents contents(Path dir, JsonNode entry) {
        JsonNode manifest = manifest(dir);
        List<String> skills = new ArrayList<>();
        for (Path d : list(dir.resolve("skills"))) {
            if (Files.isRegularFile(d.resolve("SKILL.md")))
                skills.add(d.getFileName().toString());
        }
        boolean hooks = Files.exists(dir.resolve("hooks").resolve("hooks.json"))
                || declares(manifest, "hooks")
                || declares(entry, "hooks");
        boolean mcp = Files.exists(dir.resolve(".mcp.json"))
                || declares(manifest, "mcpServers")
                || declares(entry, "mcpServers");
        boolean lsp = Files.exists(dir.resolve(".lsp.json"))
                || declares(manifest, "lspServers")
                || declares(entry, "lspServers");
        return new Contents(
                skills, mdStems(dir.resolve("agents")), mdStems(dir.resolve("commands")), hooks, mcp, lsp, true);
    }

    /** A plugin folder's {@code .claude-plugin/plugin.json}, or null. */
    JsonNode manifest(Path dir) {
        return readJson(dir.resolve(".claude-plugin").resolve("plugin.json"), ClaudeAssets.MAX_MANIFEST_BYTES);
    }

    /** What a marketplace entry alone declares, its files out of reach. */
    static Contents declared(JsonNode entry) {
        return new Contents(
                names(entry.path("skills")),
                names(entry.path("agents")),
                names(entry.path("commands")),
                declares(entry, "hooks"),
                declares(entry, "mcpServers"),
                declares(entry, "lspServers"),
                false);
    }

    private static boolean declares(JsonNode n, String field) {
        if (n == null || !n.has(field)) return false;
        JsonNode v = n.get(field);
        return !(v.isNull()
                || v.isEmpty() && (v.isArray() || v.isObject())
                || v.isTextual() && v.asText().isBlank());
    }

    private static List<String> names(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (!n.isArray()) return out;
        for (JsonNode e : n) {
            String s = e.asText("").replace('\\', '/');
            s = s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
            s = s.contains("/") ? s.substring(s.lastIndexOf('/') + 1) : s;
            s = s.endsWith(".md") ? s.substring(0, s.length() - 3) : s;
            if (!s.isBlank()) out.add(ClaudeAssets.clean(s, 120));
        }
        return out;
    }

    private static List<String> mdStems(Path dir) {
        List<String> out = new ArrayList<>();
        for (Path f : list(dir)) {
            String n = f.getFileName().toString();
            if (Files.isRegularFile(f) && n.toLowerCase(Locale.ROOT).endsWith(".md")) {
                out.add(n.substring(0, n.length() - 3));
            }
        }
        return out;
    }

    private static List<Path> list(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.sorted().limit(ClaudeAssets.MAX_ENTRIES).toList();
        } catch (IOException e) {
            // Handled: a folder that cannot be listed holds nothing Floor42 can show; logged.
            LOG.debugf("Cannot list %s: %s", dir, e.getMessage());
            return List.of();
        }
    }

    /** The first line of a plugin's LICENSE file, which names the licence in most of them. */
    static String licenseLine(Path dir) {
        for (String n : List.of("LICENSE", "LICENSE.md", "LICENSE.txt", "LICENCE")) {
            Path f = dir.resolve(n);
            if (!Files.isRegularFile(f)) continue;
            try (InputStream in = Files.newInputStream(f)) {
                String head = new String(in.readNBytes(2048), StandardCharsets.UTF_8);
                return head.lines()
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .findFirst()
                        .map(s -> ClaudeAssets.clean(s, 200) + " (first line of " + n + ")")
                        .orElse(null);
            } catch (IOException e) {
                // Handled: an unreadable licence is shown as none (NO_LICENSE warns the director); logged.
                LOG.debugf("Cannot read %s: %s", f, e.getMessage());
                return null;
            }
        }
        return null;
    }

    /** A JSON text value, cleaned and cut at {@code max}; null when absent or blank. */
    static String text(JsonNode n, int max) {
        return n != null && n.isTextual() && !n.asText().isBlank() ? ClaudeAssets.clean(n.asText(), max) : null;
    }
}
