package dev.floor42.server.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.floor42.domain.Actor;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.DynamicSkills;
import dev.floor42.server.runtime.Sandbox;
import dev.floor42.server.runtime.SessionAssets;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Which installed skills and agents a mission's sessions receive (ADR-0040): the director's choice, journaled, and
 * enforced when a session starts. Floor42 lays out, in the session's own folder, one plugin per chosen plugin holding
 * only the chosen skills' folders and agents' files, under a manifest Floor42 writes itself; the session loads them with
 * {@code --plugin-dir}. A plugin's hooks, MCP servers and LSP servers are never copied, and a skill or agent whose
 * frontmatter declares hooks or MCP servers is not given: the agents receive instructions, never code that runs
 * outside Floor42's governed tools.
 */
@ApplicationScoped
public class MissionAssets implements SessionAssets {

    /** Copying stops past this many files or bytes per plugin. */
    static final int MAX_FILES = 2_000;

    static final long MAX_BYTES = 20L * 1024 * 1024;

    private static final Pattern ITEM =
            Pattern.compile("(floor42|claude):([A-Za-z0-9._-]+)@([A-Za-z0-9._-]+)/(skill|agent)/([A-Za-z0-9._-]+)");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,120}");
    /** Quarkus's mapper: one configuration for the whole daemon. */
    @Inject
    ObjectMapper json;

    /**
     * One skill or agent the director may give the mission.
     *
     * @param id {@code <floor42|claude>:<plugin>@<marketplace>/<skill|agent>/<name>}
     * @param target FLOOR42 or CLAUDE: where it is installed
     * @param givable false when Floor42 will not give it; {@code reason} says why: {@code declaresHooks}, {@code
     *     declaresMcp}, {@code runsCommand}, {@code unreadable}, {@code badName}
     */
    public record AssetChoice(
            String id,
            String target,
            String plugin,
            String marketplace,
            String kind,
            String name,
            String description,
            boolean givable,
            String reason,
            boolean selected) {}

    /**
     * @param selected the director's last choice, as journaled
     * @param missing chosen items no longer installed: not given
     * @param skillTool whether the mission's table offers the Skill tool, without which a skill is never used
     * @param agentTool the same for the Agent tool
     */
    public record MissionAssetsView(
            String mission,
            List<String> selected,
            List<String> missing,
            List<AssetChoice> choices,
            boolean skillTool,
            boolean agentTool) {
        public MissionAssetsView {
            choices = choices == null ? null : Collections.unmodifiableList(new ArrayList<>(choices));
            missing = missing == null ? null : Collections.unmodifiableList(new ArrayList<>(missing));
            selected = selected == null ? null : Collections.unmodifiableList(new ArrayList<>(selected));
        }
    }

    /** What the director chooses. */
    public record AssetSelection(List<String> items) {
        public AssetSelection {
            items = items == null ? null : Collections.unmodifiableList(new ArrayList<>(items));
        }
    }

    @Inject
    OfficeService office;

    @Inject
    Plugins plugins;

    @Inject
    Sandbox sandbox;

    /** What the director may choose for the mission, and what is chosen. Reads disk and journal; changes nothing. */
    public MissionAssetsView view(MissionId id) {
        Mission m = office.query(c -> c.missions().find(id))
                .orElseThrow(() -> new jakarta.ws.rs.NotFoundException("Unknown mission " + id.value()));
        List<String> selected = office.query(c -> c.deliveries().missionAssets(id));
        List<AssetChoice> choices = new ArrayList<>();
        for (String target : List.of(Plugins.FLOOR42, Plugins.CLAUDE)) {
            for (PluginViews.InstalledPlugin p : plugins.installed(target)) {
                if (p.problem() != null || p.contents() == null) continue;
                Optional<Path> dir = plugins.installedDir(target, p.plugin(), p.marketplace());
                if (dir.isEmpty()) continue;
                for (String s : p.contents().skills()) {
                    choices.add(choice(
                            target,
                            p,
                            "skill",
                            s,
                            dir.get().resolve("skills").resolve(s).resolve("SKILL.md"),
                            selected));
                }
                for (String a : p.contents().agents()) {
                    choices.add(choice(
                            target, p, "agent", a, dir.get().resolve("agents").resolve(a + ".md"), selected));
                }
            }
        }
        Set<String> known = new LinkedHashSet<>();
        choices.forEach(c -> known.add(c.id()));
        List<String> missing = selected.stream().filter(s -> !known.contains(s)).toList();
        List<String> tools = Briefs.offeredTools(m);
        return new MissionAssetsView(
                id.value(), selected, missing, choices, tools.contains("Skill"), tools.contains("Agent"));
    }

    private AssetChoice choice(
            String target, PluginViews.InstalledPlugin p, String kind, String name, Path file, List<String> selected) {
        String id = prefix(target) + ":" + p.plugin() + "@" + p.marketplace() + "/" + kind + "/" + name;
        Head h = head(file, sandbox.enabled());
        String reason = !NAME.matcher(name).matches() ? "badName" : h.refusal();
        return new AssetChoice(
                id,
                target,
                p.plugin(),
                p.marketplace(),
                kind.toUpperCase(Locale.ROOT),
                ClaudeAssets.clean(h.name() == null ? name : h.name(), 120),
                h.description(),
                reason == null,
                reason,
                selected.contains(id));
    }

    /** The director's choice for the mission: each item must be installed and givable now. Journaled. */
    public MissionAssetsView select(Actor actor, MissionId id, AssetSelection selection) {
        if (actor == null || !actor.isDirector()) {
            throw RefusedException.conflict("FORBIDDEN", "Only the director chooses what a mission's sessions receive");
        }
        List<String> items = selection == null || selection.items() == null ? List.of() : selection.items();
        MissionAssetsView now = view(id);
        Map<String, AssetChoice> byId = new LinkedHashMap<>();
        now.choices().forEach(c -> byId.put(c.id(), c));
        for (String item : items) {
            AssetChoice c = byId.get(item);
            if (c == null) throw RefusedException.notFound("UNKNOWN_ASSET", item + " is not installed");
            if (!c.givable()) {
                throw RefusedException.conflict(
                        "ASSET_NOT_GIVABLE", item + " is not given to agents (" + c.reason() + ")");
            }
        }
        office.execute(
                c -> c.deliveries().selectMissionAssets(office.context(Actor.director(), Origin.REAL), id, items));
        return view(id);
    }

    // --- at launch ---------------------------------------------------------------------------------------------------

    @Override
    public List<Path> pluginDirs(SessionSpec spec, Path sessionDir, List<String> notes) {
        if (spec.origin() != Origin.REAL || spec.mission() == null) return List.of();
        List<String> selected = office.query(c -> c.deliveries().missionAssets(spec.mission()));
        if (selected.isEmpty()) return List.of();
        record Key(String target, String plugin, String marketplace) {}
        Map<Key, List<String[]>> byPlugin = new LinkedHashMap<>();
        for (String item : selected) {
            var m = ITEM.matcher(item);
            if (!m.matches()) continue;
            Key k = new Key(m.group(1).equals("floor42") ? Plugins.FLOOR42 : Plugins.CLAUDE, m.group(2), m.group(3));
            byPlugin.computeIfAbsent(k, x -> new ArrayList<>()).add(new String[] {m.group(4), m.group(5)});
        }
        List<Path> out = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (var e : byPlugin.entrySet()) {
            Key k = e.getKey();
            String id = k.plugin() + "@" + k.marketplace();
            Optional<Path> src = plugins.installedDir(k.target(), k.plugin(), k.marketplace());
            if (src.isEmpty()) {
                notes.add("Chosen for the mission but no longer installed, not given: " + id);
                continue;
            }
            if (!names.add(k.plugin().toLowerCase(Locale.ROOT))) {
                notes.add("Two chosen plugins are named " + k.plugin() + ": only the first is given");
                continue;
            }
            Path dest = sessionDir.resolve("plugins").resolve(k.plugin());
            try {
                int given = layOut(src.get(), dest, k.plugin(), e.getValue(), notes);
                if (given > 0) out.add(dest.toAbsolutePath());
            } catch (IOException ex) {
                // Handled: noted for the session's report; the asset is not given
                notes.add("Could not lay out " + id + " for the session, not given: " + ex.getMessage());
            }
        }
        return out;
    }

    /** Copies the chosen skills and agents of one plugin under a manifest Floor42 writes; returns how many. */
    int layOut(Path src, Path dest, String name, List<String[]> items, List<String> notes) throws IOException {
        Files.createDirectories(dest.resolve(".claude-plugin"));
        int given = 0;
        long[] budget = {MAX_FILES, MAX_BYTES};
        for (String[] item : items) {
            String kind = item[0];
            String n = item[1];
            Path file = kind.equals("skill")
                    ? src.resolve("skills").resolve(n).resolve("SKILL.md")
                    : src.resolve("agents").resolve(n + ".md");
            Head h = head(file, sandbox.enabled());
            if (h.refusal() != null) {
                notes.add("Not given to the session: " + name + ":" + n + " (" + h.refusal() + ")");
                continue;
            }
            if (kind.equals("skill")) {
                copyTree(
                        src.resolve("skills").resolve(n), dest.resolve("skills").resolve(n), budget);
            } else {
                Files.createDirectories(dest.resolve("agents"));
                copyFile(file, dest.resolve("agents").resolve(n + ".md"), budget);
            }
            given++;
        }
        ObjectNode manifest = json.createObjectNode().put("name", name);
        manifest.put("description", "Chosen by the director for this mission, laid out by Floor42 (ADR-0040)");
        Files.writeString(
                dest.resolve(".claude-plugin").resolve("plugin.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        return given;
    }

    /** Regular files only, links never followed nor copied, within the budget. */
    private static void copyTree(Path from, Path to, long[] budget) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    copyFile(p, target, budget);
                }
            }
        }
    }

    private static void copyFile(Path from, Path to, long[] budget) throws IOException {
        if (!Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) return;
        long size = Files.size(from);
        if (budget[0] <= 0 || budget[1] < size) throw new IOException("more than the copy limit");
        budget[0]--;
        budget[1] -= size;
        Files.createDirectories(to.getParent());
        Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** A skill's or agent's frontmatter, and why Floor42 would not give it; refusal null when it would. */
    record Head(String name, String description, String refusal) {}

    static Head head(Path file, boolean sandboxed) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return new Head(null, null, "unreadable");
        byte[] bytes;
        long size;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes(ClaudeAssets.MAX_HEAD_BYTES);
            size = Files.size(file);
        } catch (IOException e) {
            // Handled: listed as unreadable, never guessed
            return new Head(null, null, "unreadable");
        }
        ClaudeAssets.Frontmatter fm = ClaudeAssets.frontmatter(bytes, size > bytes.length);
        Map<String, String> f = fm.fields();
        String refusal = "tooLarge".equals(fm.problem())
                ? "unreadable"
                : f.containsKey("hooks")
                        ? "declaresHooks"
                        : f.containsKey("mcpservers")
                                ? "declaresMcp"
                                // A dynamic command runs on the host when the skill loads, past the sandbox (ADR-0028).
                                : sandboxed
                                                && file.getFileName().toString().equalsIgnoreCase("SKILL.md")
                                                && DynamicSkills.folderRunsCommand(file.getParent())
                                        ? "runsCommand"
                                        : null;
        return new Head(
                f.get("name"),
                f.containsKey("description") ? ClaudeAssets.clean(f.get("description"), 400) : null,
                refusal);
    }

    private static String prefix(String target) {
        return target.equals(Plugins.FLOOR42) ? "floor42" : "claude";
    }
}
