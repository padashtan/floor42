package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.floor42.server.runtime.Git;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The sample repository (ADR-0045) is Floor42's own: created once, reused, and nothing else is ever taken for it. */
class SampleRepositoryTest {

    @TempDir
    Path data;

    SampleRepository sample() {
        return new SampleRepository(new Git(), data);
    }

    @Test
    void itIsCreatedOnceWithOneCommitThenReused() throws IOException {
        Path dir = sample().prepare();
        assertThat(dir).isEqualTo(sample().location());
        assertThat(dir.resolve(".git")).isDirectory();
        assertThat(dir.resolve("README.md")).content().isEqualTo(SampleRepository.README);
        Files.writeString(dir.resolve("README.md"), "kept");
        assertThat(sample().prepare()).isEqualTo(dir);
        assertThat(dir.resolve("README.md")).as("never rewritten").content().isEqualTo("kept");
    }

    @Test
    void aFolderHoldingFilesWithoutItsOwnGitIsRefusedUntouched() throws IOException {
        Path dir = sample().location();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mine.txt"), "the director's");
        assertThatThrownBy(() -> sample().prepare()).hasMessageContaining("not Floor42's sample");
        assertThat(dir.resolve(".git")).doesNotExist();
        assertThat(dir.resolve("README.md")).doesNotExist();
    }

    @Test
    void aGitfileOrARepositoryWithoutACommitIsRefused() throws IOException {
        Path dir = sample().location();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".git"), "gitdir: /somewhere/else/.git");
        assertThatThrownBy(() -> sample().prepare()).isInstanceOf(RefusedException.class);
        assertThat(dir.resolve(".git")).content().startsWith("gitdir:");
    }

    @Test
    void aRepositoryWithoutACommitIsRefused() throws Exception {
        Path dir = Files.createDirectories(sample().location());
        new Git().out(dir, "init", "-q");
        assertThatThrownBy(() -> sample().prepare()).hasMessageContaining("not Floor42's sample");
        assertThat(dir.resolve("README.md")).doesNotExist();
    }

    @Test
    void aFolderNamedInAnotherCaseIsNoLinkOnWindows() {
        assumeTrue(
                System.getProperty("os.name").toLowerCase().contains("win"), "Windows names files case-insensitively");
        Path dir = sample().location();
        Path other = dir.getParent().getParent().resolve("TUTORIAL").resolve("Sample-Repository");
        try {
            Files.createDirectories(other);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(sample().prepare()).as("the same folder, named differently").isEqualTo(dir);
        assertThat(other.resolve("README.md")).exists();
    }

    @Test
    void aWindowsJunctionIsNeverFollowed() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"), "junctions are Windows'");
        Path elsewhere = Files.createDirectories(data.resolve("elsewhere"));
        Path dir = sample().location();
        Files.createDirectories(dir.getParent());
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", dir.toString(), elsewhere.toString())
                .redirectErrorStream(true)
                .start();
        assumeTrue(mklink.waitFor() == 0, "mklink /J failed");
        assertThat(Files.isSymbolicLink(dir)).as("what the first check missed").isFalse();

        assertThatThrownBy(() -> sample().prepare()).hasMessageContaining("is a link");
        try (var entries = Files.list(elsewhere)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void aLinkIsNeverFollowed() throws IOException {
        Path elsewhere = Files.createDirectories(data.resolve("elsewhere"));
        Path dir = sample().location();
        Files.createDirectories(dir.getParent());
        try {
            Files.createSymbolicLink(dir, elsewhere);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "this machine cannot create a symbolic link: " + e.getMessage());
        }
        assertThatThrownBy(() -> sample().prepare()).hasMessageContaining("is a link");
        try (var entries = Files.list(elsewhere)) {
            assertThat(entries).isEmpty();
        }
    }
}
