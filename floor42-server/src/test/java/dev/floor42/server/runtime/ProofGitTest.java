package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Git works in the offline copy of a worktree (a node test, a delivery proof): the copy holds no {@code .git}, so the
 * sandbox mounts the source's git directory read-only. Skipped where the WSL sandbox does not work.
 */
class ProofGitTest {

    @Test
    void aCopyOfALinkedWorktreeSeesItsHeadAndCommittedFilesAndCannotWriteGit(@TempDir Path tmp) throws Exception {
        Path repo = repo(tmp);
        Path source = tmp.resolve("linked");
        git(repo, "worktree", "add", "-b", "feature", source.toString());
        check(tmp, source);
    }

    @Test
    void aCopyOfAPlainRepositorySeesItsHeadAndCommittedFilesAndCannotWriteGit(@TempDir Path tmp) throws Exception {
        check(tmp, repo(tmp));
    }

    @Test
    void aNormalSessionInALinkedWorktreeWritesNothingIntoTheSourceGitDirectory(@TempDir Path tmp) throws Exception {
        Sandbox s = WslSandbox.ready(tmp.resolve("data"));
        Path repo = repo(tmp);
        Path linked = tmp.resolve("linked");
        git(repo, "worktree", "add", "-b", "feature", linked.toString());
        Path common = repo.resolve(".git");
        String before = snapshot(common);

        String command = String.join(
                "\n",
                "G=\"$(git rev-parse --git-dir)\"; C=\"$(git rev-parse --git-common-dir)\"",
                "printf '[core]\\n\\thooksPath=/tmp\\n' > \"$G/config.worktree\" 2>/dev/null",
                "echo x > \"$G/hooks-x\" 2>/dev/null; echo x > \"$C/hooks/x\" 2>/dev/null",
                "echo x >> \"$C/config\" 2>/dev/null; echo x > \"$G/ORIG_HEAD\" 2>/dev/null",
                "echo z > added.txt; git add added.txt 2>/dev/null; echo add=$?",
                "git status --porcelain > /dev/null; echo status=$?",
                "echo alive");
        ProofSandboxIsolationTest.Ran ran = ProofSandboxIsolationTest.run(s.argv(linked, command), linked);

        assertThat(ran.output()).contains("alive", "status=0");
        assertThat(snapshot(common)).as("the source git directory, rw mode").isEqualTo(before);
    }

    private static void check(Path tmp, Path source) throws Exception {
        Sandbox s = WslSandbox.ready(tmp.resolve("data"));
        Path copy = Files.createDirectories(tmp.resolve("copy"));
        Files.copy(source.resolve("broker-report.md"), copy.resolve("broker-report.md"));
        String head = git(source, "rev-parse", "HEAD").strip();
        String before = git(source, "status", "--porcelain", "--ignored") + git(source, "log", "--all", "--oneline");
        String commit = git(source, "log", "-1", "--format=%H").strip();
        Path common = Path.of(git(source, "rev-parse", "--path-format=absolute", "--git-common-dir")
                .strip());
        Path own = Path.of(
                git(source, "rev-parse", "--path-format=absolute", "--git-dir").strip());
        String command = String.join(
                "\n",
                "echo head=$(git rev-parse HEAD)",
                "echo log=$(git log -1 --format=%H)",
                "git diff HEAD --stat > /dev/null; echo diff=$?",
                "git ls-files --error-unmatch broker-report.md; echo tracked=$?",
                "echo dirty=$(git status --porcelain | wc -l)",
                "G=\"$(git rev-parse --git-dir)\"; C=\"$(git rev-parse --git-common-dir)\"",
                "git -c user.name=a -c user.email=a@b commit --allow-empty -m x > /dev/null 2>&1; echo commit=$?",
                "echo x > \"$G/planted\" 2>/dev/null; echo write=$?",
                "git update-ref refs/heads/x HEAD 2>/dev/null; echo updateref=$?",
                "git config core.x y 2>/dev/null; echo config=$?",
                "echo x > \"$C/hooks/pre-commit\" 2>/dev/null; echo hook=$?",
                "echo x > \"$C/refs/heads/y\" 2>/dev/null; echo refs=$?",
                "echo z > added.txt; git add added.txt 2>/dev/null; echo add=$?",
                "git update-index --add added.txt 2>/dev/null; echo updateindex=$?",
                "git worktree add /tmp/wt 2>/dev/null; echo worktreeadd=$?",
                "getent hosts example.com > /dev/null; echo dns=$?");
        String gitBefore = snapshot(common) + snapshot(own);

        ProofSandboxIsolationTest.Ran ran =
                ProofSandboxIsolationTest.run(s.argvOffline(copy, command, 300, source), copy);

        assertThat(snapshot(common) + snapshot(own))
                .as("the source git directories")
                .isEqualTo(gitBefore);
        assertThat(ran.output())
                .contains("head=" + head, "log=" + commit, "diff=0", "tracked=0", "dirty=0")
                .doesNotContain("commit=0", "write=0", "updateref=0", "config=0", "hook=0", "refs=0")
                .doesNotContain("add=0", "updateindex=0", "worktreeadd=0", "dns=0");
        assertThat(git(source, "rev-parse", "HEAD").strip()).isEqualTo(head);
        assertThat(git(source, "status", "--porcelain", "--ignored") + git(source, "log", "--all", "--oneline"))
                .isEqualTo(before);
        assertThat(copy.resolve(".git")).doesNotExist();
    }

    /** Every file and folder under {@code dir} with its size and SHA-256, read from the Windows side. */
    static String snapshot(Path dir) throws Exception {
        StringBuilder out = new StringBuilder();
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted().toList()) {
                out.append(dir.relativize(p)).append(' ');
                if (Files.isRegularFile(p)) {
                    out.append(Files.size(p))
                            .append(' ')
                            .append(java.util.HexFormat.of()
                                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(Files.readAllBytes(p))));
                } else out.append("dir");
                out.append('\n');
            }
        }
        return dir + "\n" + out;
    }

    private static Path repo(Path tmp) throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        git(repo, "init", "-q");
        Files.writeString(repo.resolve("broker-report.md"), "sub azp\n");
        git(repo, "add", ".");
        git(repo, "-c", "user.name=a", "-c", "user.email=a@b", "commit", "-q", "-m", "first");
        return repo;
    }

    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IOException("git " + List.of(args) + " failed: " + out);
        return out;
    }
}
