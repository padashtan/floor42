package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Mission;
import dev.floor42.domain.Origin;
import dev.floor42.server.app.PluginViews.Contents;
import dev.floor42.server.app.PluginViews.InstalledPlugin;
import dev.floor42.server.app.PluginViews.MarketplaceView;
import dev.floor42.server.app.PluginViews.PluginOutcome;
import dev.floor42.server.app.PluginViews.PluginPreview;
import dev.floor42.server.app.PluginViews.PluginRequest;
import dev.floor42.server.app.PluginViews.PluginsView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Installing plugins from a marketplace, on the director's click only (ADR-0040). Two targets, chosen at each install:
 *
 * <ul>
 *   <li>{@code FLOOR42}: Floor42's own Claude Code configuration folder ({@code <data>/claude-assets/config}, passed as
 *       {@code CLAUDE_CONFIG_DIR}); nothing there reaches a session until the director selects it for a mission;
 *   <li>{@code CLAUDE}: the director's own Claude Code; it does not reach Floor42's sessions either ({@code
 *       --restricted}) unless selected for a mission.
 * </ul>
 *
 * Floor42 runs Claude Code's own {@code claude plugin} commands ({@link PluginCli}), never its own copy of them; it shows
 * them first ({@link PluginPlan}), and reads the installed state again afterwards ({@link MarketplaceStore}): an exit
 * code is never taken as proof of what happened. Reading a marketplace's listing from GitHub is a network call made
 * only when the director asks for it.
 */
@ApplicationScoped
public class Plugins {

    public static final String FLOOR42 = "FLOOR42";
    public static final String CLAUDE = "CLAUDE";
    public static final String INSTALL = "INSTALL";
    public static final String UNINSTALL = "UNINSTALL";

    private final OfficeService office;
    private final PluginFolders folders;
    private final MarketplaceStore store;
    private final PluginPlan plans;
    private final PluginCli cli;

    @Inject
    public Plugins(
            OfficeService office, PluginFolders folders, MarketplaceStore store, PluginPlan plans, PluginCli cli) {
        this.office = office;
        this.folders = folders;
        this.store = store;
        this.plans = plans;
        this.cli = cli;
    }

    /** Tests swap the command line and the network for fakes; null puts the configured ones back. */
    public void useForTests(ClaudeCli fake, MarketplaceStore.Fetcher fakeFetcher, Long seconds) {
        cli.useForTests(fake, seconds);
        store.useForTests(fakeFetcher);
    }

    /** Floor42's own folder: configuration of the FLOOR42 target, listings read from GitHub. */
    public Path assetsDir() {
        return folders.assetsDir();
    }

    /** The {@code CLAUDE_CONFIG_DIR} of the FLOOR42 target. */
    public Path floor42Config() {
        return folders.floor42Config();
    }

    /** The plugins installed in a target, from its {@code installed_plugins.json} (version 2). */
    public List<InstalledPlugin> installed(String target) {
        return store.installed(target);
    }

    /** The folder of an installed plugin, real path inside its target's plugins folder; empty otherwise. */
    public Optional<Path> installedDir(String target, String plugin, String marketplace) {
        return store.installedDir(target, plugin, marketplace);
    }

    /** Marketplaces, their plugins and what is installed, read from disk now. No network, no command but the version. */
    public PluginsView view() {
        List<InstalledPlugin> installed = new ArrayList<>(installed(FLOOR42));
        installed.addAll(installed(CLAUDE));
        List<MarketplaceView> markets = new ArrayList<>();
        for (MarketplaceStore.Market m : store.markets().values()) markets.add(store.view(m, installed));
        return new PluginsView(
                Instant.now().toString(),
                cli.status(),
                folders.floor42Config().toString(),
                folders.claudeFolder().toString(),
                markets,
                installed);
    }

    /**
     * Reads a marketplace's listing from GitHub, now, on the director's click, and keeps it in Floor42's folder. It is a
     * listing to choose from; installing still goes through Claude Code, which reads its own copy.
     */
    public PluginsView refresh(Actor actor, String marketplace) {
        requireDirector(actor);
        if (marketplace == null || !MarketplaceStore.NAME.matcher(marketplace).matches()) {
            throw RefusedException.invalid("INVALID_ARGUMENT", "Which marketplace?");
        }
        MarketplaceStore.Market m = store.markets().get(marketplace);
        if (m == null) throw RefusedException.notFound("UNKNOWN_MARKETPLACE", "No marketplace " + marketplace);
        String repo = MarketplaceStore.githubRepo(m.source);
        if (repo == null) {
            throw RefusedException.conflict(
                    "NOT_GITHUB", marketplace + " is not on GitHub: Floor42 reads only GitHub listings itself");
        }
        store.refresh(marketplace, repo);
        return view();
    }

