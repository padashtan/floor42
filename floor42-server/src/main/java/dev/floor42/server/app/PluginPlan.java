package dev.floor42.server.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.floor42.server.app.PluginViews.CliStatus;
import dev.floor42.server.app.PluginViews.Contents;
import dev.floor42.server.app.PluginViews.InstalledPlugin;
import dev.floor42.server.app.PluginViews.PluginPreview;
import dev.floor42.server.app.PluginViews.PluginRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What an install or an uninstall would run, before anything runs (ADR-0040): the exact commands, what the plugin
 * holds and declares, the warnings the director reads, and the first reason Floor42 would refuse it.
 */
@ApplicationScoped
public class PluginPlan {

    /** A preview and the commands it shows, as arguments of {@code claude}. */
    record Prepared(PluginPreview preview, List<List<String>> steps) {}

    /** What the preview says, filled step by step; the first refusal found is the one kept. */
    private static final class Draft {
        String refused;
        String refusal;
        String source;
        String publisher;
        String version;
        String license;
        String description;
        Contents contents;
        final List<List<String>> steps = new ArrayList<>();

        void refuse(String code, String why) {
            if (refused == null) {
                refused = code;
                refusal = why;
            }
        }
    }

    /** One request, checked: target and action upper-cased, names safe. */
    private record Asked(String target, String action, String plugin, String market) {
        String id() {
            return plugin + "@" + market;
        }
    }

    private final PluginFolders folders;
    private final MarketplaceStore store;
    private final ManifestReader reader;
    private final PluginCli cli;

    @Inject
    public PluginPlan(PluginFolders folders, MarketplaceStore store, ManifestReader reader, PluginCli cli) {
        this.folders = folders;
        this.store = store;
        this.reader = reader;
        this.cli = cli;
    }

    Prepared prepare(PluginRequest r) {
        Asked a = asked(r);
        Optional<InstalledPlugin> present = store.installed(a.target()).stream()
                .filter(i -> i.plugin().equals(a.plugin()) && i.marketplace().equals(a.market()))
                .findFirst();
        MarketplaceStore.Market m = store.markets().get(a.market());
        Draft d = new Draft();
        if (a.action().equals(Plugins.INSTALL)) install(a, present, m, d);
        else uninstall(a, present, m, d);
        if (d.refused == null) {
            CliStatus s = cli.status();
            if (!s.available()) d.refuse("CLAUDE_MISSING", s.detail());
        }
        return new Prepared(preview(a, d), List.copyOf(d.steps));
    }

    private PluginPreview preview(Asked a, Draft d) {
        Path config = folders.configFor(a.target());
        List<String> shown =
                d.steps.stream().map(s -> ClaudeCli.shown(s, config)).toList();
        String digest = Plugins.digest(a.target(), a.action(), a.id(), shown, d.version, d.license, d.contents);
        return new PluginPreview(
                a.target(),
                a.action(),
                a.plugin(),
                a.market(),
                d.source,
                d.publisher,
                d.version,
                d.license,
                d.description,
                d.contents,
                warnings(a, d.contents, d.license),
                shown,
                List.of(),
                d.refused,
                d.refusal,
                digest);
    }

    /** The same preview, naming the missions whose sessions receive something of the plugin (the digest stays). */
    Prepared withSelectedBy(Prepared p, List<String> selectedBy) {
        PluginPreview v = p.preview();
        return new Prepared(
                new PluginPreview(
                        v.target(),
                        v.action(),
                        v.plugin(),
                        v.marketplace(),
                        v.source(),
                        v.publisher(),
                        v.version(),
                        v.license(),
                        v.description(),
                        v.contents(),
                        v.warnings(),
                        v.commands(),
                        List.copyOf(selectedBy),
                        v.refused(),
                        v.refusal(),
                        v.digest()),
                p.steps());
    }

    private static Asked asked(PluginRequest r) {
        if (r == null) throw RefusedException.invalid("INVALID_ARGUMENT", "Which plugin?");
        String target = r.target() == null ? "" : r.target().toUpperCase(Locale.ROOT);
        String action = r.action() == null ? "" : r.action().toUpperCase(Locale.ROOT);
        if (!target.equals(Plugins.FLOOR42) && !target.equals(Plugins.CLAUDE)) {
            throw RefusedException.invalid("INVALID_ARGUMENT", "The target is FLOOR42 or CLAUDE");
        }
        if (!action.equals(Plugins.INSTALL) && !action.equals(Plugins.UNINSTALL)) {
            throw RefusedException.invalid("INVALID_ARGUMENT", "The action is INSTALL or UNINSTALL");
        }
        if (r.plugin() == null || !MarketplaceStore.NAME.matcher(r.plugin()).matches()) {
            throw RefusedException.invalid("INVALID_ARGUMENT", "A plugin name is letters, digits, '.', '_' or '-'");
        }
        if (r.marketplace() == null
                || !MarketplaceStore.NAME.matcher(r.marketplace()).matches()) {
            throw RefusedException.invalid(
                    "INVALID_ARGUMENT", "A marketplace name is letters, digits, '.', '_' or '-'");
        }
        return new Asked(target, action, r.plugin(), r.marketplace());
    }

