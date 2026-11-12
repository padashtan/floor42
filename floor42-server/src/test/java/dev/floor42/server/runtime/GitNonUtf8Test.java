package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bytes that are not UTF-8 (a CP1252 file, a Latin-1 name) are decoded with a replacement, never an exception. */
class GitNonUtf8Test {

    @TempDir
    Path tmp;

    @Test
    void aCp1252FileDoesNotBreakDiffOrStatus() throws Exception {
        Path repo = Files.createDirectory(tmp.resolve("repo"));
        GitProcess.git(repo, List.of("init", "-q", "-b", "main"));
        Files.write(repo.resolve("a.txt"), "café €\n".getBytes(Charset.forName("windows-1252")));
        GitProcess.git(repo, List.of("add", "a.txt"));
        GitProcess.git(repo, List.of("-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "x"));
        Files.write(repo.resolve("a.txt"), "café € changed\n".getBytes(Charset.forName("windows-1252")));

        GitProcess.Result diff = GitProcess.git(repo, List.of("diff"));
        GitProcess.Result status = GitProcess.git(repo, List.of("status", "--porcelain"));

        assertThat(diff.out()).contains("changed").contains("�");
        assertThat(status.out()).contains("a.txt");
    }
}
