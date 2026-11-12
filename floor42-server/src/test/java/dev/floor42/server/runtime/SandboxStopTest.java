package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirDeletionStrategy;

/**
 * Stopping a session kills the Windows side (bash.exe, wsl.exe); the Linux tree of the sandboxed command must end with
 * it, not run on to the sandbox's wall time and write into the worktree. Skipped only where WSL is absent.
 */
class SandboxStopTest {

    private static int linuxProcesses() throws Exception {
        Process p = new ProcessBuilder(WslSandbox.host("pgrep", "-fc", "[s]leep 11.77"))
                .redirectErrorStream(true)
                .start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        p.waitFor(30, TimeUnit.SECONDS);
        return out.isEmpty()
                ? 0
                : Integer.parseInt(out.lines().reduce((a, b) -> b).orElse("0").strip());
    }

    @Test
    void stoppingTheWindowsSideEndsTheLinuxTreeAndTheMarkerIsNeverWritten(
            @TempDir(deletionStrategy = TempDirDeletionStrategy.IgnoreFailures.class) Path tmp) throws Exception {
        Sandbox s = WslSandbox.ready(tmp.resolve("data"));
        Path work = Files.createDirectories(tmp.resolve("work"));
        List<String> argv = s.argv(work, "sleep 11.77; touch marker", 120);
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
        p.getOutputStream().close();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (linuxProcesses() == 0 && System.nanoTime() < until) Thread.sleep(300);
        assertThat(linuxProcesses()).as("the command runs").isPositive();

        SessionProcesses.stopTree(p);
        Thread.sleep(4_000);

        assertThat(linuxProcesses())
                .as("the Linux tree is gone a few seconds after the stop")
                .isZero();
        Thread.sleep(10_000);
        assertThat(work.resolve("marker"))
                .as("the stopped command wrote nothing")
                .doesNotExist();
    }
}
