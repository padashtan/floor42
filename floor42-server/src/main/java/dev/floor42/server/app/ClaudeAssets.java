package dev.floor42.server.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What Claude Code skills, custom agents and plugins are installed on this machine (W3), read from disk and never run.
 *
 * <p>Only the frontmatter of a {@code SKILL.md} or an agent's {@code .md} is read, at most {@link #MAX_HEAD_BYTES} of
 * it; a plugin's manifest at most {@link #MAX_MANIFEST_BYTES}. Nothing is executed, nothing is written. A file whose
 * real path (links resolved) leaves the root it was found in is not read, unless it lands in a root the director
 * trusts ({@code floor42.catalogue.trusted-roots}): it is listed with the problem {@code linkOut}.
 */
public final class ClaudeAssets {

    /** How much of a skill or agent file is read: the frontmatter must close within it. */
    public static final int MAX_HEAD_BYTES = 16 * 1024;
    /** How much of a plugin manifest or of {@code installed_plugins.json} is read. */
    public static final int MAX_MANIFEST_BYTES = 256 * 1024;
    /** At most this many entries per root: a folder of thousands is cut, not read in full. */
    public static final int MAX_ENTRIES = 300;

    static final int MAX_DESCRIPTION = 600;
    static final int MAX_NAME = 120;

    public enum Kind {
        SKILL,
        AGENT,
        PLUGIN
    }

    public enum Level {
        USER,
        PROJECT,
        PLUGIN
    }

    /**
     * One installed item.
     *
     * @param plugin the plugin it comes with; null for user and project items, the plugin's own name for a PLUGIN
     * @param name the frontmatter's name, else the folder or file name
     * @param path relative to its root: {@code ~/.claude}, the repository, or the plugins folder for a plugin; with a
     *     plugin's skills and agents, relative to the plugin's folder
     * @param modified when the file was last changed; null when unreadable
     * @param problem why it was not (fully) read: {@code linkOut}, {@code outsidePlugins}, {@code tooLarge}, {@code
     *     malformed}, {@code noFrontmatter}, {@code unreadable}; null when read
     */
    public record Asset(
            Kind kind,
            Level level,
            String plugin,
            String name,
            String description,
            List<String> tools,
            String model,
            String version,
            String path,
            Instant modified,
            String problem) {
        public Asset {
            tools = tools == null ? null : Collections.unmodifiableList(new ArrayList<>(tools));
        }
    }

    /** The fields read from a frontmatter, and what went wrong; problem null means it parsed. */
    public record Frontmatter(Map<String, String> fields, String problem) {
        public Frontmatter {
            fields = fields == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }

    static final Pattern KEY = Pattern.compile("([A-Za-z0-9_-]{1,64})\\s*:(.*)");

    private ClaudeAssets() {}

    /** The user level: {@code ~/.claude/skills} and {@code ~/.claude/agents}. */
    public static List<Asset> user(Path claudeHome, List<Path> trusted) {
        List<Asset> out = new ArrayList<>();
        skills(claudeHome.resolve("skills"), "skills/", Level.USER, null, trusted, out);
        agents(claudeHome.resolve("agents"), "agents/", Level.USER, null, trusted, out);
        return sorted(out);
    }

    /** The project level of a repository: its {@code .claude/skills} and {@code .claude/agents}. */
    public static List<Asset> project(Path repository, List<Path> trusted) {
        List<Asset> out = new ArrayList<>();
        Path dot = repository.resolve(".claude");
        skills(dot.resolve("skills"), ".claude/skills/", Level.PROJECT, null, trusted, out);
        agents(dot.resolve("agents"), ".claude/agents/", Level.PROJECT, null, trusted, out);
        return sorted(out);
    }

    /**
     * Installed plugins, as {@code ~/.claude/plugins/installed_plugins.json} lists them (version 2: {@code
     * "name@marketplace": [{installPath, version, scope}]}), with their skills and agents. A plugin installed outside
     * the plugins folder is listed and not read ({@code outsidePlugins}).
     */
    public static List<Asset> plugins(ObjectMapper json, Path claudeHome, List<Path> trusted) {
        List<Asset> out = new ArrayList<>();
        Path root = claudeHome.resolve("plugins");
        Path realRoot = real(root);
        if (realRoot == null) return out;
        JsonNode installed = readJson(json, root.resolve("installed_plugins.json"), realRoot, trusted);
        if (installed == null) return out;
        int count = 0;
        for (var entry : installed.path("plugins").properties()) {
            if (count++ >= MAX_ENTRIES) break;
            plugin(json, root, realRoot, entry.getKey(), entry.getValue(), out);
        }
        return sorted(out);
    }

