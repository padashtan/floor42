package dev.floor42.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.server.app.Briefs;
import dev.floor42.server.app.ClaudeAssets;
import dev.floor42.server.app.ClaudeAssets.Asset;
import dev.floor42.server.app.ClaudeAssets.Kind;
import dev.floor42.server.app.ClaudeAssets.Level;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.SessionReach;
import dev.floor42.server.app.SessionReach.Init;
import dev.floor42.server.app.SessionReach.State;
import dev.floor42.server.app.SessionReach.Verdict;
import dev.floor42.server.app.Workspaces;
import dev.floor42.server.runtime.ClaudeCodeRuntime;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

/**
 * Skills and agents (W3): what is installed for Claude Code on this machine, whether it reaches Floor42's sessions, and
 * a curated list of what could be added. A reading from disk: nothing is journaled, nothing runs, nothing is installed.
 * Whether an item reaches the sessions is read from a real session's init line ({@link SessionReach}), never assumed.
 */
@Path("/api/catalogue")
@Produces(MediaType.APPLICATION_JSON)
public class CatalogueResource {

    /**
     * How Floor42 launches its sessions, and the session the verdicts are read from.
     *
     * @param flags the isolating flags every session gets ({@link ClaudeCodeRuntime#ISOLATION}); besides them Floor42
     *     passes its own settings file (its hooks, nothing else) and the tools the mission's table offers
     * @param sessionAt when that session started; null when no session was recorded
     * @param sessionOfMission whether it ran in a worktree of the mission asked about
     * @param sessionTools the tools it was offered
     * @param skillTool whether the Skill tool is offered (the mission's table, else that session); null when unknown
     * @param agentTool the same for the Agent tool
     * @param isolation {@code isolated} (user-level items existed and none was loaded), {@code notIsolated}, or {@code
     *     unknown}
     */
    public record LaunchView(
            List<String> flags,
            String sessionAt,
            String sessionVersion,
            String sessionCwd,
            boolean sessionOfMission,
            List<String> sessionTools,
            Boolean skillTool,
            Boolean agentTool,
            String isolation) {
        public LaunchView {
            flags = flags == null ? null : Collections.unmodifiableList(new ArrayList<>(flags));
            sessionTools = sessionTools == null ? null : Collections.unmodifiableList(new ArrayList<>(sessionTools));
        }
    }

    /**
     * One installed item.
     *
     * @param level USER, PROJECT (the mission's repository) or PLUGIN
     * @param path relative to its root (see {@link Asset#path()})
     * @param problem why it was not (fully) read; null when read
     * @param state AVAILABLE, NOT_AVAILABLE or UNKNOWN for Floor42's sessions
     * @param reason the code the screen explains (see {@link Verdict})
     */
    public record InstalledItem(
            String kind,
            String level,
            String plugin,
            String name,
            String description,
            List<String> tools,
            String model,
            String version,
            String path,
            String problem,
            String state,
            String reason) {
        public InstalledItem {
            tools = tools == null ? null : Collections.unmodifiableList(new ArrayList<>(tools));
        }
    }

    /** A name the session's init lists that matches no file Floor42 read: built into Claude Code, or from elsewhere. */
    public record SessionItem(String kind, String name, String state, String reason) {}

