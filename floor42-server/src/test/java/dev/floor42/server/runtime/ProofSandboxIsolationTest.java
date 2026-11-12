package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirDeletionStrategy;

/**
 * A delivery's proof runs commands an agent wrote (ADR-0058): through the real WSL sandbox, they change nothing outside
 * the worktree and reach no network, even when the director opened the network for the agents. Skipped where the
 * sandbox does not work (no Windows, no WSL distribution).
 */
class ProofSandboxIsolationTest {

    /** The director opened the network for the agents: the proof must still have none. */
    static final SandboxPolicy NETWORK_ON = new SandboxPolicy() {
        @Override
        public boolean sandboxEnabled() {
            return true;
        }

        @Override
        public boolean sandboxNetwork() {
            return true;
        }
    };

    record Ran(int exit, String output) {}

    static Ran run(List<String> argv, Path dir) throws Exception {
        Process p = new ProcessBuilder(argv)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return new Ran(p.exitValue(), out);
    }

    Sandbox sandbox(Path data) {
        Sandbox s = new Sandbox();
        s.policy = NETWORK_ON;
        s.distro = "Debian";
        s.dataDir = data;
        s.assumed = Optional.empty();
        return s;
    }

    @Test
    void aProofStepWritesOnlyInItsWorktreeAndReachesNoNetwork(
            @TempDir(deletionStrategy = TempDirDeletionStrategy.IgnoreFailures.class) Path tmp) throws Exception {
        Sandbox s = WslSandbox.ready(tmp.resolve("data"));
        Path work = Files.createDirectories(tmp.resolve("work"));
        Path windowsFile = tmp.resolve("outside.txt");
        String command = String.join(
                "\n",
                "echo leak > " + Sandbox.linux(windowsFile) + " 2>/dev/null; echo windows=$?",
                "echo leak > ../beside.txt; echo beside=$?",
                "echo leak > /etc/floor42-proof-leak 2>/dev/null; echo etc=$?",
                "getent hosts example.com > /dev/null; echo dns=$?",
                "echo inside > inside.txt");

        Ran ran = run(s.argvOffline(work, command, 300), work);

        assertThat(Files.exists(windowsFile)).as(ran.output()).isFalse();
        assertThat(Files.exists(tmp.resolve("beside.txt"))).as(ran.output()).isFalse();
        assertThat(ran.output()).contains("windows=").doesNotContain("windows=0");
        assertThat(ran.output()).contains("etc=").doesNotContain("etc=0");
        assertThat(ran.output()).contains("dns=").doesNotContain("dns=0");
        assertThat(Files.readString(work.resolve("inside.txt")).strip()).isEqualTo("inside");
        Ran etc = run(WslSandbox.host("test", "-e", "/etc/floor42-proof-leak"), work);
        assertThat(etc.exit())
                .as("nothing was written in the distribution's /etc")
                .isNotZero();
    }

    @Test
    void aProofStepMountsNoGitFolderWhateverTheFolderSays(
            @TempDir(deletionStrategy = TempDirDeletionStrategy.IgnoreFailures.class) Path tmp) throws Exception {
        Sandbox s = sandbox(tmp.resolve("data"));
        Path copy = Files.createDirectories(tmp.resolve("copy"));
        // A step wrote this between two steps: it must not decide what the next step sees.
        Files.writeString(copy.resolve(".git"), "gitdir: C:/Users/someone/.ssh/worktrees/x");
        List<String> argv = s.argvOffline(copy, "ls", 300);
        int net = argv.indexOf("off");
        assertThat(argv.subList(net, net + 4)).containsExactly("off", "none", "none", "none");
        // Then the sandbox's limits, as for every command (run.sh $7-$9: wall time, processes, file size), and the
        // work mounted writable ($10): a proof step writes in its own copy; only a reviewer's shell is "ro".
        assertThat(argv.subList(net + 4, argv.size()))
                .containsExactly("300", Long.toString(s.maxProcesses), Long.toString(s.maxFileBytes), "rw");
        assertThat(String.join(" ", argv)).doesNotContain(".ssh");
    }

    @Test
    void theAgentsShellKeepsTheNetworkTheDirectorOpened() {
        Sandbox s = sandbox(Path.of(System.getProperty("java.io.tmpdir"), "floor42-proof-argv"));
        Path work = Path.of("C:/w/M-1-chris");
        assertThat(s.argv(work, "ls")).contains("on");
        assertThat(s.argvOffline(work, "ls", 300))
                .doesNotContain("on")
                .contains("off")
                .startsWith(SandboxHost.linux(s.osName) ? "sh" : "wsl.exe");
    }
}
