package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Without the shell sandbox an agent can write the repository's configuration: a git that talks to a remote refuses
 * what would send a push elsewhere or run a program for it. The director's own settings are left alone.
 */
class NetworkConfigGuardTest {

    @TempDir
    Path tmp;

    private static void run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).as(String.join(" ", cmd)).isZero();
    }

    private Path repository() throws Exception {
        Path repo = Files.createTempDirectory(tmp, "repo");
        run(repo, "git", "init", "-q", "-b", "main");
        return repo;
    }

    @Test
    void hostileSettingsInTheRepositoryRefuseEveryCommandThatOpensAConnection() throws Exception {
        String[][] hostile = {
            {"core.sshCommand", "calc"},
            {"core.gitProxy", "calc"},
            {"remote.origin.pushurl", "https://evil.example/x.git"},
            {"remote.origin.receivepack", "calc"},
            {"remote.origin.uploadpack", "calc"},
            {"remote.origin.vcs", "evil"},
            {"url.https://evil.example/.insteadOf", "https://github.com/"},
            {"credential.helper", "!calc"},
            {"include.path", "other"},
            {"http.proxy", "http://evil.example:3128"},
            // '=' inside a quoted subsection name: the key is not cut at it
            {"url.https://evil.example/x?a=b.insteadOf", "https://github.com/"},
            {"remote.o=x.pushurl", "https://evil.example/x.git"},
            {"http.https://github.com/?x=y.proxy", "http://evil.example:3128"},
            {"http.curloptResolve", "github.com:443:127.0.0.1"},
            {"http.https://github.com/.curloptResolve", "github.com:443:127.0.0.1"},
            {"http.sslVerify", "false"},
            {"http.sslCAPath", "/tmp/ca"},
            {"http.sslCAInfo", "/tmp/ca.pem"},
            {"http.sslCert", "/tmp/c.pem"},
            {"http.sslKey", "/tmp/k.pem"},
            {"http.sslBackend", "openssl"},
            {"http.extraHeader", "Authorization: x"},
            {"http.https://github.com/.extraHeader", "Authorization: x"},
            {"core.hooksPath", "/tmp/hooks"},
            {"gpg.program", "calc"},
            {"gpg.ssh.program", "calc"},
            {"gpg.ssh.defaultKeyCommand", "calc"},
            {"remote.https://github.com/acme/app.git.url", "/tmp/evil.git"},
            {"protocol.allow", "always"},
            {"protocol.ext.allow", "always"},
            {"CORE.SSHCOMMAND", "calc"},
            {"Http.ExtraHeader", "Authorization: x"},
        };
        for (String[] kv : hostile) {
            Path repo = repository();
            run(repo, "git", "config", "--local", kv[0], kv[1]);
            for (String command : List.of("push", "ls-remote", "fetch")) {
                assertThatThrownBy(() -> GitProcess.git(repo, List.of(command, "--", "https://127.0.0.1:1/x.git")))
                        .as(kv[0] + " " + command)
                        .hasMessageContaining("the repository sets");
            }
        }
    }

    @Test
    void aPlainRepositoryStillRunsTheCommandAndOtherCommandsAreNotChecked() throws Exception {
        Path repo = repository();
        GitProcess.Result r = GitProcess.git(repo, List.of("ls-remote", "--", "https://127.0.0.1:1/x.git"));
        assertThat(r.code()).isNotZero();
        run(repo, "git", "config", "--local", "core.sshCommand", "calc");
        assertThat(GitProcess.git(repo, List.of("status", "--short")).code()).isZero();
    }

    @Test
    void aRemoteNamedLikeTheTargetUrlCannotSendThePushToAnotherRepository() throws Exception {
        Path evil = Files.createTempDirectory(tmp, "evil");
        run(evil, "git", "init", "-q", "--bare");
        Path target = Files.createTempDirectory(tmp, "target");
        run(target, "git", "init", "-q", "--bare");
        Path repo = repository();
        run(repo, "git", "-c", "user.name=a", "-c", "user.email=a@b.c", "commit", "-q", "--allow-empty", "-m", "x");
        String url = target.toString();
        run(repo, "git", "config", "--local", "remote." + url + ".url", evil.toString());
        assertThatThrownBy(() -> GitProcess.git(
                        repo, List.of("push", "--porcelain", "--", url, "refs/heads/main:refs/heads/main")))
                .hasMessageContaining("the repository sets remote.");
        // Production reads the remote by its name (PullRequestGit.pushedAt): origin, with a program set under it.
        Path named = repository();
        run(named, "git", "remote", "add", "origin", url);
        run(named, "git", "config", "--local", "remote.origin.uploadpack", "calc");
        assertThatThrownBy(() -> GitProcess.git(named, List.of("ls-remote", "--", "origin")))
                .hasMessageContaining("the repository sets remote.origin.uploadpack");
        assertThat(GitProcess.git(evil, List.of("for-each-ref")).out()).isBlank();
        assertThat(GitProcess.git(target, List.of("for-each-ref")).out()).isBlank();
    }

    @Test
    void aSettingWithNoValueAndAConfigGitCannotReadAreRefused() throws Exception {
        Path repo = repository();
        Path config = repo.resolve(".git/config");
        Files.writeString(config, "[core]\n\tsshCommand\n", java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> GitProcess.git(repo, List.of("ls-remote", "--", "https://127.0.0.1:1/x.git")))
                .hasMessageContaining("the repository sets core.sshcommand");
        Path broken = repository();
        Files.writeString(broken.resolve(".git/config"), "[core\n\tbroken\n", java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> GitProcess.git(broken, List.of("ls-remote", "--", "https://127.0.0.1:1/x.git")))
                .hasMessageContaining("git could not list its settings");
    }

    @Test
    void aBenignRepositoryConfigStaysAllowed() throws Exception {
        Path repo = repository();
        run(repo, "git", "config", "--local", "core.autocrlf", "input");
        run(repo, "git", "config", "--local", "user.name", "Someone");
        run(repo, "git", "config", "--local", "remote.origin.url", "https://github.com/o/r.git");
        run(repo, "git", "config", "--local", "remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*");
        run(repo, "git", "config", "--local", "branch.main.remote", "origin");
        run(repo, "git", "config", "--local", "remote.o=x.url", "https://github.com/o/r.git");
        assertThat(NetworkConfigGuard.risky(
                        GitProcess.git(repo, NetworkConfigGuard.LIST).out()))
                .isEmpty();
    }

    @Test
    void onlyTheRepositoryAndTheWorktreeCountNotTheDirectorsOwnScopes() {
        String list = "global\0core.sshcommand\nssh -i key\0system\0credential.helper\nmanager\0"
                + "local\0core.bare\nfalse\0worktree\0remote.origin.pushurl\nx\0";
        assertThat(NetworkConfigGuard.risky(list)).contains("remote.origin.pushurl");
        assertThat(NetworkConfigGuard.risky("global\0core.sshcommand\nx\0system\0credential.helper\nm\0"))
                .isEmpty();
        assertThat(NetworkConfigGuard.risky("local\0remote.origin.url\nhttps://github.com/o/r\0"))
                .isEmpty();
    }
}
