package dev.floor42.server.security;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans every text a mission replay writes into a file meant to leave the director's machine (ADR-0035). In order:
 * the session token, the machine's known folders (the repository, the agents' worktrees, Floor42's data directory, the
 * home folder) replaced by a named placeholder, any other local absolute path replaced by {@value #PATH}, what looks
 * like a secret masked by {@link SecretMasker}, then the text clipped. It counts each replacement, and where it was,
 * for the preview the director reads before confirming.
 *
 * <p>It works on text only: what must never be exported at all (settings values, the director's file contents) is
 * left out by the caller, which records it with {@link #withheld}.
 */
public final class ReplaySanitizer {

    /** What was changed or left out. */
    public enum Kind {
        /** A value that looks like a credential. */
        SECRET,
        /** A local absolute path. */
        PATH,
        /** The daemon's session token. */
        TOKEN,
        /** A tool or check output cut at {@link #OUTPUT_MAX}. */
        OUTPUT_CLIPPED,
        /** A text cut at {@link #TEXT_MAX}. */
        TEXT_CLIPPED,
        /** A setting's value, never exported. */
        SETTING_VALUE,
        /** The director's file contents (a workshop edit), never exported. */
        DIRECTOR_FILE
    }

    /**
     * One place where something was masked or left out.
     *
     * @param event the event's number in the replay; 0 for the mission itself
     */
    public record Masked(int event, String field, Kind kind) {}

    public static final String PATH = "<path>";
    public static final int OUTPUT_MAX = 1500;
    public static final int TEXT_MAX = 2000;
    /** At most this many places are listed; every one is counted. */
    public static final int DETAILS_MAX = 200;

    /** A Windows path: drive, separator, and for a user folder its name even with spaces. */
    private static final Pattern WINDOWS = Pattern.compile(
            "(?<![A-Za-z0-9])[A-Za-z]:[\\\\/]+(?:(?i:users|documents and settings)[\\\\/]+[^\\\\/\\r\\n\"'<>|]+)?"
                    + "[^\\s\"'<>|]*");

    /** A network share: {@code \\server\share\...}. */
    private static final Pattern UNC = Pattern.compile("(?<![\\w\\\\])\\\\\\\\[A-Za-z0-9._$-]+\\\\[^\\s\"'<>|]*");

    /** A POSIX path under a folder that is personal or local to the machine; Git Bash's {@code /c/Users} too. */
    private static final Pattern POSIX = Pattern.compile("(?<![\\w.~:/\\\\-])/(?:(?:home|Users)/[^/\\r\\n\"'<>|]+"
            + "|(?:root|mnt|media|tmp|private|var|opt|srv)(?=/|\\s|$|[\"'<>|])"
            + "|[a-zA-Z](?=/(?i:users)/))"
            + "(?:/[^\\s\"'<>|]*)?");

    private final String token;
    private final List<Map.Entry<Pattern, String>> roots = new ArrayList<>();
    private final Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
    private final List<Masked> details = new ArrayList<>();

    /**
     * @param token the daemon's session token: replaced wherever it appears
     * @param roots absolute folders of this machine and the placeholder for each ({@code <repository>}); the longest is
     *     replaced first, so a worktree inside the data directory keeps its own name
     */
    public ReplaySanitizer(String token, Map<String, String> roots) {
        this.token = token;
        roots.entrySet().stream()
                .filter(r -> r.getKey() != null && !r.getKey().isBlank())
                .sorted(Comparator.comparingInt(
                                (Map.Entry<String, String> r) -> r.getKey().length())
                        .reversed())
                .forEach(r -> this.roots.add(Map.entry(root(r.getKey()), r.getValue())));
    }

    /** A text: an event's line, a title, an objective. Clipped at {@link #TEXT_MAX}. */
    public String text(int event, String field, String s) {
        return clip(event, field, clean(event, field, s), TEXT_MAX, Kind.TEXT_CLIPPED);
    }

    /** A tool's or a check's output. Clipped at {@link #OUTPUT_MAX}. */
    public String output(int event, String field, String s) {
        return clip(event, field, clean(event, field, s), OUTPUT_MAX, Kind.OUTPUT_CLIPPED);
    }

    /** Whether {@link #output} cut this output (after masking). */
    public static boolean clipped(String cleaned) {
        return cleaned != null && cleaned.endsWith(CLIP_MARK);
    }

    /** Records something the caller left out whole. */
    public void withheld(int event, String field, Kind kind) {
        note(event, field, kind, 1);
    }

    /** How many replacements of each kind, in the order of {@link Kind}; kinds never met are left out. */
    public Map<Kind, Integer> counts() {
        return new LinkedHashMap<>(counts);
    }

    /** Where they were, the first {@link #DETAILS_MAX}. */
    public List<Masked> details() {
        return List.copyOf(details);
    }

    public int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static final String CLIP_MARK = " [clipped]";

    private String clean(int event, String field, String s) {
        if (s == null || s.isEmpty()) return s;
        String out = s;
        if (token != null && !token.isEmpty() && out.contains(token)) {
            int n = (out.length() - out.replace(token, "").length()) / token.length();
            out = out.replace(token, SecretMasker.MASK);
            note(event, field, Kind.TOKEN, n);
        }
        for (var r : roots) out = replace(out, r.getKey(), r.getValue(), event, field, Kind.PATH);
        out = replace(out, WINDOWS, PATH, event, field, Kind.PATH);
        out = replace(out, UNC, PATH, event, field, Kind.PATH);
        out = replace(out, POSIX, PATH, event, field, Kind.PATH);
        int secrets = SecretMasker.count(out);
        if (secrets > 0) {
            out = SecretMasker.maskText(out);
            note(event, field, Kind.SECRET, secrets);
        }
        return out;
    }

    private String clip(int event, String field, String s, int max, Kind kind) {
        if (s == null || s.length() <= max) return s;
        note(event, field, kind, 1);
        return s.substring(0, max) + CLIP_MARK;
    }

    private String replace(String s, Pattern p, String placeholder, int event, String field, Kind kind) {
        Matcher m = p.matcher(s);
        StringBuilder out = new StringBuilder();
        int n = 0;
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(placeholder));
            n++;
        }
        if (n == 0) return s;
        m.appendTail(out);
        note(event, field, kind, n);
        return out.toString();
    }

    private void note(int event, String field, Kind kind, int n) {
        counts.merge(kind, n, Integer::sum);
        if (details.size() < DETAILS_MAX) details.add(new Masked(event, field, kind));
    }

    /**
     * A folder in any spelling of it: either separator, doubled or not (a path written inside JSON doubles its
     * backslashes), any case, and for a Windows drive the Git Bash and WSL forms ({@code /c/...}, {@code /mnt/c/...}).
     */
    static Pattern root(String folder) {
        String norm = folder.replace('\\', '/').replaceAll("/+$", "");
        String[] parts = norm.split("/+");
        StringBuilder p = new StringBuilder("(?i)");
        int start = 0;
        boolean drive = false;
        if (parts.length > 0 && parts[0].matches("[A-Za-z]:")) {
            char d = Character.toLowerCase(parts[0].charAt(0));
            p.append("(?:")
                    .append(d)
                    .append(":|/mnt/")
                    .append(d)
                    .append("|/")
                    .append(d)
                    .append(")");
            start = 1;
            drive = true;
        } else if (parts.length > 0 && parts[0].isEmpty()) {
            start = 1;
        }
        for (int i = start; i < parts.length; i++) {
            // A match never starts inside a run of one separator (it starts at the run's first one), so a long run of
            // slashes costs one scan, not one per slash.
            boolean opens = i == start && !drive;
            p.append(opens ? "(?:(?<!\\\\)\\\\+|(?<!/)/+)" : "(?:\\\\+|/+)").append(Pattern.quote(parts[i]));
        }
        return Pattern.compile(p.append("(?![\\w.-])").toString());
    }
}
