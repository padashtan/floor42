package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the sandbox passes of the repository's git settings: read on the host through Floor42's one git runner, only the
 * allow-listed keys, never a remote, a credential, a URL rewrite, an include or a program.
 */
class SandboxGitConfigTest {

    @TempDir
    Path dir;

    static final String TOKEN = "ghp_floor42faketoken0123456789";

    /** A repository whose config holds everything the sandbox must not show, and the settings git needs. */
    static Path repoWithSecrets(Path dir) throws Exception {
        Path repo = Files.createDirectory(dir.resolve("secrets"));
        assertThat(GitProcess.git(repo, List.of("init", "-q", "-b", "main")).code())
                .isZero();
        List<List<String>> settings = List.of(
                List.of("remote.origin.url", "https://x:" + TOKEN + "@example.invalid/r.git"),
                List.of("credential.helper", "store --file=/tmp/" + TOKEN),
                List.of("http.extraHeader", "Authorization: Bearer " + TOKEN),
                List.of("url.https://" + TOKEN + "@example.invalid/.insteadOf", "https://example.invalid/"),
                List.of("include.path", "/tmp/" + TOKEN),
                List.of("core.sshCommand", "ssh -i " + TOKEN),
                List.of("core.askPass", TOKEN),
                List.of("core.filemode", "false"),
                List.of("core.autocrlf", "Input"));
        for (List<String> kv : settings) {
            assertThat(GitProcess.git(repo, List.of("config", kv.get(0), kv.get(1)))
                            .code())
                    .as(kv.getFirst())
                    .isZero();
        }
        return repo;
    }

    @Test
    void onlyTheAllowListedKeysArePassedAndNoSecretOfTheConfig() throws Exception {
        Path repo = repoWithSecrets(dir);

        List<String> keys = WorktreeGitDirs.gitConfig(repo);

        assertThat(keys).contains("core.filemode=false", "core.autocrlf=input", "core.bare=false");
        assertThat(keys)
                .allMatch(l -> l.matches(
                        "core\\.(filemode|autocrlf|ignorecase|symlinks|repositoryformatversion|bare)=[A-Za-z0-9._-]+"
                                + "|extensions\\.[a-z0-9]+=[A-Za-z0-9._-]+"));
        assertThat(String.join("\n", keys))
                .doesNotContain(TOKEN, "remote.", "credential.", "http.", "url.", "include.");

        String passed = new String(Base64.getDecoder().decode(Sandbox.config(repo)), StandardCharsets.UTF_8);
        assertThat(passed).as("the argument run.sh gets").isEqualTo(String.join("\n", keys) + "\n");
    }

    @Test
    void theRepositoryFormatIsPassedAndAValueGitWouldNotAcceptIsDropped() throws Exception {
        Path repo = Files.createDirectory(dir.resolve("format"));
        assertThat(GitProcess.git(repo, List.of("init", "-q", "-b", "main")).code())
                .isZero();
        GitProcess.git(repo, List.of("config", "extensions.worktreeConfig", "true"));
        GitProcess.git(repo, List.of("config", "core.autocrlf", "sometimes"));
        GitProcess.git(repo, List.of("config", "core.symlinks", "a b"));

        List<String> keys = WorktreeGitDirs.gitConfig(repo);

        assertThat(keys)
                .contains("extensions.worktreeconfig=true")
                .anyMatch(l -> l.startsWith("core.repositoryformatversion="));
        assertThat(keys).noneMatch(l -> l.startsWith("core.autocrlf=") || l.startsWith("core.symlinks="));
    }
}
