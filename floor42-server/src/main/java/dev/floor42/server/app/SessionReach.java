package dev.floor42.server.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.app.ClaudeAssets.Asset;
import dev.floor42.server.app.ClaudeAssets.Kind;
import dev.floor42.server.app.ClaudeAssets.Level;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Whether an installed item reaches Floor42's sessions (W3). Never guessed: it is read from what a real session said
 * about itself. Claude Code's {@code system/init} line lists the skills, agents and plugins it loaded; Floor42 keeps every
 * session's stream in {@code <data>/sessions/<id>/transcript.jsonl}. An item listed there was loaded; an item that
 * existed then and is not listed was not. Without such a line, the answer is UNKNOWN.
 */
public final class SessionReach {

    /** How much of a transcript's first line is read. */
    static final int MAX_INIT_BYTES = 256 * 1024;
    /** How many of the most recent sessions are looked at. */
    static final int MAX_SESSIONS = 400;

    public enum State {
        AVAILABLE,
        NOT_AVAILABLE,
        UNKNOWN
    }

    /**
     * Why: {@code listed} (the session's init lists it), {@code toolDenied} (listed, but the mission forbids the Skill
     * or Agent tool), {@code notLoaded} (it existed and the session did not load it), {@code changedSince} (changed
     * after that session), {@code notReported} (that session's init has no such list), {@code noSession} (no session
     * recorded), {@code noMissionSession} (a project item, and no session of this mission yet), {@code notInWorktree}
     * (a project item missing from every worktree of the mission: only what is committed on its branch reaches it).
     */
    public record Verdict(State state, String reason) {}

    /**
     * A session's own account of what it loaded; a list is null when the line has none.
     *
     * @param at when the session started (its transcript was created)
     */
    public record Init(
            Instant at,
            String cwd,
            String version,
            List<String> tools,
            List<String> skills,
            List<String> agents,
            List<String> plugins) {
        public Init {
            agents = agents == null ? null : Collections.unmodifiableList(new ArrayList<>(agents));
            plugins = plugins == null ? null : Collections.unmodifiableList(new ArrayList<>(plugins));
            skills = skills == null ? null : Collections.unmodifiableList(new ArrayList<>(skills));
            tools = tools == null ? null : Collections.unmodifiableList(new ArrayList<>(tools));
        }
    }

    private SessionReach() {}

