package dev.floor42.server.app;

import dev.floor42.server.app.Delivered.DeliveredCommit;
import dev.floor42.server.app.Delivered.DeliveredFile;
import dev.floor42.server.runtime.Git;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What git says of a branch's history for the deliverables (ADR-0037): the commits between its start and its tip, and
 * the files those commits added, changed, renamed or deleted. Every revision on the command line is a full hash Floor42
 * resolved itself, after {@code --end-of-options}. A read git answers with an error gives an empty list; a git that
 * cannot run throws a {@code GitFailure}.
 */
@ApplicationScoped
public class GitReader {

    /** At most this many commits listed per branch. */
    public static final int MAX_COMMITS = 30;

    static final Pattern HASH = Pattern.compile("[0-9a-f]{40,64}");

    private final Git git;

    @Inject
    public GitReader(Git git) {
        this.git = git;
    }

    /** Full hashes reachable from {@code tip} and not from {@code exclude}. */
    public List<String> revList(Path dir, String tip, String exclude) {
        return git.outIfOk(dir, "rev-list", "--end-of-options", tip, "^" + exclude, "--")
                .orElse("")
                .lines()
                .map(String::strip)
                .filter(l -> HASH.matcher(l).matches())
                .toList();
    }

    /** The best common ancestor of two commits, or null. */
    public String mergeBase(Path dir, String a, String b) {
        return git.outIfOk(dir, "merge-base", "--end-of-options", a, b)
                .map(String::strip)
                .filter(out -> HASH.matcher(out).matches())
                .orElse(null);
    }

    /** The newest commits between start and tip; {@code outside} holds those the project lacks (null: unknown). */
    public List<DeliveredCommit> commits(Path dir, String start, String tip, Set<String> outside) {
        String out = git.outIfOk(
                        dir,
                        "log",
                        "-" + MAX_COMMITS,
                        "--format=%H%x1f%s%x1f%an%x1f%cI",
                        "--end-of-options",
                        tip,
                        "^" + start,
                        "--")
                .orElse("");
        List<DeliveredCommit> commits = new ArrayList<>();
        for (String line : out.lines().toList()) {
            String[] p = line.split("\u001f", 4);
            if (p.length != 4 || !HASH.matcher(p[0]).matches()) continue;
            commits.add(new DeliveredCommit(
                    Delivered.shortHash(p[0]), p[1], p[2], p[3].strip(), outside != null && !outside.contains(p[0])));
        }
        return commits;
    }

    private record Entry(String status, String path, String from) {}

    /** The files the branch added, changed, renamed or deleted between the start and its tip. */
    public List<DeliveredFile> committedFiles(Path dir, String start, String tip) {
        var diff = git.outIfOk(dir, "diff", "--name-status", "-z", "-M", "--end-of-options", start, tip, "--");
        if (diff.isEmpty()) return List.of();
        Map<String, Long> sizes = sizes(dir, tip);
        Map<String, String> lastCommit = lastCommits(dir, start, tip);
        String tipShort = Delivered.shortHash(tip);
        List<DeliveredFile> files = new ArrayList<>();
        for (Entry e : entries(diff.get())) {
            boolean deleted = e.status().equals("D");
            files.add(new DeliveredFile(
                    e.path(),
                    e.from(),
                    e.status(),
                    deleted ? null : sizes.get(e.path()),
                    lastCommit.getOrDefault(e.path(), tipShort),
                    true,
                    false,
                    false,
                    FileKinds.kind(e.path())));
        }
        return files;
    }

    /** {@code diff --name-status -z}: a status, its path; a rename or a copy gives its former name first. */
    private static List<Entry> entries(String z) {
        List<Entry> entries = new ArrayList<>();
        String[] f = z.split("\0");
        for (int i = 0; i < f.length; i++) {
            if (f[i].isEmpty()) continue;
            char c = f[i].charAt(0);
            if ((c == 'R' || c == 'C') && i + 2 < f.length) {
                entries.add(new Entry(c == 'R' ? "R" : "A", f[i + 2], f[i + 1]));
                i += 2;
            } else if (i + 1 < f.length) {
                entries.add(new Entry(String.valueOf(c == 'T' ? 'M' : c), f[i + 1], null));
                i += 1;
            }
        }
        return entries;
    }

    /** Each file's size at {@code tip}, from {@code ls-tree -l}; a submodule ("-") has none. */
    private Map<String, Long> sizes(Path dir, String tip) {
        Map<String, Long> sizes = new HashMap<>();
        String out = git.outIfOk(dir, "ls-tree", "-r", "-l", "-z", "--end-of-options", tip)
                .orElse("");
        for (String entry : out.split("\0")) {
            int tab = entry.indexOf('\t');
            if (tab < 0) continue;
            String[] meta = entry.substring(0, tab).trim().split("\\s+");
            if (meta.length >= 4 && meta[3].matches("\\d{1,18}")) {
                sizes.put(entry.substring(tab + 1), Long.parseLong(meta[3]));
            }
        }
        return sizes;
    }

    /** For each path, the newest non-merge commit between start and tip that touched it (short hash). */
    private Map<String, String> lastCommits(Path dir, String start, String tip) {
        String out = git.outIfOk(
                        dir,
                        "log",
                        "--no-merges",
                        "--format=%x1e%h",
                        "--name-only",
                        "-z",
                        "-n",
                        "2000",
                        "--end-of-options",
                        tip,
                        "^" + start,
                        "--")
                .orElse("");
        Map<String, String> last = new HashMap<>();
        for (String chunk : out.split("\u001e")) {
            if (chunk.isBlank()) continue;
            String[] parts = chunk.split("[\0\n]");
            String hash = parts[0].strip();
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i].isEmpty()) last.putIfAbsent(parts[i], hash);
            }
        }
        return last;
    }
}
