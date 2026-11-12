package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SandboxTest {

    @Test
    void theAssumeAvailableOverrideIsRefusedOutsideTheTestLaunchMode() {
        var set = java.util.Optional.of(true);
        for (var mode : new io.quarkus.runtime.LaunchMode[] {
            io.quarkus.runtime.LaunchMode.NORMAL, io.quarkus.runtime.LaunchMode.DEVELOPMENT
        })
            assertThatThrownBy(() -> SandboxStartCheck.refuseAssumedOutsideTests(set, mode))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("floor42.sandbox.assume-available");
        SandboxStartCheck.refuseAssumedOutsideTests(set, io.quarkus.runtime.LaunchMode.TEST);
        SandboxStartCheck.refuseAssumedOutsideTests(java.util.Optional.empty(), io.quarkus.runtime.LaunchMode.NORMAL);
    }

    @Test
    void sessionTokenIsStrippedFromEverySandboxedCommand() {
        String jwt = "eyJhbGciOiJSUzI1NiJ9.e30.c2ln";
        var env = new java.util.HashMap<>(java.util.Map.of(
                "FLOOR42_HOOK_TOKEN", jwt, "floor42_hook_token", jwt, "Floor42_Other", "x", "PATH", "/bin"));
        SandboxEnv.scrub(env);
        assertThat(env).doesNotContainKeys("FLOOR42_HOOK_TOKEN", "floor42_hook_token", "Floor42_Other");
        assertThat(env).containsKey("PATH");
        assertThat(Sandbox.RUN)
                .as("the sandbox keeps its own allow-list inside")
                .contains("\"LANG\", \"LC_ALL\", \"TERM\"");
    }

    @Test
    void noFloor42VariableReachesTheSandboxedCommandEvenWhenTheHostSetsOne(
            @org.junit.jupiter.api.io.TempDir(
                            deletionStrategy = org.junit.jupiter.api.io.TempDirDeletionStrategy.IgnoreFailures.class)
                    Path tmp)
            throws Exception {
        Sandbox s = WslSandbox.ready(tmp.resolve("data"));
        Path work = java.nio.file.Files.createDirectories(tmp.resolve("work"));
        var builder = new ProcessBuilder(s.argvOffline(work, "env", 300))
                .directory(work.toFile())
                .redirectErrorStream(true);
        builder.environment().put("FLOOR42_HOOK_TOKEN", "eyJhbGciOiJSUzI1NiJ9.e30.c2ln");
        builder.environment().put("WSLENV", "FLOOR42_HOOK_TOKEN/u");
        Process p = builder.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(out).as("env printed: " + out).doesNotContain("FLOOR42_").doesNotContain("eyJ");
    }

    @Test
    void windowsPathsBecomeWslPaths() {
        assertThat(Sandbox.linux(Path.of("C:/Users/alice/.floor42/workspaces/M-1-chris")))
                .isEqualTo("/mnt/c/Users/alice/.floor42/workspaces/M-1-chris");
    }

    @Test
    void theCommandTravelsEncodedSoNoShellReinterpretsIt() {
        String command = "echo \"$HOME\" 'a b' && rm -rf ../x; `id`";
        String encoded = Sandbox.b64(command);
        assertThat(encoded).matches("[A-Za-z0-9+/=]+");
        assertThat(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8))
                .isEqualTo(command);
    }

    @Test
    void theScriptHidesTheMachineAndCutsTheNetworkByDefault() {
        assertThat(Sandbox.RUN)
                .contains("unshare -Urm -C -i -u $NET")
                .contains("NET=-n")
                .contains("for d in /mnt /run /home /root /tmp")
                .contains("unset WSL_INTEROP")
                .contains("mount --bind \"$1\" /media/work");
    }

    @Test
    void theCommandRunsOneUserNamespaceDeeperSoTheMasksAreLockedAndItIsBounded() {
        assertThat(Sandbox.RUN)
                .as("a mask that fails to mount stops the sandbox instead of leaving the drive visible")
                .doesNotContain("tmpfs \"$d\" 2>/dev/null")
                .contains("mount -t binfmt_misc binfmt_misc /proc/sys/fs/binfmt_misc")
                .contains("exec unshare -Urm --propagation private setpriv --no-new-privs")
                .contains("prlimit --nproc=\"$7\" --fsize=\"$8\"")
                .contains("python3 -I /media/.floor42-init \"$6\"")
                .contains("lowerdir=/media/.wtlow,upperdir=/media/.ovg/u")
                .contains("ro /media/work/.git");
    }

    @Test
    void wslExeStartsWithNoSecretAndForwardsNothing() {
        var env = new java.util.HashMap<>(java.util.Map.of(
                "ANTHROPIC_API_KEY",
                "x",
                "CLAUDE_CODE_X",
                "x",
                "FLOOR42_TOKEN",
                "x",
                "OFFICE_TOKEN",
                "x",
                "AI_AGENT",
                "x",
                "WSLENV",
                "ANTHROPIC_API_KEY/u",
                "PATH",
                "C:/bin",
                "SystemRoot",
                "C:/Windows"));

        SandboxEnv.scrub(env);

        assertThat(env).containsOnlyKeys("PATH", "SystemRoot");
        Sandbox s = new Sandbox();
        s.distro = "Debian";
        s.osName = "Windows 11";
        assertThat(s.shellPrefix()).isEqualTo("WSLENV= MSYS_NO_PATHCONV=1 wsl.exe -d Debian --exec ");
        s.osName = "Linux";
        assertThat(s.shellPrefix()).isEmpty();
    }

    @Test
    void theScrubIgnoresCase() {
        var env = new java.util.HashMap<>(java.util.Map.of(
                "anthropic_api_key", "x", "Claude_Code", "x", "floor42_token", "x", "WslEnv", "x", "Path", "C:/bin"));
        SandboxEnv.scrub(env);
        assertThat(env).containsOnlyKeys("Path");
    }

    @Test
    void everyArgumentOfTheShellLineIsQuotedSoGitBashReadsItAsOneWord(@org.junit.jupiter.api.io.TempDir Path data) {
        Sandbox s = new Sandbox();
        s.distro = "Debian";
        s.dataDir = data;
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
        for (String name : java.util.List.of("R&D", "a;b", "$(id)", "`id`", "a#b", "~x")) {
            String line = s.wrapForShell(Path.of("C:/w/" + name), "echo hi", false);
            assertThat(line).as(name).contains("'/mnt/c/w/" + name + "'");
            assertThat(line.substring(s.shellPrefix().length()))
                    .as("every word after the prefix is quoted: " + line)
                    .matches("sh( '[^']*')+");
        }
        assertThat(s.wrapForShell(Path.of("C:/w/it's"), "echo hi", false)).contains("'/mnt/c/w/it'\\''s'");
    }

    @Test
    void aLinkedWorktreeNamesItsRepositorysGitDirectory(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve(".git"), "gitdir: C:/repo/.git/worktrees/M-2-chris");
        var g = WorktreeGitDirs.gitDirs(dir).orElseThrow();
        assertThat(g.name()).isEqualTo("M-2-chris");
        assertThat(Sandbox.linux(g.common())).isEqualTo("/mnt/c/repo/.git");
    }

    @Test
    void aPlainFolderHasNoGitDirectoryToMount(@org.junit.jupiter.api.io.TempDir Path dir) {
        assertThat(WorktreeGitDirs.gitDirs(dir)).isEmpty();
    }

    @Test
    void aPathWithASpaceOrAnApostropheIsAcceptedAndQuotedForTheShell() {
        assertThat(Sandbox.linux(Path.of("C:/Users/Jean Dupont/O'Neil/x")))
                .isEqualTo("/mnt/c/Users/Jean Dupont/O'Neil/x");
        assertThatThrownBy(() -> Sandbox.linux(Path.of("C:/Users/ab"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWorktreeWithASpaceAndAnApostropheReachesGitBashEscaped(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        Path work = java.nio.file.Files.createDirectories(tmp.resolve("Jean Dupont's work"));
        Sandbox s = new Sandbox();
        s.policy = ProofSandboxIsolationTest.NETWORK_ON;
        s.distro = "Debian";
        s.dataDir = tmp.resolve("data");

        String line = s.wrapForShell(work, "true", false);

        assertThat(line).contains("Jean Dupont'\\''s work'");
    }

    @Test
    void aWorktreeWithASpaceAndAnApostropheRunsInTheRealSandbox(
            @org.junit.jupiter.api.io.TempDir(
                            deletionStrategy = org.junit.jupiter.api.io.TempDirDeletionStrategy.IgnoreFailures.class)
                    Path tmp)
            throws Exception {
        Sandbox s = WslSandbox.ready(tmp.resolve("data"));
        Path work = java.nio.file.Files.createDirectories(tmp.resolve("Jean Dupont's work"));
        Process p = new ProcessBuilder(s.argvOffline(work, "pwd", 120))
                .redirectErrorStream(true)
                .start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(out).as(out).contains("/media/work");
        assertThat(p.exitValue()).as(out).isZero();
    }
}
