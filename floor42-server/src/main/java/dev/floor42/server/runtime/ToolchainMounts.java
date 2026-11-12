package dev.floor42.server.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What of the host's toolchains and caches the native Linux sandbox shows, read-only, at the same path (ADR-0028,
 * amended 2026-11-09). On a native Linux host the new root holds only the system folders, so a JDK or Node installed
 * under the home folder (sdkman, nvm) and the Maven cache would be gone. Exposed, and nothing else:
 *
 * <ul>
 *   <li>{@code ~/.m2/repository} (artifacts only: never {@code settings.xml}, which holds server credentials). Not the
 *       npm cache: {@code make-fetch-happen} stores the {@code Authorization} header when a registry answers
 *       {@code Vary: Authorization}, and npm runs offline from the project's {@code node_modules} anyway;
 *   <li>the install root of each of java, node, mvn, npm, gradle and python3 that the daemon's absolute {@code PATH}
 *       entries find. The binary is resolved to its real path first. Under the home folder only a known layout is shown
 *       ({@link #LAYOUTS}: sdkman, nvm, pyenv versions, asdf, fnm, uv, mise). Outside the system folders only a folder
 *       recognised as a toolchain by its own markers ({@link #kind}: a JDK has {@code release} and {@code bin/java}, a Node
 *       {@code bin/node} and {@code lib/node_modules}, and so on). The folder above an arbitrary {@code bin} is never
 *       shown. A tool that is neither is hidden, and {@link #resolve} says which and why.
 * </ul>
 *
 * Inside a shown root the files that usually hold credentials (npmrc, settings.xml, pip.conf, keys, ...) are masked by
 * {@code sandbox-mounts.py}: defence in depth, not a promise. The mounts are made from open handles, so a swapped link is
 * never followed. The file holds lines {@code dir <path>} (a root, masked), {@code cache <path>} and {@code bin <path>}.
 */
final class ToolchainMounts {

    private static final List<String> TOOLS = List.of("java", "node", "mvn", "npm", "gradle", "python3");

    /** Install roots below the home folder, one component per element, {@code *} any single name. */
    static final List<String> LAYOUTS = List.of(
            ".sdkman/candidates/*/*",
            ".nvm/versions/node/*",
            ".pyenv/versions/*",
            ".asdf/installs/*/*",
            ".local/share/fnm/node-versions/*/installation",
            ".local/share/uv/python/*",
            ".local/share/mise/installs/*/*");

    /** The system folders the sandbox's new root has: a tool there needs nothing mounted. */
    static final List<Path> VISIBLE =
            List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/sbin"), Path.of("/lib"), Path.of("/lib64"));

    /** Folders the sandbox does not have (or has empty): a tool there cannot be shown, and is reported. */
    static final List<Path> UNAVAILABLE = List.of(
            Path.of("/etc"),
            Path.of("/var"),
            Path.of("/proc"),
            Path.of("/sys"),
            Path.of("/dev"),
            Path.of("/run"),
            Path.of("/tmp"), // a folder the sandbox hides, never one Floor42 writes to
            Path.of("/media"),
            Path.of("/mnt"));

    private ToolchainMounts() {}

    /**
     * @param dirs install roots (credential files masked)
     * @param caches folders shown as they are
     * @param hidden tool name to the reason it is not shown
     */
    record Result(Set<Path> dirs, Set<Path> caches, Set<Path> bins, Map<String, String> hidden) {}

    /**
     * The listing for this machine. On Windows (WSL backend) only the Maven cache, as its WSL path: the distribution
     * keeps its own tools. The script mounts it outside /mnt, which it masks.
     */
    static String listing(String home, Map<String, String> env) {
        if (SandboxHost.windows(SandboxHost.current())) return windowsCache(home);
        if (!SandboxHost.linux(SandboxHost.current()) || home == null) return "";
        return listing(resolve(home, env, VISIBLE, UNAVAILABLE));
    }

    /** {@code cache /mnt/c/Users/me/.m2/repository} for a Windows home on a drive letter, else nothing. */
    static String windowsCache(String home) {
        String wsl = wslRepository(home);
        return wsl == null || !Files.isDirectory(Path.of(home, ".m2", "repository")) ? "" : "cache " + wsl + "\n";
    }

    /** The WSL path of {@code <home>\.m2\repository}; null for a home not on a drive letter, or with a space or a control character (MAVEN_ARGS is split on spaces). */
    static String wslRepository(String home) {
        if (home == null || !home.matches("[A-Za-z]:[\\\\/].*")) return null;
        String wsl = "/mnt/" + Character.toLowerCase(home.charAt(0))
                + home.substring(2).replace('\\', '/');
        wsl = wsl.replaceAll("/+$", "") + "/.m2/repository";
        return wsl.matches(".*[\\s\\p{Cntrl}].*") ? null : wsl;
    }

    static String listing(Result r) {
        StringBuilder text = new StringBuilder();
        r.dirs().forEach(d -> text.append("dir ").append(d).append('\n'));
        r.caches().forEach(d -> text.append("cache ").append(d).append('\n'));
        r.bins().forEach(b -> text.append("bin ").append(b).append('\n'));
        return text.toString();
    }

    static Result resolve(String home, Map<String, String> env, List<Path> visible, List<Path> unavailable) {
        Result r =
                new Result(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashMap<>());
        Path h;
        try {
            // A home that is a link (/home to /var/home on Fedora atomic) is judged by where it really is.
            h = Path.of(home).toRealPath();
        } catch (IOException | RuntimeException e) {
            // Handled: no home folder to judge by, nothing is shown
            return r;
        }
        Path repository = h.resolve(".m2/repository");
        if (plain(repository, h)) r.caches().add(repository);
        for (String tool : TOOLS) {
            Path bin = find(tool, env.get("PATH"));
            if (bin != null) place(tool, bin, h, visible, unavailable, r);
        }
        return r;
    }

    /** Shows the install root of one tool, or records why it is not shown. */
    private static void place(String tool, Path bin, Path h, List<Path> visible, List<Path> unavailable, Result r) {
        Path real;
        try {
            real = bin.toRealPath();
        } catch (IOException e) {
            // Handled: a tool that cannot be resolved is not shown
            r.hidden().put(tool, tool + " (" + bin + ") cannot be resolved: " + e.getMessage());
            return;
        }
        if (visible.stream().anyMatch(real::startsWith)) return; // the sandbox has the system folders already
        Path gone = unavailable.stream().filter(real::startsWith).findFirst().orElse(null);
        if (gone != null) {
            r.hidden().put(tool, tool + " (" + real + ") is under " + gone + ", which the sandbox does not have");
            return;
        }
        boolean inHome = real.startsWith(h);
        Path root = inHome ? layoutRoot(h, real) : markedRoot(h, real, tool);
        if (root == null) {
            r.hidden().put(tool, notShown(tool, real, inHome));
        } else if (plain(root, h)) {
            r.dirs().add(root);
            if (Files.isDirectory(root.resolve("bin"))) r.bins().add(root.resolve("bin"));
        }
    }

    private static String notShown(String tool, Path real, boolean inHome) {
        return tool + " (" + real + ") is in "
                + (inHome
                        ? "no known toolchain layout under your home folder (sdkman, nvm, pyenv versions, asdf, fnm, uv,"
                                + " mise)"
                        : "no folder recognised as a toolchain install (markers such as release and bin/java)")
                + ": showing the folder around it would show more than the tool";
    }

    /** The install root of a binary below the home folder, when its path matches a known layout. */
    private static Path layoutRoot(Path home, Path real) {
        Path rel = home.relativize(real);
        for (String layout : LAYOUTS) {
            String[] parts = layout.split("/");
            if (rel.getNameCount() <= parts.length) continue;
            boolean match = true;
            for (int i = 0; i < parts.length && match; i++) {
                match = parts[i].equals("*") || parts[i].equals(rel.getName(i).toString());
            }
            if (match) return home.resolve(rel.subpath(0, parts.length));
        }
        return null;
    }

    /** Outside the home folder: the nearest folder above the binary that has a toolchain's own markers. */
    private static Path markedRoot(Path home, Path real, String tool) {
        Path p = real.getParent();
        for (int i = 0; i < 6 && p != null && p.getNameCount() >= 2; i++, p = p.getParent()) {
            if (home.startsWith(p)) return null;
            // The markers must be those of this tool: a node under a Python install is not that install's tool.
            if (expected(tool).equals(kind(p))) return p;
        }
        return null;
    }

    private static String expected(String tool) {
        return switch (tool) {
            case "java" -> "jdk";
            case "node", "npm" -> "node";
            case "mvn" -> "maven";
            case "gradle" -> "gradle";
            default -> "python";
        };
    }

    /** What kind of toolchain install a folder is, by markers a stray {@code bin} folder does not have. */
    static String kind(Path root) {
        if (Files.isRegularFile(root.resolve("release")) && Files.isExecutable(root.resolve("bin/java"))) return "jdk";
        if (Files.isExecutable(root.resolve("bin/node")) && Files.isDirectory(root.resolve("lib/node_modules"))) {
            return "node";
        }
        if (Files.isExecutable(root.resolve("bin/mvn")) && Files.isDirectory(root.resolve("boot"))) return "maven";
        if (Files.isExecutable(root.resolve("bin/gradle")) && Files.isDirectory(root.resolve("lib"))) return "gradle";
        if (Files.isExecutable(root.resolve("bin/python3")) && python(root.resolve("lib"))) return "python";
        return null;
    }

    private static boolean python(Path lib) {
        try (var entries = Files.newDirectoryStream(lib, "python3*")) {
            return entries.iterator().hasNext();
        } catch (IOException e) {
            // Handled: no lib folder, not a Python install
            return false;
        }
    }

    /** The directory of {@code tool} among the absolute PATH entries (empty and relative ones mean the daemon's cwd). */
    private static Path find(String tool, String path) {
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank() || !Path.of(dir).isAbsolute()) continue;
            Path candidate = Path.of(dir).resolve(tool);
            if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }

    /** A real folder without a link in its path, no control character in it, and not the home folder or above it. */
    private static boolean plain(Path dir, Path home) {
        try {
            return Files.isDirectory(dir)
                    && dir.toRealPath().equals(dir)
                    && dir.getNameCount() >= 2
                    && !home.startsWith(dir)
                    && dir.toString().chars().noneMatch(Character::isISOControl);
        } catch (IOException | RuntimeException e) {
            // Handled: nothing is mounted for it
            return false;
        }
    }
}
