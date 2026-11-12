package dev.floor42.server.runtime;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * The dependency folders of a worktree that a proof copy lacks because git ignores them (ADR-0058, amended 2026-11-05):
 * every {@code node_modules} under it, not inside another one and not leading outside the worktree (a link or a
 * junction). The sandbox mounts them read-only into the copy; nothing is copied and the run stays offline.
 */
final class DependencyMounts {

    private static final int MAX_DEPTH = 6;
    private static final int MAX_MOUNTS = 20;

    private DependencyMounts() {}

    /** {@code argv} plus the two arguments of run.sh (${11} the folders joined by ':', ${12} the source), when any. */
    static List<String> with(List<String> argv, Path source, String linuxSource) {
        List<String> deps = in(source);
        if (deps.isEmpty()) return argv;
        List<String> all = new ArrayList<>(argv);
        all.add(String.join(":", deps));
        all.add(linuxSource);
        return all;
    }

    /** The folders as paths relative to {@code source}, with '/' (only plain characters: they reach a shell script). */
    static List<String> in(Path source) {
        List<String> found = new ArrayList<>();
        try {
            Path root = source.toRealPath();
            Files.walkFileTree(source, java.util.Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                    String name = String.valueOf(dir.getFileName());
                    if (dir.equals(source)) return FileVisitResult.CONTINUE;
                    if (name.equals(".git") || !dir.toRealPath().startsWith(root)) return FileVisitResult.SKIP_SUBTREE;
                    if (!name.equals("node_modules")) return FileVisitResult.CONTINUE;
                    String rel = source.relativize(dir).toString().replace(java.io.File.separatorChar, '/');
                    if (rel.matches("[A-Za-z0-9._/-]+") && found.size() < MAX_MOUNTS) found.add(rel);
                    return FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    // Handled: an unreadable entry just has no dependencies mounted
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            // Handled: no mount is added; the run is as it was before
            return List.of();
        }
        return found;
    }
}