    /** What the command would be. Runs nothing, changes nothing. */
    public PluginPreview preview(Actor actor, PluginRequest request) {
        requireDirector(actor);
        return prepare(request).preview();
    }

    private PluginPlan.Prepared prepare(PluginRequest r) {
        PluginPlan.Prepared p = plans.prepare(r);
        if (!p.preview().action().equals(UNINSTALL)) return p;
        String id = p.preview().plugin() + "@" + p.preview().marketplace();
        String prefix = (p.preview().target().equals(FLOOR42) ? "floor42" : "claude") + ":" + id + "/";
        List<String> selectedBy = office.query(c -> {
            List<String> out = new ArrayList<>();
            for (Mission mi : c.missions().all()) {
                if (c.deliveries().missionAssets(mi.id()).stream().anyMatch(s -> s.startsWith(prefix))) {
                    out.add(mi.id().value());
                }
            }
            return out;
        });
        return plans.withSelectedBy(p, selectedBy);
    }

    /**
     * Runs the commands the director confirmed (the preview's digest must still match), then reads the installed state
     * again and journals what it found. A failed command is never taken as proof that nothing changed.
     */
    public PluginOutcome run(Actor actor, PluginRequest request) {
        requireDirector(actor);
        PluginPlan.Prepared prepared = prepare(request);
        PluginPreview p = prepared.preview();
        if (p.refused() != null) throw RefusedException.conflict(p.refused(), p.refusal());
        if (request.digest() == null || !request.digest().equals(p.digest())) {
            throw RefusedException.conflict(
                    "PREVIEW_STALE", "What Floor42 would run changed since the preview: look at it again");
        }
        Path config = folders.configFor(p.target());
        try {
            if (config != null && p.target().equals(FLOOR42)) Files.createDirectories(config);
        } catch (IOException e) {
            throw RefusedException.conflict("PLUGIN_FOLDER", "Cannot prepare Floor42's folder: " + e.getMessage(), e);
        }
        return recorded(p, cli.run(prepared.steps(), config));
    }

    /** The installed state read again from disk, journaled with the commands that ran. */
    private PluginOutcome recorded(PluginPreview p, PluginCli.Ran ran) {
        Optional<InstalledPlugin> after = installed(p.target()).stream()
                .filter(i -> i.plugin().equals(p.plugin()) && i.marketplace().equals(p.marketplace()))
                .findFirst();
        boolean present = after.isPresent();
        String version = after.map(InstalledPlugin::version).orElse(null);
        office.execute(c -> c.deliveries()
                .recordPluginCommand(
                        office.context(Actor.director(), Origin.REAL),
                        p.target(),
                        p.action(),
                        p.plugin(),
                        p.marketplace(),
                        ran.shown(),
                        ran.lastExit(),
                        present,
                        version));
        String outcome = p.action().equals(INSTALL)
                ? present ? "INSTALLED" : "NOT_INSTALLED"
                : present ? "STILL_INSTALLED" : "REMOVED";
        cli.forgetStatus();
        return new PluginOutcome(outcome, present, version, ran.ok(), ran.steps(), view());
    }

    private static void requireDirector(Actor actor) {
        if (actor == null || !actor.isDirector()) {
            throw RefusedException.conflict(
                    "FORBIDDEN", "Only the director installs or uninstalls a plugin: never an agent, never Floor42");
        }
    }

    /** The digest a run must match: what the preview showed, so nothing changes between the click and the run. */
    static String digest(
            String target,
            String action,
            String id,
            List<String> shown,
            String version,
            String license,
            Contents contents) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (String p : List.of(
                    target,
                    action,
                    id,
                    String.join("\n", shown),
                    String.valueOf(version),
                    String.valueOf(license),
                    String.valueOf(contents))) {
                d.update(p.getBytes(StandardCharsets.UTF_8));
                d.update((byte) 0);
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
