package dev.floor42.server.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The views of plugins and marketplaces (ADR-0040), as {@link Plugins} assembles them and the API serves them. */
public final class PluginViews {

    private PluginViews() {}

    /**
     * What a plugin holds, by its standard layout ({@code skills/}, {@code agents/}, {@code commands/}, {@code
     * hooks/hooks.json}, {@code .mcp.json}, {@code .lsp.json}) and its manifest.
     *
     * @param known false when Floor42 could not look at its files (an external source not installed yet): then only
     *     what the marketplace entry declares is shown, and it may hold more
     */
    public record Contents(
            List<String> skills,
            List<String> agents,
            List<String> commands,
            boolean hooks,
            boolean mcpServers,
            boolean lspServers,
            boolean known) {
        public Contents {
            agents = agents == null ? null : Collections.unmodifiableList(new ArrayList<>(agents));
            commands = commands == null ? null : Collections.unmodifiableList(new ArrayList<>(commands));
            skills = skills == null ? null : Collections.unmodifiableList(new ArrayList<>(skills));
        }
    }

    /** One plugin a marketplace lists. */
    public record PluginEntry(
            String name,
            String description,
            String publisher,
            String version,
            String category,
            String sourceKind,
            List<String> installedIn) {
        public PluginEntry {
            installedIn = installedIn == null ? null : Collections.unmodifiableList(new ArrayList<>(installedIn));
        }
    }

    /**
     * @param knownIn the targets that have it added ({@code FLOOR42}, {@code CLAUDE}); empty for an official one not
     *     added anywhere yet
     * @param listedFrom where the listing was read: {@code floor42} or {@code claude} (the clone Claude Code keeps),
     *     {@code fetched} (read from GitHub on the director's click), {@code none}
     * @param refreshable whether its listing can be read from GitHub
     */
    public record MarketplaceView(
            String name,
            String source,
            String owner,
            List<String> knownIn,
            boolean official,
            String listedFrom,
            String listedAt,
            boolean refreshable,
            List<PluginEntry> plugins) {
        public MarketplaceView {
            knownIn = knownIn == null ? null : Collections.unmodifiableList(new ArrayList<>(knownIn));
            plugins = plugins == null ? null : Collections.unmodifiableList(new ArrayList<>(plugins));
        }
    }

    /**
     * One installed plugin, in one target.
     *
     * @param problem {@code outsidePlugins} or {@code unreadable} when its folder could not be read; null otherwise
     */
    public record InstalledPlugin(
            String target,
            String plugin,
            String marketplace,
            String version,
            String scope,
            String installedAt,
            Contents contents,
            String problem) {}

    /** Whether Claude Code's command line can be started here, and its version. */
    public record CliStatus(boolean available, String version, String detail) {}

    /**
     * @param floor42Folder the configuration folder of the {@code FLOOR42} target
     * @param claudeFolder the director's Claude Code folder Floor42 reads for the {@code CLAUDE} target
     */
    public record PluginsView(
            String readAt,
            CliStatus cli,
            String floor42Folder,
            String claudeFolder,
            List<MarketplaceView> marketplaces,
            List<InstalledPlugin> installed) {
        public PluginsView {
            installed = installed == null ? null : Collections.unmodifiableList(new ArrayList<>(installed));
            marketplaces = marketplaces == null ? null : Collections.unmodifiableList(new ArrayList<>(marketplaces));
        }
    }

    /** What the director asks for; {@code digest} is the preview's, for a run. */
    public record PluginRequest(String target, String action, String plugin, String marketplace, String digest) {}

    /**
     * What a command would do, before anything runs: the director reads it in the confirmation dialog.
     *
     * @param warnings codes the screen explains: {@code HOOKS}, {@code MCP}, {@code LSP} (run code on the machine),
     *     {@code CONTENTS_UNKNOWN}, {@code NO_LICENSE}, {@code YOUR_CLAUDE} (changes the director's own Claude Code),
     *     {@code NOT_UNTIL_SELECTED}, {@code CODE_NEVER_TO_AGENTS} (Floor42 gives agents skills and agents only)
     * @param commands the exact commands, in order, as they will run
     * @param selectedBy missions whose sessions receive something of it (uninstall)
     * @param refused a stable code when Floor42 will not run it; {@code refusal} says why
     */
    public record PluginPreview(
            String target,
            String action,
            String plugin,
            String marketplace,
            String source,
            String publisher,
            String version,
            String license,
            String description,
            Contents contents,
            List<String> warnings,
            List<String> commands,
            List<String> selectedBy,
            String refused,
            String refusal,
            String digest) {
        public PluginPreview {
            commands = commands == null ? null : Collections.unmodifiableList(new ArrayList<>(commands));
            selectedBy = selectedBy == null ? null : Collections.unmodifiableList(new ArrayList<>(selectedBy));
            warnings = warnings == null ? null : Collections.unmodifiableList(new ArrayList<>(warnings));
        }
    }

    /** One command as it ran, with what it printed. */
    public record Step(String command, Integer exitCode, boolean timedOut, String output) {}

    /**
     * @param outcome read from disk afterwards: {@code INSTALLED}, {@code NOT_INSTALLED}, {@code REMOVED}, {@code
     *     STILL_INSTALLED}
     * @param commandsOk whether every command exited 0; the outcome stands either way
     */
    public record PluginOutcome(
            String outcome, boolean present, String version, boolean commandsOk, List<Step> steps, PluginsView view) {
        public PluginOutcome {
            steps = steps == null ? null : Collections.unmodifiableList(new ArrayList<>(steps));
        }
    }
}