    /** One installed plugin, with its skills and agents; one outside the plugins folder is listed, not read. */
    private static void plugin(
            ObjectMapper json, Path root, Path realRoot, String key, JsonNode value, List<Asset> out) {
        String name = clean(key.contains("@") ? key.substring(0, key.indexOf('@')) : key, MAX_NAME);
        JsonNode first = value.isArray() ? value.path(0) : value;
        String install = first.path("installPath").asText("");
        String version =
                first.path("version").isTextual() ? clean(first.path("version").asText(), 40) : null;
        Path dir = install.isBlank() ? null : root.resolve(install);
        Path realDir = dir == null ? null : real(dir);
        if (realDir == null || !realDir.startsWith(realRoot)) {
            String problem = realDir == null ? "unreadable" : "outsidePlugins";
            out.add(new Asset(
                    Kind.PLUGIN, Level.PLUGIN, name, name, null, List.of(), null, version, key, null, problem));
            return;
        }
        String rel = slashes(realRoot.relativize(realDir).toString());
        JsonNode manifest = readJson(json, dir.resolve(".claude-plugin").resolve("plugin.json"), realDir, List.of());
        String description = manifest == null ? null : text(manifest.path("description"), MAX_DESCRIPTION);
        if (manifest != null && version == null) version = text(manifest.path("version"), 40);
        out.add(new Asset(
                Kind.PLUGIN,
                Level.PLUGIN,
                name,
                name,
                description,
                List.of(),
                null,
                version,
                rel,
                modified(dir),
                manifest == null ? "unreadable" : null));
        skills(dir.resolve("skills"), "skills/", Level.PLUGIN, name, List.of(), out);
        agents(dir.resolve("agents"), "agents/", Level.PLUGIN, name, List.of(), out);
    }

    /** Every {@code <root>/<name>/SKILL.md}. */
    static void skills(Path root, String prefix, Level level, String plugin, List<Path> trusted, List<Asset> out) {
        Path realRoot = real(root);
        if (realRoot == null || !Files.isDirectory(realRoot)) return;
        for (Path dir : list(root)) {
            String folder = dir.getFileName().toString();
            Path file = dir.resolve("SKILL.md");
            if (!Files.exists(file)) continue;
            out.add(read(Kind.SKILL, level, plugin, file, prefix + folder + "/SKILL.md", folder, realRoot, trusted));
        }
    }

    /** Every {@code <root>/*.md}. */
    static void agents(Path root, String prefix, Level level, String plugin, List<Path> trusted, List<Asset> out) {
        Path realRoot = real(root);
        if (realRoot == null || !Files.isDirectory(realRoot)) return;
        for (Path file : list(root)) {
            String fileName = file.getFileName().toString();
            if (!fileName.toLowerCase(Locale.ROOT).endsWith(".md")) continue;
            String stem = fileName.substring(0, fileName.length() - 3);
            out.add(read(Kind.AGENT, level, plugin, file, prefix + fileName, stem, realRoot, trusted));
        }
    }

    private static Asset read(
            Kind kind,
            Level level,
            String plugin,
            Path file,
            String rel,
            String fallback,
            Path realRoot,
            List<Path> trusted) {
        String name = clean(fallback, MAX_NAME);
        Path realFile = real(file);
        if (realFile == null) return problem(kind, level, plugin, name, rel, file, "unreadable");
        if (!inside(realFile, realRoot, trusted)) return problem(kind, level, plugin, name, rel, file, "linkOut");
        if (!Files.isRegularFile(realFile)) return problem(kind, level, plugin, name, rel, file, "unreadable");
        byte[] head;
        long size;
        try (InputStream in = Files.newInputStream(realFile)) {
            head = in.readNBytes(MAX_HEAD_BYTES);
            size = Files.size(realFile);
        } catch (IOException e) {
            // Handled: listed as unreadable, never guessed
            return problem(kind, level, plugin, name, rel, file, "unreadable");
        }
        return asset(kind, level, plugin, name, rel, realFile, frontmatter(head, size > head.length));
    }

    /** An item as its frontmatter describes it; its own name, when it gives one, wins over its folder's. */
    private static Asset asset(
            Kind kind, Level level, String plugin, String name, String rel, Path realFile, Frontmatter fm) {
        Map<String, String> f = fm.fields();
        String own = f.containsKey("name") ? clean(f.get("name"), MAX_NAME) : null;
        return new Asset(
                kind,
                level,
                plugin,
                own == null || own.isBlank() ? name : own,
                f.containsKey("description") ? clean(f.get("description"), MAX_DESCRIPTION) : null,
                f.containsKey("tools") ? tools(f.get("tools")) : List.of(),
                f.containsKey("model") ? clean(f.get("model"), 60) : null,
                null,
                rel,
                modified(realFile),
                fm.problem());
    }

