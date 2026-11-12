package dev.floor42.server.app;

import dev.floor42.server.runtime.Git;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import org.jboss.logging.Logger;

/** Stale worktrees of another timeline: recognised by their git common directory, moved aside, never deleted. */
final class WorkspaceDisplacement {

    private static final Logger LOG = Logger.getLogger(WorkspaceDisplacement.class);
    private static final String DISPLACED = ".displaced";

    private final Git git;
    private final Path dataDir;

    WorkspaceDisplacement(Git git, Path dataDir) {
        this.git = git;
        this.dataDir = dataDir;
    }

    /** Outside the mission namespace: {@link #worktrees} never lists it as an agent's. */
    void displace(Path target) {
        Path aside = dataDir.resolve("workspaces")
                .resolve(DISPLACED)
                .resolve(target.getFileName() + "-" + System.currentTimeMillis());
        try {
            Files.createDirectories(aside.getParent());
            Files.move(target, aside);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot move aside the stale worktree " + target, e);
        }
        LOG.warnf("The worktree %s was not this mission's: moved to %s", target, aside);
    }

    boolean sameRepository(String repository, Path worktree) {
        Optional<Path> mine = commonDir(Path.of(repository));
        return mine.isPresent() && mine.equals(commonDir(worktree));
    }

    /** git's common directory of a folder, real path: git may answer relative to the folder (older git, a plain repository). */
    private Optional<Path> commonDir(Path dir) {
        return git.outIfOk(dir, "rev-parse", "--git-common-dir")
                .map(String::strip)
                .flatMap(d -> {
                    try {
                        return Optional.of(dir.resolve(d).toRealPath());
                    } catch (IOException | InvalidPathException e) {
                        // Handled: a path git cannot resolve is no common directory: empty, so not the same repository
                        return Optional.empty();
                    }
                });
    }
}
