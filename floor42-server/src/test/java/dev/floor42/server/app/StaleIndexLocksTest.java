package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A planted index.lock, in a linked worktree's git directory and in a plain .git: old ones go, a fresh one stays. */
class StaleIndexLocksTest {

    @TempDir
    Path tmp;

    private Path linked() throws IOException {
        Path gitDir = Files.createDirectories(tmp.resolve("repo/.git/worktrees/w1"));
        Path worktree = Files.createDirectories(tmp.resolve("workspaces/M-1-dev"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + gitDir.toAbsolutePath() + "\n");
        return worktree;
    }

    private static Path plant(Path lock, long ageSeconds) throws IOException {
        Files.writeString(lock, "");
        Files.setLastModifiedTime(lock, FileTime.from(Instant.now().minusSeconds(ageSeconds)));
        return lock;
    }

    @Test
    void anOldLockOfALinkedWorktreeIsRemoved() throws IOException {
        Path worktree = linked();
        Path lock = plant(tmp.resolve("repo/.git/worktrees/w1/index.lock"), 600);

        assertThat(StaleIndexLocks.clear(worktree, true)).isTrue();

        assertThat(lock).doesNotExist();
    }

    @Test
    void aFreshLockMayBeALiveGitCommandAndStays() throws IOException {
        Path worktree = linked();
        Path lock = plant(tmp.resolve("repo/.git/worktrees/w1/index.lock"), 1);

        assertThat(StaleIndexLocks.clear(worktree, true)).isFalse();

        assertThat(lock).exists();
    }

    @Test
    void withoutProofThatSessionsStoppedOnlyAVeryOldLockGoes() throws IOException {
        Path worktree = linked();
        Path lock = plant(tmp.resolve("repo/.git/worktrees/w1/index.lock"), 120);

        assertThat(StaleIndexLocks.clear(worktree, false)).isFalse();
        assertThat(lock).exists();

        plant(lock, 700);
        assertThat(StaleIndexLocks.clear(worktree, false)).isTrue();
    }

    @Test
    void aProcessRecordLeftBehindMeansSessionsAreNotProvenStopped() throws IOException {
        Path sessions = Files.createDirectories(tmp.resolve("sessions"));
        Files.createDirectories(sessions.resolve("s1"));
        assertThat(StaleIndexLocks.sessionsStopped(sessions)).isTrue();

        Files.writeString(sessions.resolve("s1/process.txt"), "1 x");

        assertThat(StaleIndexLocks.sessionsStopped(sessions)).isFalse();
    }

    @Test
    void aPlainGitFolderAndAWorktreeWithoutLockAreHandled() throws IOException {
        Path plain = Files.createDirectories(tmp.resolve("plain/.git"));
        Path lock = plant(plain.resolve("index.lock"), 600);

        assertThat(StaleIndexLocks.clear(plain.getParent(), true)).isTrue();
        assertThat(lock).doesNotExist();
        assertThat(StaleIndexLocks.clear(linked(), true)).isFalse();
        assertThat(StaleIndexLocks.clear(tmp.resolve("missing"), true)).isFalse();
    }
}
