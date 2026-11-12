package dev.floor42.server.app;

import dev.floor42.domain.DomainException;
import dev.floor42.server.runtime.ConfinedPath;
import dev.floor42.server.runtime.Confinement;
import dev.floor42.server.runtime.GitProcess;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jboss.logging.Logger;

/** The workshop's work on files and on git's output, without git itself ({@link Workshop} runs it). */
public final class WorkshopFiles {

    private static final Logger LOG = Logger.getLogger(WorkshopFiles.class);

    private WorkshopFiles() {}

    /** The whole pending diff, bounded: {@code omitted} counts the changes left out (too large, or past the bound). */
    public record WholeDiff(String diff, int omitted) {}

    /** Each path's diff ({@code diffOf} null for one too large) while within both bounds; the rest is omitted. */
    static WholeDiff bounded(List<String> paths, Function<String, String> diffOf, int maxFiles, int maxChars) {
        StringBuilder out = new StringBuilder();
        int shown = 0;
        int omitted = 0;
        for (String p : paths) {
            String d = shown < maxFiles ? diffOf.apply(p) : null;
            if (d == null || out.length() + d.length() > maxChars) {
                omitted++;
            } else {
                out.append(d);
                shown++;
            }
        }
        return new WholeDiff(out.toString(), omitted);
    }

    /** A file the whole diff may read: confined, and at most {@code maxBytes}. */
    static boolean small(Path root, String path, long maxBytes) {
        if (Confinement.refusal(root, path, ConfinedPath.Mode.READ).isPresent()) return false;
        Path file = root.resolve(path);
        try {
            return !Files.isRegularFile(file) || Files.size(file) <= maxBytes;
        } catch (IOException e) {
            // Handled: a file whose size cannot be read is left out of the whole diff, and counted as omitted.
            return false;
        }
    }

    /** Lines added and removed per path, from {@code git diff --numstat -z}; -1 for a binary file. */
    static Map<String, int[]> numstat(String output) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        // -z: "added<TAB>removed<TAB>path" then NUL; a rename leaves the path empty and gives its two names after.
        String[] numstat = output.split("\0");
        for (int i = 0; i < numstat.length; i++) {
            String[] p = numstat[i].split("\t", 3);
            if (p.length < 3) continue;
            String path = p[2];
            if (path.isEmpty() && i + 2 < numstat.length) {
                path = numstat[i + 2];
                i += 2;
            }
            counts.put(path, new int[] {
                p[0].equals("-") ? -1 : Integer.parseInt(p[0]), p[1].equals("-") ? -1 : Integer.parseInt(p[1])
            });
        }
        return counts;
    }

    /** Deletes the untracked files not in {@code before}, confined for writing; returns what was removed. */
    static List<String> removeCreated(Path root, Set<String> untracked, Set<String> before) {
        List<String> removed = new ArrayList<>();
        for (String p : untracked) {
            if (before.contains(p)
                    || Confinement.refusal(root, p, ConfinedPath.Mode.WRITE).isPresent()) continue;
            try {
                Path file = root.resolve(p).normalize();
                Files.deleteIfExists(file);
                removed.add(p);
                removeEmptyParents(root, file);
            } catch (IOException e) {
                // Handled: left in place, it shows as a pending change the director can see and reject.
                LOG.debugf("Could not remove %s left by the checks in %s: %s", p, root, e.getMessage());
            }
        }
        return removed;
    }

    /** Directories the checks created and left empty go too, up to the workspace root. */
    private static void removeEmptyParents(Path root, Path file) throws IOException {
        for (Path dir = file.getParent(); dir != null && !dir.equals(root); dir = dir.getParent()) {
            try (var entries = Files.list(dir)) {
                if (entries.findAny().isPresent()) return;
            }
            Files.delete(dir);
        }
    }

    /** Browsers send LF: the file keeps its own line endings, or every line would show as changed. */
    static void write(Path file, String content, String path) {
        try {
            Files.createDirectories(file.getParent());
            String text = content;
            if (Files.isRegularFile(file)
                    && new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains("\r\n")
                    && !text.contains("\r\n")) {
                text = text.replace("\n", "\r\n");
            }
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + path, e);
        }
    }

    /** The line count of a text file, -1 when it is no file, too large or not readable as text (listed without one). */
    static int lineCount(Path root, String path, int maxBytes) {
        try {
            Path file = root.resolve(path);
            if (!Files.isRegularFile(file) || Files.size(file) > maxBytes) return -1;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return text.isEmpty() ? 0 : (int) text.lines().count();
        } catch (IOException | UncheckedIOException e) {
            // Handled: a file Floor42 cannot read as text (unreadable, not UTF-8) is listed without a line count.
            return -1;
        }
    }

    /** Runs {@code use} with a file holding these paths NUL-separated: a long list fits no command line. */
    static void withPathList(List<String> paths, Function<String, ?> use) {
        try {
            Path list = Files.createTempFile("floor42-paths", ".txt"); // NOSONAR owner-only temp
            try {
                Files.writeString(list, String.join("\0", paths), StandardCharsets.UTF_8);
                use.apply(list.toString());
            } finally {
                Files.deleteIfExists(list);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the list of paths for git; nothing was changed", e);
        }
    }

    /**
     * The files an acceptance commits: all the pending ones when none are asked, else the asked ones, each confined to
     * the worktree and one of the pending changes (NOT_PENDING otherwise); a renamed file brings its former name.
     */
    static List<String> pick(Path root, List<String> pending, List<GitProcess.StatusEntry> status, List<String> asked) {
        if (asked == null) return pending;
        Set<String> picked = new java.util.LinkedHashSet<>();
        for (String raw : asked) {
            if (raw == null || raw.isBlank()) throw new DomainException("INVALID_ARGUMENT", "a path is empty");
            String path =
                    Confinement.relative(root, raw, ConfinedPath.Mode.READ).relative();
            if (!pending.contains(path)) throw new DomainException("NOT_PENDING", path + " is not a pending change");
            picked.add(path);
            status.stream()
                    .filter(e -> e.path().equals(path) && e.from() != null)
                    .forEach(e -> picked.add(e.from()));
        }
        return List.copyOf(picked);
    }

    static String abbreviate(String diff) {
        int max = Workshop.MAX_RECORDED_DIFF;
        return diff.length() <= max ? diff : diff.substring(0, max) + "\n… (abbreviated)";
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
