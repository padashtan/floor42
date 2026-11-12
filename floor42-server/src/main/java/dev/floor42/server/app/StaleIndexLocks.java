package dev.floor42.server.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * A crash or a power cut in the middle of a git command leaves {@code index.lock} in the worktree's git directory, and
 * every later git command of that worktree then fails with "File exists". Called at start, when Floor42 has no git
 * process running, it removes a lock old enough not to be a director's own git command running right now (on Windows
 * the delete also fails while a process still holds the file open: then the lock is left, and logged).
 */
final class StaleIndexLocks {

    private static final Logger LOG = Logger.getLogger(StaleIndexLocks.class);

    /** With the previous sessions proven stopped: only a director's own git could still hold a lock this young. */
    static final Duration MIN_AGE = Duration.ofSeconds(30);

    /** Without that proof a long git of a session left running could hold it: only a lock this old is deleted. */
    static final Duration MIN_AGE_UNPROVEN = Duration.ofMinutes(10);

    private StaleIndexLocks() {}

    /**
     * True when no session of the previous daemon can still run: its process record is gone (the start stopped the
     * process and its tree, then deleted the record). A record left behind means that could not be shown. Only
     * {@code index.lock} is cleared: a stale HEAD or ref lock is left to the director.
     */
    static boolean sessionsStopped(Path sessions) {
        if (!Files.isDirectory(sessions)) return true;
        try (var dirs = Files.list(sessions)) {
            // process.txt: SessionProcesses.PROCESS_FILE
            return dirs.noneMatch(d -> Files.exists(d.resolve("process.txt")));
        } catch (IOException e) {
            // Handled: unreadable means not proven
            return false;
        }
    }

    /** Deletes the stale {@code index.lock} of this worktree, if any; true when one was deleted. */
    static boolean clear(Path worktree, boolean sessionsStopped) {
        try {
            Path lock = gitDir(worktree).resolve("index.lock");
            if (!Files.isRegularFile(lock)) return false;
            FileTime modified = Files.getLastModifiedTime(lock);
            Duration min = sessionsStopped ? MIN_AGE : MIN_AGE_UNPROVEN;
            if (modified.toInstant().isAfter(Instant.now().minus(min))) {
                if (!sessionsStopped)
                    LOG.warnf(
                            "Worktree %s: %s left in place, a session of the previous daemon may hold it",
                            worktree, lock);
                return false;
            }
            Files.delete(lock);
            LOG.warnf("Worktree %s: removed the stale %s left by a git command that did not finish", worktree, lock);
            return true;
        } catch (IOException | RuntimeException e) {
            // Handled: a lock another process still holds stays; git will report it, as it did before
            LOG.warnf("Worktree %s: index.lock not removed: %s", worktree, e.toString());
            return false;
        }
    }

    /** The worktree's git directory: {@code .git} itself, or the one a linked worktree's {@code .git} file names. */
    private static Path gitDir(Path worktree) throws IOException {
        Path dotGit = worktree.resolve(".git");
        if (Files.isDirectory(dotGit)) return dotGit;
        String line = Files.readString(dotGit).lines().findFirst().orElse("").strip();
        if (!line.startsWith("gitdir:")) throw new IOException(".git does not name a git directory");
        return worktree.resolve(line.substring("gitdir:".length()).strip()).normalize();
    }
}