    private void install(Asked a, Optional<InstalledPlugin> present, MarketplaceStore.Market m, Draft d) {
        if (present.isPresent()) d.refuse("ALREADY_INSTALLED", a.id() + " is already installed there");
        if (m == null || m.source == null || MarketplaceStore.addArgument(m.source) == null) {
            d.refuse("UNKNOWN_MARKETPLACE", "Floor42 knows no marketplace " + a.market());
        } else if (!m.knownIn.contains(a.target())) {
            d.steps.add(List.of("plugin", "marketplace", "add", MarketplaceStore.addArgument(m.source), "--json"));
        }
        MarketplaceStore.Listing l = m == null ? null : store.listing(m);
        JsonNode entry = null;
        if (l != null) {
            for (JsonNode p : l.json().path("plugins")) {
                if (a.plugin().equals(p.path("name").asText())) entry = p;
            }
        }
        if (entry == null) {
            d.refuse(
                    "UNKNOWN_PLUGIN",
                    a.market() + "'s listing has no plugin " + a.plugin()
                            + (l == null ? ": refresh the marketplace first" : ""));
        } else {
            describeEntry(a, m, l, entry, d);
        }
        d.steps.add(List.of("plugin", "install", a.id(), "--json"));
    }

    private void describeEntry(
            Asked a, MarketplaceStore.Market m, MarketplaceStore.Listing l, JsonNode entry, Draft d) {
        JsonNode src = entry.path("source");
        if (src.isObject() && "command".equals(src.path("source").asText()) || entry.has("headersHelper")) {
            d.refuse(
                    "RUNS_A_COMMAND",
                    a.id() + " is installed by running a command its marketplace declares: Floor42"
                            + " does not accept such a command for you; install it yourself in Claude Code if you trust it");
        }
        d.description = ManifestReader.text(entry.path("description"), 600);
        d.publisher = Optional.ofNullable(
                        ManifestReader.text(entry.path("author").path("name"), 120))
                .orElse(ManifestReader.text(l.json().path("owner").path("name"), 120));
        d.version = ManifestReader.text(entry.path("version"), 40);
        d.license = ManifestReader.text(entry.path("license"), 200);
        d.source = MarketplaceStore.sourceText(src, m.source);
        Path local = MarketplaceStore.localPluginDir(l, src);
        if (local == null) {
            d.contents = ManifestReader.declared(entry);
            return;
        }
        d.contents = reader.contents(local, entry);
        JsonNode manifest = reader.manifest(local);
        if (d.version == null && manifest != null) d.version = ManifestReader.text(manifest.path("version"), 40);
        if (d.license == null && manifest != null) d.license = ManifestReader.text(manifest.path("license"), 200);
        if (d.license == null) d.license = ManifestReader.licenseLine(local);
    }

    private void uninstall(Asked a, Optional<InstalledPlugin> present, MarketplaceStore.Market m, Draft d) {
        if (present.isEmpty()) {
            d.refuse("NOT_INSTALLED", a.id() + " is not installed there");
        } else {
            InstalledPlugin p = present.get();
            if (!"user".equals(p.scope())) {
                d.refuse(
                        "NOT_USER_SCOPE",
                        a.id() + " is installed for a project (" + p.scope()
                                + "): uninstall it from that project in Claude Code");
            }
            d.version = p.version();
            d.contents = p.contents();
            store.installedDir(a.target(), a.plugin(), a.market()).ifPresent(dir -> describeInstalled(dir, d));
            d.source = m == null ? null : MarketplaceStore.addArgument(m.source);
        }
        d.steps.add(List.of("plugin", "uninstall", a.id(), "--json"));
    }

    private void describeInstalled(Path dir, Draft d) {
        JsonNode manifest = reader.manifest(dir);
        if (manifest != null) {
            d.description = ManifestReader.text(manifest.path("description"), 600);
            d.publisher = ManifestReader.text(manifest.path("author").path("name"), 120);
            d.license = ManifestReader.text(manifest.path("license"), 200);
        }
        if (d.license == null) d.license = ManifestReader.licenseLine(dir);
    }

    private static List<String> warnings(Asked a, Contents contents, String license) {
        List<String> warnings = new ArrayList<>();
        if (contents != null) {
            if (contents.hooks()) warnings.add("HOOKS");
            if (contents.mcpServers()) warnings.add("MCP");
            if (contents.lspServers()) warnings.add("LSP");
            if (!contents.known()) warnings.add("CONTENTS_UNKNOWN");
        }
        if (license == null) warnings.add("NO_LICENSE");
        if (a.action().equals(Plugins.INSTALL)) {
            if (a.target().equals(Plugins.CLAUDE)) warnings.add("YOUR_CLAUDE");
            warnings.add("NOT_UNTIL_SELECTED");
            if (contents == null
                    || contents.hooks()
                    || contents.mcpServers()
                    || contents.lspServers()
                    || !contents.known()) {
                warnings.add("CODE_NEVER_TO_AGENTS");
            }
        }
        return warnings;
    }
}