    /** The init lines of the most recent sessions, newest first. */
    public static List<Init> inits(ObjectMapper json, Path sessions) {
        if (!Files.isDirectory(sessions)) return List.of();
        record Found(Path file, Instant at) {}
        List<Found> found = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(sessions)) {
            for (Path d : dirs.toList()) {
                Path t = d.resolve("transcript.jsonl");
                if (!Files.isRegularFile(t)) continue;
                found.add(new Found(t, started(t)));
            }
        } catch (IOException e) {
            // Handled: sessions Floor42 cannot list give no verdict; the catalogue then says "not checked".
            return List.of();
        }
        List<Init> inits = new ArrayList<>();
        found.stream()
                .sorted(Comparator.comparing(Found::at).reversed())
                .limit(MAX_SESSIONS)
                .forEach(f -> {
                    Init i = init(json, f.file(), f.at());
                    if (i != null) inits.add(i);
                });
        return inits;
    }

    /** The first one that satisfies {@code which}, or null. */
    public static Init latest(List<Init> inits, Predicate<Init> which) {
        return inits.stream().filter(which).findFirst().orElse(null);
    }

    static Init init(ObjectMapper json, Path transcript, Instant at) {
        byte[] head;
        try (InputStream in = Files.newInputStream(transcript)) {
            head = in.readNBytes(MAX_INIT_BYTES);
        } catch (IOException e) {
            // Handled: an unreadable transcript is a session without an init line, skipped.
            return null;
        }
        String text = new String(head, StandardCharsets.UTF_8);
        int nl = text.indexOf('\n');
        if (nl < 0 && head.length == MAX_INIT_BYTES) return null;
        return parse(json, nl < 0 ? text : text.substring(0, nl), at);
    }

    /** One stream line, if it is Claude Code's {@code system/init}. */
    public static Init parse(ObjectMapper json, String line, Instant at) {
        try {
            JsonNode n = json.readTree(line);
            if (n == null
                    || !"system".equals(n.path("type").asText())
                    || !"init".equals(n.path("subtype").asText())) {
                return null;
            }
            return new Init(
                    at,
                    n.path("cwd").asText(null),
                    n.path("claude_code_version").asText(null),
                    names(n.get("tools")),
                    names(n.get("skills")),
                    names(n.get("agents")),
                    names(n.get("plugins")));
        } catch (IOException | RuntimeException e) {
            // Handled: a line that is not JSON is not Claude Code's init line (documented: null).
            return null;
        }
    }

    /** Names of a list of strings, or of objects with a {@code name}; null when absent. */
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull") // null is "not reported", an empty list is "none"
    private static List<String> names(JsonNode list) {
        if (list == null || !list.isArray()) return null;
        List<String> out = new ArrayList<>();
        for (JsonNode e : list) {
            String s = e.isTextual() ? e.asText() : e.path("name").asText("");
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    private static Instant started(Path file) {
        try {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
            Instant created = a.creationTime().toInstant();
            Instant modified = a.lastModifiedTime().toInstant();
            return created.isAfter(modified) ? modified : created;
        } catch (IOException e) {
            // Handled: a session of unknown date sorts last, never first.
            return Instant.EPOCH;
        }
    }

    /** Whether the init lists the item: by name, or as {@code plugin:name} for a plugin's skill or agent. */
    public static boolean listed(Asset a, Init init) {
        List<String> list = list(a.kind(), init);
        if (list == null) return false;
        String name = a.name().toLowerCase(Locale.ROOT);
        String qualified = a.plugin() == null ? null : (a.plugin() + ":" + a.name()).toLowerCase(Locale.ROOT);
        return list.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .anyMatch(s -> s.equals(name) && a.level() != Level.PLUGIN
                        || a.kind() == Kind.PLUGIN && s.equals(name)
                        || s.equals(qualified));
    }

    static List<String> list(Kind kind, Init init) {
        return switch (kind) {
            case SKILL -> init.skills();
            case AGENT -> init.agents();
            case PLUGIN -> init.plugins();
        };
    }

    /**
     * The verdict for one item.
     *
     * @param init the session to judge by: the mission's latest when it has one, else the latest of all; null if none
     * @param ofMission whether {@code init} is a session of the mission (run in one of its worktrees)
     * @param toolOffered whether the Skill tool (skills) or the Agent tool (agents) is offered to the mission's
     *     sessions; null when it cannot be told
     * @param inWorktree for a project item, whether one of the mission's worktrees has the file; null with no worktree
     */
    public static Verdict verdict(Asset a, Init init, boolean ofMission, Boolean toolOffered, Boolean inWorktree) {
        if (a.level() == Level.PROJECT && !ofMission) {
            return Boolean.FALSE.equals(inWorktree)
                    ? new Verdict(State.NOT_AVAILABLE, "notInWorktree")
                    : new Verdict(State.UNKNOWN, "noMissionSession");
        }
        if (init == null) return new Verdict(State.UNKNOWN, "noSession");
        if (list(a.kind(), init) == null) return new Verdict(State.UNKNOWN, "notReported");
        boolean listed = listed(a, init);
        if (!listed) {
            if (a.modified() == null || a.modified().isAfter(init.at())) {
                return new Verdict(State.UNKNOWN, "changedSince");
            }
            return new Verdict(State.NOT_AVAILABLE, "notLoaded");
        }
        if (a.kind() == Kind.PLUGIN) return new Verdict(State.AVAILABLE, "listed");
        if (Boolean.FALSE.equals(toolOffered)) return new Verdict(State.NOT_AVAILABLE, "toolDenied");
        if (toolOffered == null) return new Verdict(State.UNKNOWN, "toolUnknown");
        return new Verdict(State.AVAILABLE, "listed");
    }

    /**
     * Whether the director's user-level items reach Floor42's sessions, from the verdicts: {@code isolated} when some
     * were judged and none was loaded, {@code notIsolated} when one was, {@code unknown} otherwise.
     */
    public static String isolation(List<Verdict> userLevel) {
        boolean loaded = userLevel.stream()
                .anyMatch(v -> v.reason().equals("listed") || v.reason().equals("toolDenied"));
        boolean refused = userLevel.stream().anyMatch(v -> v.reason().equals("notLoaded"));
        return loaded ? "notIsolated" : refused ? "isolated" : "unknown";
    }

    /** Names the init lists that match none of the items found on disk: built into Claude Code, or elsewhere. */
    public static List<String> unmatched(Kind kind, Init init, List<Asset> assets) {
        if (init == null || list(kind, init) == null) return List.of();
        return list(kind, init).stream()
                .filter(n -> assets.stream()
                        .filter(a -> a.kind() == kind)
                        .noneMatch(a -> a.name().equalsIgnoreCase(n)
                                || a.plugin() != null && (a.plugin() + ":" + a.name()).equalsIgnoreCase(n)))
                .distinct()
                .toList();
    }
}
