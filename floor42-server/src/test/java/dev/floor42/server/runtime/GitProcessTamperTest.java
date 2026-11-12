package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A worktree an agent could write into must not make Floor42's own git run a program (ADR-0028, amended 2026-11-03).
 * Each case plants a core.fsmonitor command that would create a marker, then runs git the way Floor42 does.
 */
class GitProcessTamperTest {

    @TempDir
    Path dir;

    Path repo;
    Path wt;
    Path marker;

    @BeforeEach
    void linkedWorktree() throws Exception {
        repo = Files.createDirectories(dir.resolve("repo"));
        ok(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("a.txt"), "a\n");
        ok(repo, "add", "a.txt");
        ok(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "a");
        wt = dir.resolve("wt");
        ok(repo, "worktree", "add", "-q", wt.toString());
        marker = dir.resolve("program-ran");
    }

    /** A copy of the repository's git directory, inside the worktree, whose config runs a program. */
    private Path plantedRepository() throws IOException, InterruptedException {
        Path evil = wt.resolve("evil.git");
        Path source = repo.resolve(".git");
        try (Stream<Path> all = Files.walk(source)) {
            for (Path p : all.sorted(Comparator.naturalOrder()).toList()) {
                Path to = evil.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        fsmonitor(evil.resolve("config"));
        return evil;
    }

    private void fsmonitor(Path config) throws IOException, InterruptedException {
        String command = "echo ran > '" + marker.toString().replace('\\', '/') + "'";
        ok(dir, "config", "--file", config.toString(), "core.fsmonitor", command);
    }

    @Test
    void aCommondirRepointedIntoTheWorktreeIsRefusedAndRunsNothing() throws Exception {
        Path evil = plantedRepository();
        Path own = WorktreeGitDirs.gitDirs(wt)
                .map(g -> g.common().resolve("worktrees").resolve(g.name()))
                .orElseThrow();
        Files.writeString(own.resolve("commondir"), evil.toString().replace('\\', '/') + "\n");

        GitProcess.Result r = status();

        assertThat(marker).as("the planted fsmonitor never ran").doesNotExist();
        assertThat(r.code()).as("git was refused").isNotZero();
    }

    @Test
    void aDotGitFileRepointedIntoTheWorktreeIsRefusedAndRunsNothing() throws Exception {
        Path evil = plantedRepository();
        Files.writeString(wt.resolve(".git"), "gitdir: " + evil.toString().replace('\\', '/') + "\n");

        GitProcess.Result r = status();

        assertThat(marker).as("the planted fsmonitor never ran").doesNotExist();
        assertThat(r.code()).as("git was refused").isNotZero();
    }

    @Test
    void aNestedRepositoryRecordedAsASubmoduleRunsNothing() throws Exception {
        Path sub = Files.createDirectories(wt.resolve("sub"));
        ok(sub, "init", "-q", "-b", "main");
        Files.writeString(sub.resolve("b.txt"), "b\n");
        ok(sub, "add", "b.txt");
        ok(sub, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "b");
        String sha = GitProcess.git(sub, List.of("rev-parse", "HEAD")).out().strip();
        ok(wt, "update-index", "--add", "--cacheinfo", "160000," + sha + ",sub");
        Files.writeString(sub.resolve("b.txt"), "changed\n");
        fsmonitor(sub.resolve(".git").resolve("config"));

        status();

        assertThat(marker).as("the submodule's planted fsmonitor never ran").doesNotExist();
    }

    @Test
    void aGitmodulesThatAsksToEnterANestedRepositoryWithItsOwnProgramsRunsNothing() throws Exception {
        Path sub = Files.createDirectories(wt.resolve("sub2"));
        ok(sub, "init", "-q", "-b", "main");
        Files.writeString(sub.resolve("b.txt"), "b\n");
        ok(sub, "add", "b.txt");
        ok(sub, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "b");
        String sha = GitProcess.git(sub, List.of("rev-parse", "HEAD")).out().strip();
        ok(wt, "update-index", "--add", "--cacheinfo", "160000," + sha + ",sub2");
        Files.writeString(
                wt.resolve(".gitmodules"), "[submodule \"sub2\"]\n\tpath = sub2\n\turl = ./sub2\n\tignore = none\n");
        String run = "echo ran > '" + marker.toString().replace('\\', '/') + "'";
        Path config = sub.resolve(".git").resolve("config");
        // git runs these through a shell; no double quote (Java's Windows argument quoting would split the value).
        ok(dir, "config", "--file", config.toString(), "filter.f.clean", run + "; cat");
        ok(dir, "config", "--file", config.toString(), "diff.external", run);
        ok(dir, "config", "--file", config.toString(), "diff.t.textconv", run + "; cat");
        Files.writeString(sub.resolve(".git").resolve("info").resolve("attributes"), "* filter=f diff=t\n");
        // Same size, newer time: git must read the file through its clean filter to know whether it changed.
        Files.setLastModifiedTime(
                sub.resolve("b.txt"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000));

        for (List<String> args : List.of(
                List.of("status", "--porcelain=v1", "-z", "--untracked-files=all"),
                List.of("diff", "--numstat", "-z", "HEAD"),
                List.of("diff", "HEAD"),
                List.of("add", "--all"),
                List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "c"))) {
            try {
                GitProcess.git(wt, args);
            } catch (IOException e) {
                // Handled: git add is refused here, which is one of the outcomes this test accepts
                assertThat(args).as(e.getMessage()).contains("add");
            }
            assertThat(marker).as("nothing ran for git " + args).doesNotExist();
        }
    }

    @Test
    void aPlainRepositoryWithACommondirIsRefused() throws Exception {
        Files.writeString(repo.resolve(".git").resolve("commondir"), wt.resolve("evil.git") + "\n");

        GitProcess.Result r;
        try {
            r = GitProcess.git(repo, List.of("status", "--porcelain"));
        } catch (IOException e) {
            // Handled: a refusal reads as a failed git
            r = new GitProcess.Result(-1, "", e.getMessage(), false);
        }

        assertThat(r.code()).isNotZero();
        assertThat(r.err()).contains("refused");
    }

    @Test
    void aCommittedLfsConfigurationMakesNoRequestAndBlocksNothing() throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            Files.writeString(repo.resolve(".gitattributes"), "*.bin filter=lfs diff=lfs merge=lfs -text\n");
            Files.writeString(
                    repo.resolve(".lfsconfig"),
                    "[lfs]\n\turl = http://127.0.0.1:" + server.getAddress().getPort() + "/EXFIL\n");
            Files.writeString(
                    repo.resolve("a.bin"),
                    "version https://git-lfs.github.com/spec/v1\noid sha256:" + "0".repeat(64) + "\nsize 12\n");
            ok(repo, "add", ".gitattributes", ".lfsconfig", "a.bin");
            ok(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "lfs");

            ok(repo, "worktree", "add", "-q", "-b", "m2", dir.resolve("wt2").toString());
            Files.delete(dir.resolve("wt2").resolve("a.bin"));
            ok(dir.resolve("wt2"), "reset", "--quiet", "--hard", "HEAD");

            assertThat(requests.get())
                    .as("no request to the URL the worktree named")
                    .isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anUntrackedNestedRepositoryIsNotStagedAsASubmodule() throws Exception {
        Path nest = Files.createDirectories(wt.resolve("nest"));
        ok(nest, "init", "-q", "-b", "main");
        Files.writeString(nest.resolve("n.txt"), "n\n");
        ok(nest, "add", "n.txt");
        ok(nest, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "n");

        try {
            GitProcess.git(wt, List.of("add", "--all"));
        } catch (IOException e) {
            // Handled: refusing is the expected outcome
            assertThat(e.getMessage()).contains("refused");
        }

        assertThat(GitProcess.git(wt, List.of("ls-files", "-z", "--stage")).out())
                .as("no gitlink was staged")
                .doesNotContain("160000 ");
    }

    @Test
    void aPlantedCherryPickDoesNotLendItsAuthorToFloor42sCommit() throws Exception {
        ok(
                repo,
                "-c",
                "user.name=Mallory",
                "-c",
                "user.email=m@example.org",
                "commit",
                "-q",
                "--allow-empty",
                "-m",
                "m");
        String forged = GitProcess.git(repo, List.of("rev-parse", "HEAD")).out().strip();
        Path own = WorktreeGitDirs.gitDirs(wt)
                .map(g -> g.common().resolve("worktrees").resolve(g.name()))
                .orElseThrow();
        Files.writeString(own.resolve("CHERRY_PICK_HEAD"), forged + "\n");
        Files.writeString(wt.resolve("a.txt"), "changed\n");
        ok(wt, "add", "a.txt");

        try {
            GitProcess.git(wt, List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "c"));
        } catch (IOException e) {
            // Handled: refusing is the expected outcome
            assertThat(e.getMessage()).contains("refused");
        }

        assertThat(GitProcess.git(wt, List.of("log", "-1", "--format=%an")).out())
                .as("Floor42's commit never carries the planted author")
                .doesNotContain("Mallory");
    }

    @Test
    void anUntouchedWorktreeStillWorks() throws Exception {
        assertThat(status().code()).isZero();
    }

    private GitProcess.Result status() throws IOException, InterruptedException {
        try {
            return GitProcess.git(wt, List.of("status", "--porcelain=v1", "-z"));
        } catch (IOException e) {
            // Handled: a refusal reads as a failed git
            return new GitProcess.Result(-1, "", e.getMessage(), false);
        }
    }

    private static void ok(Path in, String... args) throws IOException, InterruptedException {
        GitProcess.Result r = GitProcess.git(in, List.of(args));
        assertThat(r.code()).as(r.said()).isZero();
    }
}
