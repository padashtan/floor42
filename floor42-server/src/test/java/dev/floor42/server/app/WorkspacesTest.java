package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.server.runtime.GitProcess;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Worktrees never take over a branch they did not create. */
class WorkspacesTest {

    static void git(Path dir, String... args) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of("git"));
        cmd.addAll(java.util.List.of(args));
        Process p = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).as(String.join(" ", cmd)).isZero();
    }

    @TempDir
    Path data;

    @Test
    void aBranchLeftByAnotherDataDirectoryIsNeverReused() throws Exception {
        Path repo = Files.createDirectory(data.resolve("repo"));
        Files.writeString(repo.resolve("README.md"), "# Fake project\n");
        git(repo, "init", "-q", "-b", "main");
        git(repo, "add", ".");
        git(repo, "-c", "user.name=test", "-c", "user.email=test@localhost", "commit", "-q", "-m", "init");
        git(repo, "branch", "floor42/m-1-chris");
        Workspaces w = new Workspaces(new dev.floor42.server.runtime.Git(), data);

        Path worktree = w.worktree(repo.toString(), new MissionId("M-1"), new AgentId("chris"));

        assertThat(w.branch(worktree)).isEqualTo("floor42/m-1-chris-2");
        assertThat(w.worktree(repo.toString(), new MissionId("M-1"), new AgentId("chris")))
                .as("the same worktree is reused")
                .isEqualTo(worktree);
    }

    private Path initRepo(String name) throws Exception {
        Path repo = Files.createDirectory(data.resolve(name));
        Files.writeString(repo.resolve("README.md"), "# " + name + "\n");
        git(repo, "init", "-q", "-b", "main");
        git(repo, "add", ".");
        git(repo, "-c", "user.name=test", "-c", "user.email=test@localhost", "commit", "-q", "-m", "init");
        return repo;
    }

    @Test
    void aWorktreeOfAnotherRepositoryIsDisplacedNotAdopted() throws Exception {
        Path repoA = initRepo("repoA");
        Path repoB = initRepo("repoB");
        Workspaces w = new Workspaces(new dev.floor42.server.runtime.Git(), data);
        Path first = w.worktree(repoA.toString(), new MissionId("M-1"), new AgentId("a"));

        Path second = w.worktree(repoB.toString(), new MissionId("M-1"), new AgentId("a"));

        assertThat(second).isEqualTo(first);
        assertThat(Files.readString(second.resolve("README.md"))).contains("repoB");
        assertThat(w.worktrees(new MissionId("M-1")))
                .as("the displaced folder is no agent's")
                .hasSize(1);
        try (var displaced = Files.list(first.getParent().resolve(".displaced"))) {
            assertThat(displaced).hasSize(1);
        }
    }

    @Test
    void aWorktreeOfTheSameRepositoryIsReusedAsItIs() throws Exception {
        Path repo = initRepo("repoA");
        Workspaces w = new Workspaces(new dev.floor42.server.runtime.Git(), data);
        Path first = w.worktree(repo.toString(), new MissionId("M-1"), new AgentId("a"));
        Files.writeString(first.resolve("work.txt"), "x");

        assertThat(w.worktree(repo.toString(), new MissionId("M-1"), new AgentId("a")))
                .isEqualTo(first);
        assertThat(first.resolve("work.txt")).exists();
        assertThat(first.getParent().resolve(".displaced")).doesNotExist();
    }

    @Test
    void aWorktreeTheJournalDoesNotKnowIsDisplacedEvenOfTheSameRepository() throws Exception {
        Path repo = initRepo("repoA");
        Workspaces w = new Workspaces(new dev.floor42.server.runtime.Git(), data);
        MissionId m = new MissionId("M-1");
        Path first = w.worktree(repo.toString(), m, new AgentId("a"));
        Files.writeString(first.resolve("old-work.txt"), "x");

        Path second = w.worktree(repo.toString(), m, new AgentId("a"), p -> false);

        assertThat(second).isEqualTo(first);
        assertThat(second.resolve("old-work.txt")).doesNotExist();
        assertThat(w.branch(second)).isEqualTo("floor42/m-1-a-2");
        try (var displaced = Files.list(first.getParent().resolve(".displaced"))) {
            assertThat(displaced)
                    .singleElement()
                    .satisfies(d -> assertThat(d.resolve("old-work.txt")).exists());
        }
    }

    @Test
    void aRepositoryTellsItsBranchLastCommitAndUncommittedFiles() throws Exception {
        Path repo = Files.createDirectory(data.resolve("project"));
        Files.writeString(repo.resolve("README.md"), "# Project\n");
        git(repo, "init", "-q", "-b", "main");
        git(repo, "add", ".");
        git(repo, "-c", "user.name=test", "-c", "user.email=test@localhost", "commit", "-q", "-m", "first commit");
        Files.writeString(repo.resolve("notes.txt"), "draft\n");
        Workspaces w = new Workspaces(new dev.floor42.server.runtime.Git(), data);

        var facts = w.facts(repo).orElseThrow();

        assertThat(facts.branch()).isEqualTo("main");
        assertThat(facts.subject()).isEqualTo("first commit");
        assertThat(facts.commit()).isNotBlank();
        assertThat(facts.uncommitted()).isEqualTo(1);
        assertThat(w.facts(Files.createDirectory(data.resolve("plain")))).isEmpty();
    }

    @Test
    void pendingPathsAreReadAsTheyAreWhateverTheirName() throws Exception {
        Path repo = Files.createDirectory(data.resolve("names"));
        Files.writeString(repo.resolve("old name.txt"), "a\n");
        git(repo, "init", "-q", "-b", "main");
        git(repo, "add", ".");
        git(repo, "-c", "user.name=test", "-c", "user.email=test@localhost", "commit", "-q", "-m", "init");
        // Names the plain porcelain output quotes or escapes (accents), and a rename, which it writes "a -> b".
        Files.writeString(repo.resolve("résumé.md"), "x");
        Files.writeString(repo.resolve("two words.txt"), "x");
        git(repo, "mv", "old name.txt", "new name.txt");
        Workspaces w = new Workspaces(new dev.floor42.server.runtime.Git(), data);

        assertThat(w.pending(repo))
                .containsExactlyInAnyOrder("résumé.md", "two words.txt", "new name.txt", "old name.txt");
        assertThat(w.facts(repo).orElseThrow().uncommitted()).isEqualTo(3);
    }

    @Test
    void theNulSeparatedStatusKeepsQuotesArrowsAndNewlinesInNames() {
        // What git status --porcelain=v1 -z writes for names Windows cannot even hold.
        String z = "?? say \"hi\".txt\0 M a -> b.txt\0R  new\nline.txt\0old.txt\0";

        assertThat(GitProcess.status(z))
                .containsExactly(
                        new GitProcess.StatusEntry("??", "say \"hi\".txt", null),
                        new GitProcess.StatusEntry(" M", "a -> b.txt", null),
                        new GitProcess.StatusEntry("R ", "new\nline.txt", "old.txt"));
        assertThat(GitProcess.paths("a b\0c\"d\0")).containsExactly("a b", "c\"d");
    }

    @Test
    void onlyAnIndexHeldByAnotherGitIsRunAgain() {
        // As git 2.x on Windows writes them when two git processes touch one worktree at once (D13).
        assertThat(GitProcess.raced(new GitProcess.Result(
                        128, "", "error: index file open failed: Permission denied\nfatal: index file corrupt", false)))
                .isTrue();
        assertThat(GitProcess.raced(new GitProcess.Result(
                        128,
                        "",
                        "fatal: Unable to create 'C:/w/.git/index.lock': File exists.\n\nAnother git process seems to be"
                                + " running in this repository",
                        false)))
                .isTrue();
        assertThat(GitProcess.raced(new GitProcess.Result(1, "", "error: pathspec 'x' did not match any file", false)))
                .isFalse();
        assertThat(GitProcess.raced(
                        new GitProcess.Result(-1, "", "error: index file open failed: Permission denied", true)))
                .as("a git that hung is never run again")
                .isFalse();
        assertThat(GitProcess.raced(new GitProcess.Result(0, "", "", false))).isFalse();
    }

    @Test
    void everyGitSpeaksTheCLocaleWhateverTheMachines() throws Exception {
        // raced() reads git's English messages: a git in French would say "Permission non accordée".
        Path dir = Files.createDirectory(data.resolve("locale"));

        // A shell alias prints the environment git itself was given.
        GitProcess.Result r = GitProcess.git(
                dir,
                java.util.List.of(
                        "-c", "alias.floor42env=!echo \"$LC_ALL:$LANGUAGE:$GIT_TERMINAL_PROMPT\"", "floor42env"));

        assertThat(r.code()).as(r.said()).isZero();
        assertThat(r.out().strip()).isEqualTo("C:C:0");
        java.util.Map<String, String> env = new java.util.HashMap<>();
        GitProcess.environment(env);
        assertThat(env).containsEntry("LC_ALL", "C").containsEntry("LANGUAGE", "C");
    }

    @Test
    void aRevisionIsNeverReadAsAnOption() throws Exception {
        Path repo = Files.createDirectory(data.resolve("options"));
        git(repo, "init", "-q", "-b", "main");
        git(
                repo,
                "-c",
                "user.name=test",
                "-c",
                "user.email=test@localhost",
                "commit",
                "-q",
                "--allow-empty",
                "-m",
                "a");
        Path written = data.resolve("written.txt");
        Workspaces w = new Workspaces(new dev.floor42.server.runtime.Git(), data);

        // Read as an option, "--output=<file>" would make git log write a file of its choosing.
        assertThat(w.log(repo, "--output=" + written, 5)).isEmpty();
        assertThat(w.count(repo, "--all")).isEqualTo(-1);
        assertThat(w.contains(repo, "--help")).isFalse();
        assertThat(w.merge(repo, java.util.List.of("--abort")).clean()).isFalse();
        assertThat(written).doesNotExist();
        assertThat(w.log(repo, "HEAD", 5)).hasSize(1);
        assertThat(w.count(repo, "HEAD")).isEqualTo(1);
    }
}
