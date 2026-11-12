package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.floor42.server.runtime.Git;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The code a proof is bound to and the copy it runs on (ADR-0058): a huge file never read whole, names Windows cannot
 * hold and colliding cases left out with a reason, the size cap checked before anything is copied, and a copy deleted
 * without following a junction a step left in it.
 */
class ProofSnapshotTest {

    @TempDir
    Path tmp;

    ProofSnapshot snapshot() {
        ProofSnapshot s = new ProofSnapshot();
        s.git = new Git();
        s.dataDir = tmp.resolve("data");
        return s;
    }

    Path repository() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Files.writeString(repo.resolve("README.md"), "# Project\n");
        run(repo, "git", "init", "-q", "-b", "main");
        run(repo, "git", "add", ".");
        run(repo, "git", "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "init");
        return repo;
    }

    static void run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).as(String.join(" ", cmd)).isZero();
    }

    /** A file of {@code size} bytes that takes no room on disk (sparse where the file system allows it). */
    static void sparse(Path file, long size) throws Exception {
        try (SeekableByteChannel c = Files.newByteChannel(
                file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            c.position(size - 1);
            c.write(ByteBuffer.wrap(new byte[] {1}));
        }
    }

    @Test
    void aHugeNewFileIsNamedBySizeAndTimeNeverReadWhole() throws Exception {
        Path repo = repository();
        ProofSnapshot s = snapshot();
        ProofSnapshot.Code before = s.code(repo);
        sparse(repo.resolve("huge.bin"), 3L * 1024 * 1024 * 1024);
        ProofSnapshot.Code after = s.code(repo);
        assertThat(after.head()).isEqualTo(before.head());
        assertThat(after.changesDigest()).isNotEqualTo(before.changesDigest()).matches("[0-9a-f]{16}");
        assertThat(s.code(repo)).isEqualTo(after);
        // ...and past the size cap, the copy is refused before a byte is written.
        assertThatThrownBy(() -> s.copy(repo, "M-1-chris")).hasMessageContaining("bytes to copy");
        assertThat(tmp.resolve("data/proofs")).isEmptyDirectory();
    }

    @Test
    void namesWindowsCannotHoldCollidingCasesAndEscapesAreLeftOutWithWhy() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root")).toRealPath();
        Files.writeString(root.resolve("README.md"), "x");
        ProofCopyRules.Plan plan = ProofCopyRules.plan(
                root,
                List.of(
                        "README.md",
                        "readme.md",
                        "a:b.txt",
                        "con.txt",
                        "docs/aux",
                        "x.",
                        "../up.txt",
                        ".git/config",
                        "missing.txt"));
        assertThat(plan.copied()).containsExactly("README.md");
        assertThat(plan.bytes()).isEqualTo(1);
        assertThat(plan.skipped())
                .containsExactly(
                        "CASE_COLLISION: readme.md",
                        "WINDOWS_NAME: a:b.txt",
                        "WINDOWS_NAME: con.txt",
                        "WINDOWS_NAME: docs/aux",
                        "WINDOWS_NAME: x.",
                        "OUTSIDE: ../up.txt",
                        "GIT: .git/config",
                        "NOT_A_FILE: missing.txt");
    }

    @Test
    void aFileReachedThroughAJunctionIsNeverCopied() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root")).toRealPath();
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "s");
        assumeTrue(junction(root.resolve("link"), outside), "no junction on this machine");
        ProofCopyRules.Plan plan = ProofCopyRules.plan(root, List.of("link/secret.txt", "link"));
        assertThat(plan.copied()).isEmpty();
        assertThat(plan.skipped()).containsExactly("OUTSIDE: link/secret.txt", "NOT_A_FILE: link");
    }

    @Test
    void aCopyIsDeletedWithoutFollowingAJunctionLeftInIt() throws Exception {
        Path copy = Files.createDirectories(tmp.resolve("data/proofs/M-1-chris-1"));
        Files.writeString(copy.resolve("built.txt"), "b");
        Path scratch = Files.createDirectories(tmp.resolve("scratch"));
        Files.writeString(scratch.resolve("keep.txt"), "kept");
        assumeTrue(junction(copy.resolve("escape"), scratch), "no junction on this machine");
        assertThat(ProofFolders.delete(copy)).isEmpty();
        assertThat(copy).doesNotExist();
        assertThat(scratch.resolve("keep.txt")).hasContent("kept");
    }

    /** A junction on Windows (no privilege needed), a symbolic link elsewhere: a link the guards must not follow. */
    static boolean junction(Path link, Path target) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            Files.createSymbolicLink(link, target);
            return true;
        }
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        p.getInputStream().readAllBytes();
        return p.waitFor() == 0 && Files.exists(link);
    }

    @Test
    void theStartupCleanDeletesLeftoverCopiesButNotScratchFiles() throws Exception {
        Path proofs = Files.createDirectories(tmp.resolve("data/proofs"));
        Path left = Files.createDirectories(proofs.resolve("M-1-chris-123"));
        Files.writeString(left.resolve("a.txt"), "x");
        Path scratch = Files.writeString(proofs.resolve("diff-1.bin"), "x");
        ProofSnapshot.clean(proofs);
        assertThat(left).doesNotExist();
        assertThat(scratch).exists();
        ProofSnapshot.clean(tmp.resolve("data/absent"));
    }

    @Test
    void theStartupCleanFollowsNoLinkAndNeverThrows() throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("outside/keep"));
        Files.writeString(outside.resolve("f.txt"), "x");
        Path link = tmp.resolve("data/proofs");
        Files.createDirectories(link.getParent());
        org.junit.jupiter.api.Assumptions.assumeTrue(junction(link, outside.getParent()), "no junction here");
        ProofSnapshot.clean(link);
        assertThat(outside.resolve("f.txt")).exists();
        ProofSnapshot.clean(outside.resolve("f.txt"));
    }

    @Test
    void aCopyNameThatClimbsOrNamesAFolderIsRefusedBeforeAnythingIsWritten() throws Exception {
        ProofSnapshot s = snapshot();
        for (String bad : new String[] {"../x", "a/b", "a\\b", "..", "x/../../y", "", " "}) {
            assertThatThrownBy(() -> s.copy(tmp, bad)).hasMessageContaining("without separators");
        }
        assertThat(tmp.resolve("data")).doesNotExist();
    }
}
