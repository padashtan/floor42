package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.server.runtime.ConfinedPath.Mode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one confined path of the workshop, the deliverables and the director's files: inside, and never .git. */
class ConfinedPathTest {

    @TempDir
    Path root;

    @Test
    void aPathInsideIsRelativeWithForwardSlashes() {
        ConfinedPath p = Confinement.relative(root, "docs\\./notes.md", Mode.READ);
        assertThat(p.relative()).isEqualTo("docs/notes.md");
        assertThat(p.root()).isEqualTo(root.toAbsolutePath().normalize());
        assertThat(p.file()).isEqualTo(root.toAbsolutePath().normalize().resolve("docs/notes.md"));
        assertThat(p.isRoot()).isFalse();
        assertThat(Confinement.relative(root, ".", Mode.READ).isRoot()).isTrue();
    }

    @Test
    void climbingOutIsRefused() {
        for (String path : new String[] {"../outside.txt", "a/../../outside.txt", "~/x", "a\\..\\..\\x"}) {
            assertThatThrownBy(() -> Confinement.relative(root, path, Mode.READ))
                    .as(path)
                    .isInstanceOf(OutsideWorkspace.class);
        }
    }

    @Test
    void gitsOwnDataIsRefusedToReadAndToWrite() {
        for (String path : new String[] {".git/config", "sub/.git/HEAD", ".GIT./config", "a/../.git/config"}) {
            for (Mode mode : Mode.values()) {
                assertThatThrownBy(() -> Confinement.relative(root, path, mode))
                        .as(path + " " + mode)
                        .isInstanceOf(OutsideWorkspace.class);
            }
        }
        assertThat(Confinement.refusal(root, ".git/config", Mode.READ))
                .get()
                .asString()
                .contains("git's own data");
    }

    @Test
    void whatChangesWhatRunsLaterIsRefusedToWriteOnly() {
        assertThat(Confinement.relative(root, ".claude/settings.json", Mode.READ)
                        .relative())
                .isEqualTo(".claude/settings.json");
        assertThatThrownBy(() -> Confinement.relative(root, ".claude/settings.json", Mode.WRITE))
                .isInstanceOf(OutsideWorkspace.class)
                .hasMessageContaining("protected");
        assertThat(Confinement.refusal(root, ".mcp.json", Mode.WRITE)).isPresent();
        assertThat(Confinement.refusal(root, "src/Main.java", Mode.WRITE)).isEmpty();
    }

    @Test
    void aLinkLeadingOutIsRefused() throws IOException {
        Path outside = Files.createTempDirectory("floor42-outside");
        try {
            Files.createSymbolicLink(root.resolve("out"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            // Handled: no symbolic links here (Windows without the privilege); the confinement test covers junctions.
            return;
        }
        assertThatThrownBy(() -> Confinement.relative(root, "out/secret.txt", Mode.READ))
                .isInstanceOf(OutsideWorkspace.class)
                .hasMessageContaining("through a link");
    }

    @Test
    void equalPathsAreEqualValues() {
        assertThat(Confinement.relative(root, "a/b.txt", Mode.WRITE))
                .isEqualTo(Confinement.relative(root, "a/./b.txt", Mode.WRITE));
    }
}
