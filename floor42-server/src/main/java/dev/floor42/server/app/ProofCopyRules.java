package dev.floor42.server.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which of the files git lists go into a proof's copy (ADR-0058), and why the others do not. Decided before anything
 * is copied, with the sizes read once, so a cap is checked before the first byte is written. Pure but for reading the
 * files' attributes.
 */
final class ProofCopyRules {

    /** At most this many left-out files are named; all are counted. */
    static final int SHOWN = 50;

    /** Names Windows cannot hold: reserved device names, with or without an extension. */
    private static final Pattern RESERVED = Pattern.compile("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?");

    /** Characters Windows refuses in a name. */
    private static final Pattern WINDOWS_CHARS = Pattern.compile("[<>:\"|?*\\x00-\\x1F\\\\]");

    /**
     * @param copied the paths to copy, as git wrote them
     * @param skipped {@code REASON: path} for each one left out
     * @param bytes the size of what will be copied
     */
    record Plan(List<String> copied, List<String> skipped, long bytes) {}

    private ProofCopyRules() {}

    static Plan plan(Path root, List<String> listed) {
        List<String> copied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> folded = new HashSet<>();
        long bytes = 0;
        for (String p : listed) {
            String why = nameProblem(p);
            if (why == null && !folded.add(p.toLowerCase(Locale.ROOT))) why = "CASE_COLLISION";
            long size = -1;
            if (why == null) {
                size = sizeOf(root, p);
                if (size == -2) why = "OUTSIDE";
                else if (size < 0) why = "NOT_A_FILE";
            }
            if (why != null) {
                skipped.add(why + ": " + p);
                continue;
            }
            copied.add(p);
            bytes += size;
        }
        return new Plan(List.copyOf(copied), List.copyOf(skipped), bytes);
    }

    /** Why a path git lists cannot be copied here, read from its text alone; null when it can. */
    static String nameProblem(String p) {
        if (p.isEmpty() || p.startsWith("/") || p.contains("\\")) return "OUTSIDE";
        for (String part : p.split("/")) {
            if (part.equals("..") || part.equals(".")) return "OUTSIDE";
            if (part.equalsIgnoreCase(".git")) return "GIT";
            if (WINDOWS_CHARS.matcher(part).find()
                    || RESERVED.matcher(part).matches()
                    || part.endsWith(".")
                    || part.endsWith(" ")) return "WINDOWS_NAME";
        }
        return null;
    }

    /** The size of a plain file inside the worktree; -1 for anything else (a link, a junction, a folder), -2 outside. */
    private static long sizeOf(Path root, String p) {
        try {
            Path source = root.resolve(p).normalize();
            if (!source.startsWith(root)) return -2;
            BasicFileAttributes a = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile() || a.isOther()) return -1;
            if (!source.getParent().toRealPath().startsWith(root)) return -2;
            return a.size();
        } catch (IOException | InvalidPathException e) {
            // Handled: a file that cannot be read now is left out and named, never copied half
            return -1;
        }
    }
}