    /**
     * One entry of the curated catalogue ({@code catalogue.json}, shipped with Floor42).
     *
     * @param brings what it brings, by language ({@code en}, {@code fr})
     * @param install the commands to copy into your own Claude Code; Floor42 never runs them
     * @param installed whether a plugin of that name is installed
     * @param state whether it would reach Floor42's sessions once installed: NOT_AVAILABLE for an MCP server
     *     ({@code --strict-mcp-config}), else UNKNOWN until a session shows it
     */
    public record SuggestionItem(
            String id,
            String kind,
            String name,
            String source,
            Map<String, String> brings,
            Map<String, String> risks,
            List<String> install,
            String license,
            boolean installed,
            String state,
            String reason) {
        public SuggestionItem {
            brings = brings == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(brings));
            install = install == null ? null : Collections.unmodifiableList(new ArrayList<>(install));
            risks = risks == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(risks));
        }
    }

    /**
     * @param readAt when Floor42 read the disk for this answer; nothing of it is journaled
     * @param mission the mission asked about; null without one (no project level then)
     * @param repository the mission's repository, whose {@code .claude} is the project level
     * @param checked the date the curated catalogue was checked against its sources
     */
    public record CatalogueView(
            String readAt,
            String mission,
            String repository,
            LaunchView launch,
            List<InstalledItem> installed,
            List<SessionItem> session,
            List<SuggestionItem> suggestions,
            String checked) {
        public CatalogueView {
            installed = installed == null ? null : Collections.unmodifiableList(new ArrayList<>(installed));
            session = session == null ? null : Collections.unmodifiableList(new ArrayList<>(session));
            suggestions = suggestions == null ? null : Collections.unmodifiableList(new ArrayList<>(suggestions));
        }
    }

    /** Quarkus's mapper: one configuration for the whole daemon. */
    @Inject
    ObjectMapper json;

    @Inject
    OfficeService office;

    @Inject
    Workspaces workspaces;

    @ConfigProperty(name = "floor42.data-dir")
    java.nio.file.Path dataDir;

    /** The user level; Floor42's sessions use the same one ({@code CLAUDE*} variables are not passed to them). */
    @ConfigProperty(name = "floor42.catalogue.claude-home")
    Optional<String> claudeHome;

    /** Folders a link inside {@code ~/.claude} may point into and still be read (the director's own toolkit). */
    @ConfigProperty(name = "floor42.catalogue.trusted-roots")
    Optional<List<String>> trustedRoots;

    @GET
    public CatalogueView catalogue(
            @Parameter(
                            description =
                                    "A mission id (M-1) whose repository adds its own skills and agents to the catalogue; optional.")
                    @QueryParam("mission")
                    String missionId) {
        Mission mission = missionOf(missionId);
        String repository = mission == null ? null : mission.workspace();
        List<java.nio.file.Path> trusted = trustedRoots.orElse(List.of()).stream()
                .filter(s -> !s.isBlank())
                .map(java.nio.file.Path::of)
                .toList();
        List<Asset> assets = assets(repository, trusted);
        Reach reach = reach(mission);
        List<Verdict> userLevel = new ArrayList<>();
        List<InstalledItem> installed = installed(assets, reach, chosenPlugins(mission), userLevel);
        String isolation = SessionReach.isolation(userLevel);
        JsonNode catalogue = curated();
        return new CatalogueView(
                Instant.now().toString(),
                mission == null ? null : mission.id().value(),
                repository,
                launch(reach, isolation),
                installed,
                sessionItems(reach, assets),
                suggestions(catalogue, assets, isolation),
                catalogue.path("checked").asText(null));
    }

    /**
     * What a session of the mission was last seen to load ({@code judged}: the mission's own last session, else the
     * latest of any), and whether it was offered the Skill and Agent tools.
     */
    private record Reach(
            Collection<java.nio.file.Path> worktrees,
            Init judged,
            boolean ofMission,
            Boolean skillTool,
            Boolean agentTool) {
        Boolean toolFor(Kind k) {
            return k == Kind.SKILL ? skillTool : k == Kind.AGENT ? agentTool : Boolean.TRUE;
        }
    }

    private Mission missionOf(String missionId) {
        if (missionId == null || missionId.isBlank()) return null;
        MissionId id = new MissionId(missionId);
        return office.query(c -> c.missions().find(id))
                .orElseThrow(() -> new NotFoundException("Unknown mission " + missionId));
    }

    private List<Asset> assets(String repository, List<java.nio.file.Path> trusted) {
        java.nio.file.Path home = java.nio.file.Path.of(
                claudeHome.filter(s -> !s.isBlank()).orElse(System.getProperty("user.home") + "/.claude"));
        List<Asset> assets = new ArrayList<>(ClaudeAssets.user(home, trusted));
        if (repository != null && !repository.isBlank() && Files.isDirectory(java.nio.file.Path.of(repository))) {
            assets.addAll(ClaudeAssets.project(java.nio.file.Path.of(repository), trusted));
        }
        assets.addAll(ClaudeAssets.plugins(json, home, trusted));
        return assets;
    }

    private Reach reach(Mission mission) {
        Collection<java.nio.file.Path> worktrees =
                mission == null ? List.of() : workspaces.worktrees(mission.id()).values();
        List<Init> inits = SessionReach.inits(json, dataDir.resolve(ClaudeCodeRuntime.SESSIONS_DIR));
        Init ofMission = worktrees.isEmpty() ? null : SessionReach.latest(inits, i -> inAny(i.cwd(), worktrees));
        Init judged = ofMission != null ? ofMission : SessionReach.latest(inits, i -> true);
        return new Reach(
                worktrees,
                judged,
                ofMission != null,
                offered(mission, judged, "Skill"),
                offered(mission, judged, "Agent"));
    }

    /** The plugins the director chose for the mission (ADR-0040), by name. */
    private java.util.Set<String> chosenPlugins(Mission mission) {
        java.util.Set<String> chosen = new java.util.HashSet<>();
        if (mission == null) return chosen;
        MissionId chosenFor = mission.id();
        for (String item : office.query(c -> c.deliveries().missionAssets(chosenFor))) {
            int colon = item.indexOf(':');
            int at = item.indexOf('@');
            if (colon > 0 && at > colon) chosen.add(item.substring(colon + 1, at));
        }
        return chosen;
    }

    /** Every installed asset with its verdict; those of the user level, not chosen for the mission, go to userLevel. */
    private static List<InstalledItem> installed(
            List<Asset> assets, Reach reach, java.util.Set<String> chosen, List<Verdict> userLevel) {
        List<InstalledItem> installed = new ArrayList<>();
        for (Asset a : assets) {
            Boolean inWorktree = a.level() != Level.PROJECT || reach.worktrees().isEmpty()
                    ? null
                    : reach.worktrees().stream()
                            .anyMatch(w -> Files.exists(w.resolve(a.path()), LinkOption.NOFOLLOW_LINKS));
            Verdict v = SessionReach.verdict(a, reach.judged(), reach.ofMission(), reach.toolFor(a.kind()), inWorktree);
            // A plugin the director chose for the mission reaches it on purpose (ADR-0040): it says nothing of
            // isolation.
            if (a.level() != Level.PROJECT && (a.plugin() == null || !chosen.contains(a.plugin()))) userLevel.add(v);
            installed.add(new InstalledItem(
                    a.kind().name(),
                    a.level().name(),
                    a.plugin(),
                    a.name(),
                    a.description(),
                    a.tools(),
                    a.model(),
                    a.version(),
                    a.path(),
                    a.problem(),
                    v.state().name(),
                    v.reason()));
        }
        return installed;
    }

    /** What the session listed that no installed asset explains. */
    private static List<SessionItem> sessionItems(Reach reach, List<Asset> assets) {
        List<SessionItem> session = new ArrayList<>();
        for (Kind k : Kind.values()) {
            for (String n : SessionReach.unmatched(k, reach.judged(), assets)) {
                Boolean tool = reach.toolFor(k);
                Verdict v = Boolean.FALSE.equals(tool)
                        ? new Verdict(State.NOT_AVAILABLE, "toolDenied")
                        : tool == null
                                ? new Verdict(State.UNKNOWN, "toolUnknown")
                                : new Verdict(State.AVAILABLE, "listed");
                session.add(new SessionItem(
                        k.name(), ClaudeAssets.clean(n, 120), v.state().name(), v.reason()));
            }
        }
        return session;
    }

    private static List<SuggestionItem> suggestions(JsonNode catalogue, List<Asset> assets, String isolation) {
        List<SuggestionItem> suggestions = new ArrayList<>();
        for (JsonNode e : catalogue.path("entries")) suggestions.add(suggestion(e, assets, isolation));
        return suggestions;
    }

    private static SuggestionItem suggestion(JsonNode e, List<Asset> assets, String isolation) {
        String kind = e.path("kind").asText();
        List<String> install = new ArrayList<>();
        e.path("install").forEach(c -> install.add(c.asText()));
        String plugin = pluginOf(install);
        boolean isInstalled = plugin != null
                && assets.stream()
                        .anyMatch(a -> a.kind() == Kind.PLUGIN && a.name().equalsIgnoreCase(plugin));
        Verdict v = "mcp".equals(kind)
                ? new Verdict(State.NOT_AVAILABLE, "strictMcp")
                : "isolated".equals(isolation)
                        ? new Verdict(State.UNKNOWN, "userLevelNotLoaded")
                        : new Verdict(State.UNKNOWN, "afterSession");
        return new SuggestionItem(
                e.path("id").asText(),
                kind,
                e.path("name").asText(),
                e.path("source").asText(),
                texts(e.path("brings")),
                texts(e.path("risks")),
                install,
                e.path("license").asText(null),
                isInstalled,
                v.state().name(),
                v.reason());
    }

    private static LaunchView launch(Reach reach, String isolation) {
        Init judged = reach.judged();
        return new LaunchView(
                ClaudeCodeRuntime.ISOLATION,
                judged == null ? null : judged.at().toString(),
                judged == null ? null : judged.version(),
                judged == null ? null : judged.cwd(),
                reach.ofMission(),
                judged == null || judged.tools() == null ? List.of() : judged.tools(),
                reach.skillTool(),
                reach.agentTool(),
                isolation);
    }

    /** From the mission's table when there is one; else from what the session was offered; else unknown. */
    private static Boolean offered(Mission mission, Init judged, String tool) {
        if (mission != null) return Briefs.offeredTools(mission).contains(tool);
        if (judged == null || judged.tools() == null) return null;
        // Claude Code lists the subagent tool as Task, and names it Agent in its hook (ADR-0023).
        return judged.tools().contains(tool)
                || tool.equals("Agent") && judged.tools().contains("Task");
    }

    private static boolean inAny(String cwd, Collection<java.nio.file.Path> worktrees) {
        if (cwd == null || cwd.isBlank()) return false;
        String c;
        try {
            c = java.nio.file.Path.of(cwd).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            // Handled: a working folder that is no path matches no worktree
            return false;
        }
        return worktrees.stream()
                .anyMatch(w -> w.toAbsolutePath().normalize().toString().equalsIgnoreCase(c));
    }

    /** {@code /plugin install name@marketplace}: the plugin's name. */
    static String pluginOf(List<String> install) {
        for (String c : install) {
            String[] w = c.strip().split("\\s+");
            if (w.length == 3 && w[0].equals("/plugin") && w[1].equals("install")) {
                String p = w[2];
                return p.contains("@") ? p.substring(0, p.indexOf('@')) : p;
            }
        }
        return null;
    }

    private static Map<String, String> texts(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        node.properties()
                .forEach(p -> out.put(
                        p.getKey().toLowerCase(Locale.ROOT), p.getValue().asText()));
        return out;
    }

    JsonNode curated() {
        try (InputStream in = CatalogueResource.class.getResourceAsStream("/catalogue.json")) {
            if (in == null) throw new IllegalStateException("catalogue.json is missing from the build");
            return json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
