package dev.floor42.server.app;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The folders plugins live in (ADR-0040): Floor42's own ({@code FLOOR42} target, {@code <data>/claude-assets}) and the
 * director's Claude Code ({@code CLAUDE} target).
 */
@ApplicationScoped
public class PluginFolders {

    private final Path dataDir;
    private final Optional<String> pluginsDir;
    private final Optional<String> claudeHome;

    @Inject
    public PluginFolders(
            @ConfigProperty(name = "floor42.data-dir") Path dataDir,
            @ConfigProperty(name = "floor42.plugins.dir") Optional<String> pluginsDir,
            @ConfigProperty(name = "floor42.catalogue.claude-home") Optional<String> claudeHome) {
        this.dataDir = dataDir;
        this.pluginsDir = pluginsDir;
        this.claudeHome = claudeHome;
    }

    /** Floor42's own folder: configuration of the FLOOR42 target, listings read from GitHub. */
    public Path assetsDir() {
        return Path.of(pluginsDir
                        .filter(s -> !s.isBlank())
                        .orElse(dataDir.resolve("claude-assets").toString()))
                .toAbsolutePath()
                .normalize();
    }

    /** The {@code CLAUDE_CONFIG_DIR} of the FLOOR42 target. */
    public Path floor42Config() {
        return assetsDir().resolve("config");
    }

    /** The director's Claude Code folder, as Floor42 reads it. */
    public Path claudeFolder() {
        return Path.of(claudeHome.filter(s -> !s.isBlank()).orElse(System.getProperty("user.home") + "/.claude"))
                .toAbsolutePath()
                .normalize();
    }

    /** The configuration folder a target's commands act on; null for the director's default one. */
    Path configFor(String target) {
        if (Plugins.FLOOR42.equals(target)) return floor42Config();
        return claudeHome.filter(s -> !s.isBlank()).isPresent() ? claudeFolder() : null;
    }

    Path pluginsRoot(String target) {
        return (Plugins.FLOOR42.equals(target) ? floor42Config() : claudeFolder()).resolve("plugins");
    }

    /** Where a marketplace's listing read from GitHub is kept. */
    Path listingFile(String marketplace) {
        return assetsDir().resolve("listings").resolve(marketplace + ".json");
    }
}