    /** An item not read: its date is the link's or the entry's own, never its target's. */
    private static Asset problem(
            Kind kind, Level level, String plugin, String name, String rel, Path file, String problem) {
        return new Asset(kind, level, plugin, name, null, List.of(), null, null, rel, modified(file), problem);
    }

    /**
     * The frontmatter at the head of a file: {@code ---}, then {@code key: value} lines (quoted values, block scalars
     * {@code |} and {@code >}, and {@code - item} lists are understood), then {@code ---}. Nothing else of YAML is
     * interpreted: no anchors, no tags, no nesting kept.
     *
     * @param truncated whether the file goes on beyond {@code head}: an unclosed frontmatter is then {@code tooLarge}
     */
    public static Frontmatter frontmatter(byte[] head, boolean truncated) {
        String text = new String(head, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        List<String> lines = Arrays.asList(text.split("\r?\n", -1));
        if (lines.isEmpty() || !lines.get(0).strip().equals("---")) return new Frontmatter(Map.of(), "noFrontmatter");
        int end = Frontmatters.closing(lines, truncated);
        if (end < 0) return new Frontmatter(Map.of(), truncated ? "tooLarge" : "malformed");
        return Frontmatters.fields(lines, end);
    }

    static String unquote(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
            return v.substring(1, v.length() - 1).replace("''", "'");
        }
        return v;
    }

    /** {@code tools: Read, Grep} or {@code [Read, Grep]}: names only, at most 40. */
    static List<String> tools(String value) {
        String v = value.strip();
        if (v.startsWith("[") && v.endsWith("]")) v = v.substring(1, v.length() - 1);
        return Arrays.stream(v.split(","))
                .map(s -> clean(unquote(s.strip()), 80))
                .filter(s -> !s.isBlank())
                .limit(40)
                .toList();
    }

    /** No control characters, at most {@code max} characters. */
    public static String clean(String s, int max) {
        if (s == null) return null;
        String c = s.replaceAll("\\p{Cntrl}", " ").strip();
        return c.length() > max ? c.substring(0, max - 1) + "…" : c;
    }

    private static String text(JsonNode node, int max) {
        return node.isTextual() ? clean(node.asText(), max) : null;
    }

    private static JsonNode readJson(ObjectMapper json, Path file, Path realRoot, List<Path> trusted) {
        Path realFile = real(file);
        if (realFile == null || !inside(realFile, realRoot, trusted) || !Files.isRegularFile(realFile)) return null;
        try (InputStream in = Files.newInputStream(realFile)) {
            byte[] bytes = in.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (bytes.length > MAX_MANIFEST_BYTES) return null;
            return json.readTree(bytes);
        } catch (IOException | RuntimeException e) {
            // Handled: an unreadable or broken manifest lists the plugin without what it would declare.
            return null;
        }
    }

    /** Whether a real path stays in the root, or in a root the director trusts. */
    static boolean inside(Path realFile, Path realRoot, List<Path> trusted) {
        if (realFile.startsWith(realRoot)) return true;
        for (Path t : trusted) {
            Path r = real(t);
            if (r != null && realFile.startsWith(r)) return true;
        }
        return false;
    }

    /** The real path, links resolved; null when it does not exist or cannot be read. */
    static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException | SecurityException e) {
            // Handled: documented above, a path that cannot be resolved is "not there" (never followed).
            return null;
        }
    }

    /** Entries of a folder, links not followed, in name order, at most {@link #MAX_ENTRIES}. */
    private static List<Path> list(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .limit(MAX_ENTRIES)
                    .toList();
        } catch (IOException | SecurityException e) {
            // Handled: a folder that cannot be listed shows no asset rather than a guess.
            return List.of();
        }
    }

    private static Instant modified(Path p) {
        try {
            return Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toInstant();
        } catch (IOException e) {
            // Handled: the asset is listed without a date.
            return null;
        }
    }

    private static String slashes(String p) {
        return p.replace('\\', '/');
    }

    private static List<Asset> sorted(List<Asset> in) {
        return in.stream()
                .sorted(Comparator.comparing((Asset a) -> a.level())
                        .thenComparing(a -> a.plugin() == null ? "" : a.plugin())
                        .thenComparing(a -> a.kind() != Kind.PLUGIN)
                        .thenComparing(Asset::kind)
                        .thenComparing(a -> a.name().toLowerCase(Locale.ROOT)))
                .toList();
    }
}
