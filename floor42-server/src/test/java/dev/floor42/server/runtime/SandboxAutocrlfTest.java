package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The sandbox reads the director's core.autocrlf through Floor42's one git runner, with its timeout and locale. */
class SandboxAutocrlfTest {

    @TempDir
    Path dir;

    @Test
    void theWorktreesOwnSettingIsRead() throws Exception {
        Path repo = Files.createDirectory(dir.resolve("crlf"));
        assertThat(GitProcess.git(repo, List.of("init", "-q", "-b", "main")).code())
                .isZero();
        assertThat(GitProcess.git(repo, List.of("config", "core.autocrlf", "input"))
                        .code())
                .isZero();

        assertThat(WorktreeGitDirs.autocrlf(repo)).isEqualTo("input");

        assertThat(GitProcess.git(repo, List.of("config", "core.autocrlf", "sometimes"))
                        .code())
                .isZero();
        assertThat(WorktreeGitDirs.autocrlf(repo))
                .as("a value git would not accept")
                .isEqualTo("none");
    }
}
