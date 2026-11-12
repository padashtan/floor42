package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.runtime.Git;
import dev.floor42.server.runtime.GitProcess;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A name read from a repository's configuration or from a marketplace listing is an argument of git's or claude's
 * command line: never one that reads as an option (no {@code --receive-pack=...}).
 */
class ArgumentInjectionTest {

    /** Records every git command line and answers with the given output, exit 0. */
    static final class RecordingGit extends Git {
        final List<List<String>> calls = new ArrayList<>();
        final String answer;

        RecordingGit(String answer) {
            this.answer = answer;
        }

        @Override
        public GitProcess.Result run(Path dir, List<String> args) {
            calls.add(args);
            return new GitProcess.Result(0, answer, "", false);
        }
    }

    @Test
    void aRemoteNamedLikeAnOptionIsNoRemote() {
        Path project = Path.of(".");
        assertThat(new PullRequestGit(new RecordingGit("--receive-pack=evil\n")).remoteFor(project, "main"))
                .isNull();
        assertThat(new PullRequestGit(new RecordingGit("origin\n")).remoteFor(project, "main"))
                .isEqualTo("origin");
        RecordingGit only = new RecordingGit("--upload-pack=x");
        assertThat(new PullRequestGit(only).remoteFor(project, "")).isNull();
    }

    @Test
    void thePushAndTheRemoteReadEndTheirOptionsBeforeTheRemote() {
        RecordingGit git = new RecordingGit("https://github.com/o/r.git\n");
        PullRequestGit pr = new PullRequestGit(git);
        pr.push(Path.of("."), "origin", "https://github.com/o/r.git", "b");
        pr.pushedAt(Path.of("."), "--upload-pack=evil", "b", "abc");
        // The push goes to the URL of the preview, not to the remote's name (a pushurl under that name is no part of
        // it).
        assertThat(git.calls.stream()
                        .filter(c -> c.contains("push"))
                        .findFirst()
                        .orElseThrow())
                .containsSequence(
                        "push", "--porcelain", "--", "https://github.com/o/r.git", "refs/heads/b:refs/heads/b")
                .doesNotContain("origin");
        assertThat(git.calls.getLast()).containsSequence("ls-remote", "--", "--upload-pack=evil");
    }

    @Test
    void aRemoteWithNoUrlIsNotPushed() {
        RecordingGit git = new RecordingGit("");
        assertThat(new PullRequestGit(git)
                        .push(Path.of("."), "origin", "https://github.com/o/r.git", "b")
                        .code())
                .isNotZero();
        assertThat(git.calls).noneMatch(c -> c.contains("push"));
    }

    @Test
    void aRepositoryHandedToGhIsAHostOwnerAndNameNeverAnOption() {
        for (String url : new String[] {
            "git@--jq=x:o/r.git",
            "https://--jq=x/o/r",
            "git@github.com:-x/r.git",
            "git@github.com:o/-r.git",
            "https://github.com/o/r name",
            "git@gi thub.com:o/r"
        }) {
            assertThat(PullRequestGit.repositoryOf(url)).as(url).isEmpty();
        }
        assertThat(PullRequestGit.repositoryOf("git@GitHub.com:o/r.git")).contains("github.com/o/r");
        assertThat(PullRequestGit.repositoryOf("ssh://git@git.example.org:2222/o/r.name_1"))
                .contains("git.example.org/o/r.name_1");
    }

    @Test
    void aMarketplaceOrPluginNameNeverStartsWithADash() {
        for (String bad : new String[] {"-x", "--scope=user", "-", "", "a b", "a".repeat(101)}) {
            assertThat(MarketplaceStore.NAME.matcher(bad).matches()).as(bad).isFalse();
        }
        assertThat(MarketplaceStore.NAME.matcher("superpowers-dev").matches()).isTrue();
        assertThat(MarketplaceStore.NAME.matcher("a.b_c-d").matches()).isTrue();
        assertThat(MarketplaceStore.REPO.matcher("-x/y").matches()).isFalse();
        assertThat(MarketplaceStore.REPO.matcher("o/.github").matches()).isTrue();
    }

    @Test
    void aMarketplaceFolderNamedLikeAnOptionIsNotHandedToClaude() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertThat(MarketplaceStore.addArgument(json.readTree("{\"source\":\"directory\",\"path\":\"--help\"}")))
                .isNull();
        assertThat(MarketplaceStore.addArgument(json.readTree("{\"source\":\"file\",\"path\":\"/m/x.json\"}")))
                .isEqualTo("/m/x.json");
        assertThat(MarketplaceStore.addArgument(json.readTree("{\"source\":\"github\",\"repo\":\"-x/y\"}")))
                .isNull();
    }
}
