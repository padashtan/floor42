package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reviewer's read-only mount in the real WSL (ADR-0046, amended 2026-11-05): in "ro" mode no command writes to the
 * work or to the worktree's git directory, whatever it tries, while /tmp stays writable for the test runners. The proof
 * is on the Windows side: the files are unchanged and nothing new exists. Skipped where WSL's Debian does not run.
 */
class SandboxReadOnlyTest {

    private static final String DISTRO = "Debian";

    @TempDir
    Path data;

    @BeforeAll
    static void sandboxRunsHere() {
        WslSandbox.platformOrSkip();
    }

    /** Every write a reviewer's command might try; WROTE marks a command that succeeded, FAILED one that was refused. */
    static final String WRITES = """
            try() { if "$@" >/dev/null 2>&1; then echo "WROTE $1 $2 $3"; else echo "FAILED $1 $2 $3"; fi; }
            try sh -c 'echo x > new.txt'
            try sh -c 'echo x >> a.txt'
            try sh -c ': > a.txt'
            try rm a.txt
            try mv a.txt b.txt
            try mkdir sub
            try touch a.txt
            try chmod 777 a.txt
            try ln -s a.txt link
            try sh -c 'echo x >> .git'
            try git add a.txt
            try git commit -q -m x --allow-empty
            try sh -c 'echo x >> "$GIT_DIR/index"'
            try sh -c 'echo x > "$GIT_DIR/new"'
            try mount -o remount,bind,rw /media/work
            try sh -c 'mkdir -p /tmp/o && mount --bind /media/work /tmp/o && echo x > /tmp/o/viabind.txt'
            try sh -c 'echo ok > /tmp/scratch.txt && grep -q ok /tmp/scratch.txt'
            try sh -c 'cp -r /media/work /tmp/copy && echo y >> /tmp/copy/a.txt && grep -q y /tmp/copy/a.txt'
            try git status --porcelain
            try git diff --stat
            echo ro-end
            """;

    @Test
    void inReadOnlyModeNoWriteToTheWorkReachesWindowsButTmpStaysWritable() throws Exception {
        Path wt = linkedWorktree();

        Result r = run(sandbox().argv(wt, WRITES, 120, true));

        assertThat(r.out()).as("the script ran to its end").contains("ro-end");
        List<String> wrote = r.out().lines().filter(l -> l.startsWith("WROTE")).toList();
        assertThat(wrote)
                .as(r.out())
                .containsExactlyInAnyOrder(
                        "WROTE sh -c echo ok > /tmp/scratch.txt && grep -q ok /tmp/scratch.txt",
                        "WROTE sh -c cp -r /media/work /tmp/copy && echo y >> /tmp/copy/a.txt && grep -q y /tmp/copy/a.txt",
                        "WROTE git status --porcelain",
                        "WROTE git diff --stat");
        assertThat(Files.readString(wt.resolve("a.txt")))
                .as("unchanged on Windows")
                .isEqualToNormalizingNewlines("a\n");
        try (var files = Files.list(wt)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList())
                    .as("nothing was added to or removed from the work")
                    .containsExactlyInAnyOrder("a.txt", ".git");
        }
        assertThat(GitProcess.git(wt, List.of("status", "--porcelain")).out())
                .as("nothing was staged")
                .isEmpty();
        assertThat(GitProcess.git(wt, List.of("rev-list", "--count", "HEAD"))
                        .out()
                        .strip())
                .as("no commit was made")
                .isEqualTo("1");
    }

    @Test
    void theSameCommandsWriteInReadWriteModeSoTheTestSeesTheDifference() throws Exception {
        Path wt = linkedWorktree();

        Result r = run(sandbox().argv(wt, "echo x > new.txt; echo x >> a.txt; echo rw-end", 120, false));

        assertThat(r.out()).contains("rw-end");
        assertThat(wt.resolve("new.txt")).exists();
        assertThat(Files.readString(wt.resolve("a.txt"))).isEqualToNormalizingNewlines("a\nx\n");
    }

    /** A repository with a linked worktree, so the sandbox also mounts the repository's git directory. */
    private Path linkedWorktree() throws IOException, InterruptedException {
        Path repo = Files.createDirectories(data.resolve("repo"));
        assertThat(GitProcess.git(repo, List.of("init", "-q", "-b", "main")).code())
                .isZero();
        Files.writeString(repo.resolve("a.txt"), "a\n");
        GitProcess.git(repo, List.of("add", "a.txt"));
        GitProcess.git(repo, List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "a"));
        Path wt = data.resolve("wt");
        assertThat(GitProcess.git(repo, List.of("worktree", "add", "-q", wt.toString()))
                        .code())
                .isZero();
        assertThat(WorktreeGitDirs.gitDirs(wt)).isPresent();
        return wt;
    }

    private Sandbox sandbox() {
        Sandbox s = new Sandbox();
        s.policy = new SandboxPolicy() {
            @Override
            public boolean sandboxEnabled() {
                return true;
            }

            @Override
            public boolean sandboxNetwork() {
                return false;
            }
        };
        s.distro = DISTRO;
        s.dataDir = data;
        return s;
    }

    record Result(int code, String out) {}

    private static Result run(List<String> argv) {
        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            p.getOutputStream().close();
            var out = new java.io.ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    p.getInputStream().transferTo(out);
                } catch (IOException e) {
                    // Handled: the output read so far is kept
                }
            });
            if (!p.waitFor(180, TimeUnit.SECONDS)) p.destroyForcibly();
            reader.join(5000);
            return new Result(p.isAlive() ? -1 : p.exitValue(), out.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Handled: the test reads a failed start as a failed run
            return new Result(-1, e.getMessage());
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept; the run reads as failed
            Thread.currentThread().interrupt();
            return new Result(-1, "interrupted");
        }
    }
}
