package dev.floor42.server.app;

import dev.floor42.server.config.DataFolders;
import dev.floor42.server.runtime.ConfinedPath;
import dev.floor42.server.runtime.Confinement;
import dev.floor42.server.runtime.OutsideWorkspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Where a mission's accepted deliverables may be copied (ADR-0051), checked on disk by code, never by prompt text.
 *
 * <ul>
 *   <li>A relative folder lies inside the project: {@link Confinement#relative} refuses {@code ..}, {@code .git},
 *       {@code .claude}, {@code .mcp.json} and a link leading out. The project root itself is refused: files would sit
 *       among the sources.
 *   <li>An absolute folder outside the project is one the director typed: it must already exist.
 *   <li>Never a filesystem root, the home folder itself, Floor42's data folder (journal, worktrees, backups) or
 *       Office's former one, nor anything inside them. Each side is compared by its real path (links, 8.3 names, case).
 * </ul>
 *
 * The same checks run again when the files are copied: a link may have appeared since.
 */
final class DeliveryDestination {

    private DeliveryDestination() {}

    /**
     * Folders that configure an agent, an editor, a build or git: a document copied under one of them could become an
     * instruction, a command or a hook. Refused anywhere on disk, compared without case.
     */
    static final Set<String> PROTECTED = Set.of(
            ".claude", ".git", ".github", ".vscode", ".idea", ".mcp.json", "node_modules", ".floor42", ".office");

    /** The real absolute folder for {@code raw}, or null when the director gave none (nothing will be copied). */
    static Path resolve(Path project, String raw, Path dataDir) {
        if (raw == null || raw.isBlank()) return null;
        String typed = raw.strip();
        refuseRemote(typed);
        Path folder;
        boolean relative;
        try {
            Path asked = Path.of(typed);
            relative = !asked.isAbsolute();
            folder = (relative ? project.toAbsolutePath().resolve(asked) : asked).normalize();
        } catch (InvalidPathException e) {
            throw refused(typed + " is not a valid folder", e);
        }
        Path real = realOfNearest(folder);
        boolean inside = real.startsWith(realOfNearest(project.toAbsolutePath().normalize()));
        if (relative && !inside) throw refused(typed + " leaves the project: a relative folder stays inside it");
        if (!inside && !Files.isDirectory(real)) {
            throw refused(folder + " does not exist: a folder outside the project must exist already");
        }
        check(project, real, dataDir);
        return real;
    }

    /**
     * Every check, on the folder's real path (links and junctions followed), inside the project or not: the same at
     * creation and before each copy, when a link may have appeared.
     */
    static void check(Path project, Path folder, Path dataDir) {
        refuseRemote(folder.toString());
        Path real = realOfNearest(folder);
        Path root = realOfNearest(project.toAbsolutePath().normalize());
        if (real.getParent() == null) throw refused(folder + " is the root of a disk");
        if (real.equals(root)) throw refused("The project folder itself is refused: give a folder inside it");
        if (real.startsWith(root)) inProject(root, root.relativize(real).toString());
        for (Path segment : real) {
            if (PROTECTED.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                throw refused(folder + " is in " + segment + ": a folder that configures a tool, never a destination");
            }
        }
        Path home = realOfNearest(
                Path.of(System.getProperty("user.home", "")).toAbsolutePath().normalize());
        if (real.equals(home)) throw refused(folder + " is the home folder: give a folder inside it");
        for (Path system : systemFolders()) {
            if (real.startsWith(realOfNearest(system))) throw refused(folder + " is a system folder");
        }
        for (Path guarded : DataFolders.guarded(dataDir)) {
            if (real.startsWith(realOfNearest(guarded.toAbsolutePath().normalize()))) {
                throw refused(folder + " is inside Floor42's data folder");
            }
        }
    }

    /** A share or a device path is refused from its text alone: no file call ever reaches a server. */
    static void refuseRemote(String typed) {
        String t = typed.replace('/', '\\');
        if (t.startsWith("\\\\")) throw refused(typed + " is a network or device path: give a local folder");
    }

    /** The operating system's own folders. */
    static List<Path> systemFolders() {
        List<Path> out = new ArrayList<>();
        for (String v : List.of("SystemRoot", "windir", "ProgramFiles", "ProgramFiles(x86)", "ProgramData")) {
            String dir = System.getenv(v);
            if (dir != null && !dir.isBlank()) out.add(Path.of(dir));
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            for (String d : List.of(
                    "/etc",
                    "/bin",
                    "/sbin",
                    "/usr",
                    "/lib",
                    "/var",
                    "/boot",
                    "/dev",
                    "/proc",
                    "/sys",
                    "/System",
                    "/Library",
                    "/Applications")) {
                out.add(Path.of(d));
            }
        }
        return out;
    }

    private static void inProject(Path root, String relative) {
        try {
            Confinement.relative(root, relative, ConfinedPath.Mode.WRITE);
        } catch (OutsideWorkspace e) {
            throw refused(e.getMessage(), e);
        }
    }

    /** The real path of the folder, or of its nearest existing parent with the rest appended. */
    static Path realOfNearest(Path folder) {
        Path missing = null;
        Path p = folder;
        while (p != null) {
            Optional<Path> real = real(p);
            if (real.isPresent())
                return missing == null ? real.get() : real.get().resolve(missing);
            Path name = p.getFileName();
            missing = missing == null ? name : name.resolve(missing);
            p = p.getParent();
        }
        return folder;
    }

    private static Optional<Path> real(Path p) {
        try {
            return Files.exists(p) ? Optional.of(p.toRealPath()) : Optional.empty();
        } catch (IOException e) {
            // Handled: a folder whose real path cannot be read is judged by its parent's
            return Optional.empty();
        }
    }

    static RefusedException refused(String why) {
        return RefusedException.invalid("INVALID_DESTINATION", why);
    }

    static RefusedException refused(String why, Throwable cause) {
        return RefusedException.invalid("INVALID_DESTINATION", why, cause);
    }
}
