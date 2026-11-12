package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One error policy for every git Floor42 runs: an exit code is an answer, a failure that matters is a GitFailure. */
class GitTest {

    @TempDir
    Path tmp;

    final Git git = new Git();
    Path repo;

    @BeforeEach
    void repository() throws IOException {
        repo = Files.createDirectory(tmp.resolve("repo"));
        git.out(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("a.txt"), "hello\n");
        git.out(repo, "add", "a.txt");
        git.out(repo, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "first");
    }

    @Test
    void anExitCodeIsAnAnswerForRun() {
        GitProcess.Result r = git.run(repo, "merge-base", "--is-ancestor", "--end-of-options", "HEAD", "HEAD");
        assertThat(r.code()).isZero();
        GitProcess.Result missing = git.run(repo, "rev-parse", "--verify", "--quiet", "refs/heads/nope");
        assertThat(missing.code()).isEqualTo(1);
        assertThat(missing.timedOut()).isFalse();
    }

    @Test
    void outThrowsAFailureThatKeepsCommandExitCodeAndStderr() {
        assertThatThrownBy(() -> git.out(repo, "cat-file", "blob", "deadbeef"))
                .isInstanceOfSatisfying(GitFailure.class, f -> {
                    assertThat(f.command()).isEqualTo("git cat-file blob deadbeef");
                    assertThat(f.args()).containsExactly("cat-file", "blob", "deadbeef");
                    assertThat(f.exitCode()).isEqualTo(128);
                    assertThat(f.stderr()).contains("deadbeef");
                    assertThat(f.timedOut()).isFalse();
                    assertThat(f.getMessage()).startsWith("git cat-file blob deadbeef failed: ");
                });
    }

    @Test
    void outAcceptsTheExitCodesItIsGiven() {
        String diff = git.out(repo, Set.of(0, 1), List.of("diff", "--no-index", "--", "/dev/null", "a.txt"));
        assertThat(diff).contains("+hello");
        assertThatThrownBy(() -> git.out(repo, "diff", "--no-index", "--", "/dev/null", "a.txt"))
                .isInstanceOf(GitFailure.class)
                .extracting(e -> ((GitFailure) e).exitCode())
                .isEqualTo(1);
    }

    @Test
    void readsThatGitRefusesAreEmptyNotFailures() {
        assertThat(git.outIfOk(repo, "cat-file", "blob", "deadbeef")).isEmpty();
        assertThat(git.resolve(repo, "refs/heads/nope")).isEmpty();
        assertThat(git.resolve(repo, "HEAD")).get().asString().matches("[0-9a-f]{40,64}");
    }

    /** CodeQL java/command-line-injection: git gets a list of arguments, never a shell line, and no option by name. */
    @Test
    void aValueIsNeverReadAsAnOptionOrByAShell() throws IOException {
        // "--git-dir" alone prints the git directory: taken for a revision, it must name nothing.
        assertThat(git.resolve(repo, "--git-dir")).isEmpty();
        assertThat(git.resolve(repo, "-h")).isEmpty();
        assertThat(git.resolve(repo, null)).isEmpty();
        // A shell would run the second command; git gets one argument and finds no such revision.
        Path proof = tmp.resolve("shell-ran");
        assertThat(git.resolve(repo, "HEAD; touch " + proof + " & echo x > " + proof))
                .isEmpty();
        assertThat(proof).doesNotExist();
        // The folder is the value of -C, whatever its name looks like.
        Path odd = Files.createDirectory(tmp.resolve("--version"));
        assertThat(GitProcess.command(odd, List.of("status")))
                .containsSubsequence("git", "-C", odd.toString(), "status")
                .doesNotContain("sh", "bash", "cmd", "cmd.exe");
        assertThat(git.run(odd, "rev-parse", "--is-inside-work-tree").out()).doesNotContain("git version");
    }

    @Test
    void statusIsReadWithItsExtraArguments() throws IOException {
        Files.writeString(repo.resolve("new file.txt"), "x");
        assertThat(git.status(repo, "--untracked-files=all"))
                .containsExactly(new GitProcess.StatusEntry("??", "new file.txt", null));
    }

    @Test
    void toFileCopiesTheBytesOrThrows() throws IOException {
        Path target = tmp.resolve("blob.bin");
        git.toFile(repo, List.of("cat-file", "blob", "HEAD:a.txt"), target);
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("hello\n");
        assertThatThrownBy(() -> git.toFile(repo, List.of("cat-file", "blob", "HEAD:none.txt"), target))
                .isInstanceOf(GitFailure.class)
                .hasMessageContaining("git cat-file blob HEAD:none.txt failed");
    }

    @Test
    void aGitThatCannotRunKeepsItsCause() {
        IOException cause = new IOException("Cannot run program \"git\"");
        GitFailure f = GitFailure.unavailable(List.of("status"), cause);
        assertThat(f.getCause()).isSameAs(cause);
        assertThat(f.exitCode()).isEqualTo(GitFailure.NO_EXIT);
        assertThat(f.getMessage()).isEqualTo("git status failed: git is not available: Cannot run program \"git\"");
        InterruptedException stop = new InterruptedException();
        assertThat(GitFailure.interrupted(List.of("log"), stop).getCause()).isSameAs(stop);
    }

    @Test
    void aTimeOutSaysSo() {
        GitFailure f = GitFailure.of(List.of("fetch"), new GitProcess.Result(-1, "", "", true));
        assertThat(f.timedOut()).isTrue();
        assertThat(f.exitCode()).isEqualTo(GitFailure.NO_EXIT);
        assertThat(f.getMessage()).isEqualTo("git fetch timed out after " + GitProcess.TIMEOUT_SECONDS + " s");
    }
}
