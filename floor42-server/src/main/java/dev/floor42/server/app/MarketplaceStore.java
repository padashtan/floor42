package dev.floor42.server.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.floor42.server.app.PluginViews.InstalledPlugin;
import dev.floor42.server.app.PluginViews.MarketplaceView;
import dev.floor42.server.app.PluginViews.PluginEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * What the plugin folders hold (ADR-0040): the marketplaces each target added, their listings (the clones Claude Code
 * keeps, or a copy Floor42 read from GitHub on the director's click), the official ones Floor42 ships, and the plugins
 * installed in each target. Reading a listing from GitHub is the only network call, made only when asked.
 */
@ApplicationScoped
public class MarketplaceStore {

    private static final Logger LOG = Logger.getLogger(MarketplaceStore.class);

    /** A marketplace.json is read up to this size. */
    static final int MAX_MARKETPLACE_BYTES = 4 * 1024 * 1024;

    // Never a leading dash: a name from a marketplace listing is an argument of claude's command line, where it would
    // read as an option.
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9._][A-Za-z0-9._-]{0,99}");
    static final Pattern REPO = Pattern.compile("[A-Za-z0-9._][A-Za-z0-9._-]{0,99}/[A-Za-z0-9._-]{1,100}");
    private static final Pattern GITHUB_URL =
            Pattern.compile("^https://github\\.com/([A-Za-z0-9._-]+/[A-Za-z0-9._-]+?)(\\.git)?/?$");

    /** Fetches a marketplace's listing over the network: the director's click only. Tests swap it. */
    @FunctionalInterface
    public interface Fetcher {
        byte[] fetch(URI uri, int max) throws IOException;
    }

    /** A marketplace's listing, where it was read and when. {@code folder} is the clone it came from, if any. */
    record Listing(JsonNode json, String from, Instant at, Path folder) {}

    /** A marketplace as Floor42 knows it: its source, who added it, and where its listing comes from. */
    static final class Market {
        final String name;
        JsonNode source;
        final List<String> knownIn = new ArrayList<>();
        final Map<String, Path> clones = new LinkedHashMap<>();
        boolean official;

        Market(String name) {
            this.name = name;
        }
    }

    private final PluginFolders folders;
    private final ManifestReader reader;
    private final String listingBase;
    private volatile Fetcher fetcher = MarketplaceStore::httpFetch;

    @Inject
    public MarketplaceStore(
            PluginFolders folders,
            ManifestReader reader,
            @ConfigProperty(name = "floor42.plugins.listing-base", defaultValue = "https://raw.githubusercontent.com")
                    String listingBase) {
        this.folders = folders;
        this.reader = reader;
        this.listingBase = listingBase;
    }

    void useForTests(Fetcher fake) {
        fetcher = fake == null ? MarketplaceStore::httpFetch : fake;
    }

    /** Every marketplace: those of Floor42's folder, those of the director's Claude Code, the official ones. */
    Map<String, Market> markets() {
        Map<String, Market> out = new LinkedHashMap<>();
        for (String target : List.of(Plugins.FLOOR42, Plugins.CLAUDE)) {
            JsonNode known = reader.readJson(
                    folders.pluginsRoot(target).resolve("known_marketplaces.json"), ClaudeAssets.MAX_MANIFEST_BYTES);
            if (known == null) continue;
            for (var e : known.properties()) {
                if (!NAME.matcher(e.getKey()).matches()) continue;
                Market m = out.computeIfAbsent(e.getKey(), Market::new);
                m.knownIn.add(target);
                if (m.source == null) m.source = e.getValue().path("source");
                String location = e.getValue().path("installLocation").asText("");
                if (!location.isBlank()) m.clones.put(target, Path.of(location));
            }
        }
        for (JsonNode o : reader.officialMarketplaces()) {
            String name = o.path("name").asText("");
            if (!NAME.matcher(name).matches()) continue;
            Market m = out.computeIfAbsent(name, Market::new);
            m.official = true;
            if (m.source == null) m.source = o.path("source");
        }
        return out;
    }

    /** The listing: the newest of the clones Claude Code keeps and the copy read from GitHub. */
    Listing listing(Market m) {
        Listing best = null;
        for (var c : m.clones.entrySet()) {
            Path f = c.getValue().resolve(".claude-plugin").resolve("marketplace.json");
            best = newer(best, read(f, c.getKey().toLowerCase(Locale.ROOT), c.getValue()));
        }
        return newer(best, read(folders.listingFile(m.name), "fetched", null));
    }

    private static Listing newer(Listing a, Listing b) {
        return b != null && (a == null || b.at().isAfter(a.at())) ? b : a;
    }

    private Listing read(Path file, String from, Path clone) {
        JsonNode j = reader.readJson(file, MAX_MARKETPLACE_BYTES);
        if (j == null || !j.path("plugins").isArray()) return null;
        try {
            return new Listing(j, from, Files.getLastModifiedTime(file).toInstant(), clone);
        } catch (IOException e) {
            // Handled: a listing whose date cannot be read is not offered (another copy may be); logged.
            LOG.debugf("Cannot date %s: %s", file, e.getMessage());
            return null;
        }
    }

    MarketplaceView view(Market m, List<InstalledPlugin> installed) {
        Listing l = listing(m);
        List<PluginEntry> plugins = new ArrayList<>();
        String owner =
                l == null ? null : ManifestReader.text(l.json().path("owner").path("name"), 120);
        int count = 0;
        Iterable<JsonNode> listed = l == null ? List.of() : l.json().path("plugins");
        for (JsonNode p : listed) {
            if (count++ >= 1000) break;
            String n = p.path("name").asText("");
            if (NAME.matcher(n).matches()) plugins.add(entry(p, n, m.name, installed));
        }
        return new MarketplaceView(
                m.name,
                addArgument(m.source),
                owner,
                List.copyOf(m.knownIn),
                m.official,
                l == null ? "none" : l.from(),
                l == null ? null : l.at().toString(),
                githubRepo(m.source) != null,
                plugins);
    }

    /** One plugin of a listing, with the targets it is installed in. */
    private static PluginEntry entry(JsonNode p, String name, String market, List<InstalledPlugin> installed) {
        List<String> in = installed.stream()
                .filter(i -> i.plugin().equals(name) && i.marketplace().equals(market))
                .map(InstalledPlugin::target)
                .toList();
        return new PluginEntry(
                name,
                ManifestReader.text(p.path("description"), 400),
                ManifestReader.text(p.path("author").path("name"), 120),
                ManifestReader.text(p.path("version"), 40),
                ManifestReader.text(p.path("category"), 60),
                sourceKind(p.path("source")),
                in);
    }

    /** Reads a marketplace's listing from GitHub, now, and keeps it in Floor42's folder. */
    void refresh(String marketplace, String repo) {
        URI uri = URI.create(listingBase.replaceAll("/+$", "") + "/" + repo + "/HEAD/.claude-plugin/marketplace.json");
        byte[] body;
        try {
            body = fetcher.fetch(uri, MAX_MARKETPLACE_BYTES);
        } catch (IOException e) {
            throw RefusedException.conflict("FETCH_FAILED", "Could not read " + uri + ": " + e.getMessage(), e);
        }
        JsonNode json = reader.parse(body);
        if (json == null || !json.path("plugins").isArray()) {
            throw RefusedException.conflict("FETCH_FAILED", uri + " is not a marketplace listing");
        }
        try {
            Path file = folders.listingFile(marketplace);
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), marketplace, ".part");
            Files.write(tmp, body);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw RefusedException.conflict("FETCH_FAILED", "Could not keep the listing: " + e.getMessage(), e);
        }
    }

    static byte[] httpFetch(URI uri, int max) throws IOException {
        var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
        var request = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        try {
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                byte[] b = in.readNBytes(max + 1);
                if (b.length > max) throw new IOException("larger than " + max + " bytes");
                return b;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    // --- installed plugins -------------------------------------------------------------------------------------------

    /** The plugins installed in a target, from its {@code installed_plugins.json} (version 2). */
    List<InstalledPlugin> installed(String target) {
        Path root = folders.pluginsRoot(target);
        Path realRoot = ClaudeAssets.real(root);
        if (realRoot == null) return List.of();
        JsonNode file = reader.readJson(root.resolve("installed_plugins.json"), ClaudeAssets.MAX_MANIFEST_BYTES);
        if (file == null) return List.of();
        List<InstalledPlugin> out = new ArrayList<>();
        int count = 0;
        for (var e : file.path("plugins").properties()) {
            if (count++ >= ClaudeAssets.MAX_ENTRIES) break;
            int at = e.getKey().lastIndexOf('@');
            if (at > 0) out.add(installed(target, root, realRoot, e.getKey(), at, e.getValue()));
        }
        return out;
    }

    private InstalledPlugin installed(String target, Path root, Path realRoot, String key, int at, JsonNode value) {
        JsonNode first = value.isArray() ? value.path(0) : value;
        String install = first.path("installPath").asText("");
        Path real = install.isBlank() ? null : ClaudeAssets.real(root.resolve(install));
        String problem = real == null ? "unreadable" : !real.startsWith(realRoot) ? "outsidePlugins" : null;
        return new InstalledPlugin(
                target,
                ClaudeAssets.clean(key.substring(0, at), 100),
                ClaudeAssets.clean(key.substring(at + 1), 100),
                first.path("version").isTextual()
                        ? ClaudeAssets.clean(first.path("version").asText(), 40)
                        : null,
                first.path("scope").asText("user"),
                first.path("installedAt").asText(null),
                problem == null ? reader.contents(real, null) : null,
                problem);
    }

    /** The folder of an installed plugin, real path inside its target's plugins folder; empty otherwise. */
    Optional<Path> installedDir(String target, String plugin, String marketplace) {
        Path root = folders.pluginsRoot(target);
        Path realRoot = ClaudeAssets.real(root);
        if (realRoot == null) return Optional.empty();
        JsonNode file = reader.readJson(root.resolve("installed_plugins.json"), ClaudeAssets.MAX_MANIFEST_BYTES);
        if (file == null) return Optional.empty();
        JsonNode entry = file.path("plugins").path(plugin + "@" + marketplace);
        JsonNode first = entry.isArray() ? entry.path(0) : entry;
        String install = first.path("installPath").asText("");
        if (install.isBlank()) return Optional.empty();
        Path real = ClaudeAssets.real(root.resolve(install));
        return real != null && real.startsWith(realRoot) && Files.isDirectory(real)
                ? Optional.of(real)
                : Optional.empty();
    }

    // --- sources -----------------------------------------------------------------------------------------------------

    /** What {@code claude plugin marketplace add} takes for a source: owner/repo, a URL, or a path. */
    static String addArgument(JsonNode source) {
        if (source == null || !source.isObject()) return null;
        return switch (source.path("source").asText("")) {
            case "github" ->
                REPO.matcher(source.path("repo").asText("")).matches()
                        ? source.path("repo").asText()
                        : null;
            case "git", "url" -> {
                String url = source.path("url").asText("");
                yield url.startsWith("https://") || url.startsWith("git@") ? url : null;
            }
            case "directory", "file" -> {
                String path = source.path("path").asText("");
                yield path.isBlank() || path.startsWith("-") ? null : path;
            }
            default -> null;
        };
    }

    /** The owner/repo of a marketplace on GitHub; null when it is elsewhere. */
    static String githubRepo(JsonNode source) {
        if (source == null || !source.isObject()) return null;
        String kind = source.path("source").asText("");
        if (kind.equals("github")) {
            String repo = source.path("repo").asText("");
            return REPO.matcher(repo).matches() ? repo : null;
        }
        if (kind.equals("git") || kind.equals("url")) {
            var m = GITHUB_URL.matcher(source.path("url").asText(""));
            return m.matches() ? m.group(1) : null;
        }
        return null;
    }

    static String sourceKind(JsonNode src) {
        if (src.isTextual()) return "relative";
        return src.isObject() ? ClaudeAssets.clean(src.path("source").asText("unknown"), 30) : "unknown";
    }

    /** A plugin's source as the director reads it. */
    static String sourceText(JsonNode src, JsonNode market) {
        if (src.isTextual()) return src.asText() + " in " + addArgument(market);
        if (!src.isObject()) return null;
        StringBuilder s = new StringBuilder(src.path("source").asText("?")).append(": ");
        for (String f : List.of("repo", "url", "package", "path", "ref", "sha")) {
            if (src.path(f).isTextual())
                s.append(f).append(' ').append(src.path(f).asText()).append(' ');
        }
        return ClaudeAssets.clean(s.toString(), 400);
    }

    /** A relative-path plugin inside a clone Floor42 can read; null otherwise (the listing came from GitHub). */
    static Path localPluginDir(Listing l, JsonNode src) {
        if (l == null || l.folder() == null || !src.isTextual()) return null;
        Path root = ClaudeAssets.real(l.folder());
        if (root == null) return null;
        Path dir = ClaudeAssets.real(root.resolve(src.asText()));
        return dir != null && dir.startsWith(root) && Files.isDirectory(dir) ? dir : null;
    }
}
