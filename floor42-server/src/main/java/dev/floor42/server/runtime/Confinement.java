package dev.floor42.server.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import dev.floor42.domain.ActionCategory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps file tools inside the agent's workspace (ADR-0012). The check runs on the real file system: a symbolic link or
 * junction inside the worktree that points elsewhere counts as elsewhere.
 *
 * <p>It covers the file tools only. A shell command can reach anything the director's account can: Bash stays under
 * the director's eye (ask per exact command) until sessions run in an operating-system sandbox.
 */
public final class Confinement {

    /** Inside the worktree, but able to change what runs later outside the agent's control: git, Claude Code, MCP. */
    private static final Set<String> PROTECTED = Set.of(".git", ".claude", ".mcp.json");

    /** An 8.3 short name ("GIT~1"). Searched with find(): linear in the name, whatever an agent writes in it. */
    private static final java.util.regex.Pattern SHORT_NAME = java.util.regex.Pattern.compile("~\\d");

    private Confinement() {}

    /**
     * The first of {@code paths} (relative to a worktree) under {@code .claude} or named {@code .mcp.json}: what a
     * session of Claude Code reads later outside the sandbox. A delivery that carries one is refused.
     */
    public static Optional<String> configurationPath(java.util.Collection<String> paths) {
        for (String path : paths) {
            for (String segment : path.replace('\\', '/').split("/")) {
                String name = segment.toLowerCase(Locale.ROOT);
                if (name.equals(".claude") || name.equals(".mcp.json")) return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    /** Why the call leaves the workspace, or empty when it stays inside or touches no file. */
    public static Optional<String> violation(String tool, JsonNode input, String workdir) {
        ActionCategory category = ToolCatalog.category(tool);
        if (category != ActionCategory.READ && category != ActionCategory.WRITE) return Optional.empty();
        if (workdir == null || workdir.isBlank()) return Optional.of("no workspace to confine the call to");
        Path root;
        try {
            root = Path.of(workdir).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            // Handled: a workspace that is no path confines nothing: the call is refused
            return Optional.of("invalid workspace");
        }
        for (String field : new String[] {"file_path", "notebook_path", "path"}) {
            if (!input.hasNonNull(field)) continue;
            Optional<String> v = check(input.path(field).asText(), root, category == ActionCategory.WRITE);
            if (v.isPresent()) return v;
        }
        // A glob pattern is a path too: "../../**/*.key" or an absolute pattern searches outside the search root.
        if (tool.equals("Glob") && input.hasNonNull("pattern")) {
            String pattern = input.path("pattern").asText().replace('\\', '/');
            if (pattern.startsWith("/") || pattern.startsWith("~") || pattern.matches("^[A-Za-z]:.*")) {
                return Optional.of("absolute glob pattern: outside the agent's worktree");
            }
            for (String segment : pattern.split("/")) {
                if (segment.equals("..")) return Optional.of("glob pattern climbs out of the agent's worktree");
            }
        }
        return Optional.empty();
    }

    /**
     * The path inside {@code root} the workshop, the deliverables and the director's files may use, once the same
     * check as agents' file tools accepts it and it names no {@code .git} (read or write). Refused with an
     * {@link OutsideWorkspace} that says why.
     */
    public static ConfinedPath relative(Path root, String path, ConfinedPath.Mode mode) {
        Path r = root.toAbsolutePath().normalize();
        refusal(r, path, mode).ifPresent(v -> {
            throw new OutsideWorkspace(v);
        });
        String relative = r.relativize(r.resolve(path.replace('\\', '/')).normalize())
                .toString()
                .replace('\\', '/');
        return new ConfinedPath(r, relative);
    }

    /** Why {@link #relative} would refuse {@code path}, or empty when it accepts it. */
    public static Optional<String> refusal(Path root, String path, ConfinedPath.Mode mode) {
        Path r = root.toAbsolutePath().normalize();
        Optional<String> v = check(path, r, mode == ConfinedPath.Mode.WRITE);
        if (v.isPresent() || path.isBlank()) return v;
        // Read or written, git's own data stays out: the name as written (".git", ".GIT.", its 8.3 name "GIT~1"),
        // and the real name of what exists (a link named "notes" that is .git).
        Path target = r.resolve(path.replace('\\', '/')).normalize();
        for (Path segment : r.relativize(target)) {
            String name = plain(segment.toString());
            if (name.equals(".git") || name.matches("git~\\d+")) return Optional.of(path + " is git's own data");
        }
        Optional<Path> real = realInside(r, target);
        if (real.isPresent()) {
            for (Path segment : real.get()) {
                if (plain(segment.toString()).equals(".git")) return Optional.of(path + " is git's own data");
            }
        }
        return Optional.empty();
    }

    public static Optional<String> check(String raw, Path root, boolean write) {
        if (raw.isBlank()) return Optional.empty();
        // A backslash is a separator on Windows and only a file name character elsewhere. Splitting on it everywhere
        // never yields a deeper path than the literal one, so it can only refuse more, and paths recorded on Windows
        // ("/workspace\notes.txt") are judged the same on every OS.
        raw = raw.replace('\\', '/');
        if (raw.startsWith("~")) return Optional.of(raw + " is in the home directory, outside the agent's worktree");
        Path target;
        try {
            target = root.resolve(raw).normalize();
        } catch (InvalidPathException e) {
            // Handled: a path that cannot be parsed is refused
            return Optional.of(raw + " is not a valid path");
        }
        if (!target.startsWith(root))
            return Optional.of(raw + " is outside the agent's worktree"
                    + sandboxHint(raw, root.getFileSystem().getSeparator().equals("\\")));
        Optional<Path> real;
        try {
            real = realRelative(root, target);
        } catch (IOException e) {
            // Handled: a path that cannot be resolved is refused
            return Optional.of(raw + " cannot be resolved: " + e.getMessage());
        }
        if (real.isPresent() && real.get().startsWith("..")) {
            return Optional.of(raw + " leads outside the agent's worktree through a link");
        }
        return write ? protectedName(raw, root.relativize(target), real.orElse(null)) : Optional.empty();
    }

    /**
     * A path in the sandbox's form (/media/work, /mnt/c) names no file for the file tools, which run on the host: the
     * agent is told the form they take, so it does not guess another (no mapping: Claude Code would write elsewhere).
     */
    static String sandboxHint(String raw, boolean windowsHost) {
        // On Linux or macOS, /mnt/c may be a real folder: the hint would mislead there.
        if (!windowsHost || !raw.matches("^/(media/work|mnt/[A-Za-z])(/.*)?$")) return "";
        return ": that is the sandbox's path form; file tools run on the host and take a path relative to your"
                + " working directory";
    }

    /**
     * The real path of the deepest part of {@code target} that exists, relative to the real root (starting with
     * {@code ..} when a link leads out); empty when the root or nothing of the target exists. The rest cannot be a
     * link yet.
     */
    private static Optional<Path> realRelative(Path root, Path target) throws IOException {
        if (!Files.exists(root)) return Optional.empty();
        Path realRoot = root.toRealPath();
        Path existing = target;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null) return Optional.empty();
        Path real = existing.toRealPath();
        return Optional.of(real.startsWith(realRoot) ? realRoot.relativize(real) : Path.of(".."));
    }

    /** {@link #realRelative} once {@link #check} accepted the path: a path that cannot be resolved has none. */
    private static Optional<Path> realInside(Path root, Path target) {
        try {
            return realRelative(root, target);
        } catch (IOException e) {
            // Handled: check() just resolved the same path; one that vanished since has no real name to judge.
            return Optional.empty();
        }
    }

    /**
     * Windows drops trailing dots and spaces (".git." is ".git") and resolves 8.3 short names ("GIT~1"); a colon opens
     * an alternate data stream. The name as written and the real name of what exists are both judged.
     */
    private static Optional<String> protectedName(String raw, Path written, Path real) {
        for (Path segment : written) {
            String name = segment.toString();
            if (name.contains(":") || SHORT_NAME.matcher(name).find()) {
                return Optional.of(raw + " is not a plain file name: " + name);
            }
            if (PROTECTED.contains(plain(name))) {
                return Optional.of(raw + " is protected: " + segment + " changes what runs outside the session");
            }
        }
        if (real == null) return Optional.empty();
        for (Path segment : real) {
            if (PROTECTED.contains(plain(segment.toString()))) {
                return Optional.of(
                        raw + " is protected: it is " + segment + ", which changes what runs" + " outside the session");
            }
        }
        return Optional.empty();
    }

    /** The name as Windows reads it: case ignored, trailing dots and spaces dropped. */
    private static String plain(String name) {
        // A loop, not the regex "[. ]+$": that one is quadratic on a long run of spaces an agent can write.
        int end = name.length();
        while (end > 0 && (name.charAt(end - 1) == '.' || name.charAt(end - 1) == ' ')) end--;
        return name.substring(0, end).toLowerCase(Locale.ROOT);
    }
}
