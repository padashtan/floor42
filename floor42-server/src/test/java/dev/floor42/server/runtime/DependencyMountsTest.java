package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirDeletionStrategy;

/**
 * The worktree's node_modules reach a proof copy through an overlay whose writes stay in the sandbox (ADR-0058, amended
 * 2026-11-05): tools that write caches there work, the source is never changed; the run stays offline.
 */
class DependencyMountsTest {

    @Test
    void findsEveryNodeModulesOnceAndNotTheOnesInsideOthers(
            @TempDir(deletionStrategy = TempDirDeletionStrategy.IgnoreFailures.class) Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("node_modules/a/node_modules"));
        Files.createDirectories(tmp.resolve("web/node_modules"));
        Files.createDirectories(tmp.resolve(".git/node_modules"));

        assertThat(DependencyMounts.in(tmp)).containsExactlyInAnyOrder("node_modules", "web/node_modules");
        assertThat(DependencyMounts.with(List.of("x"), tmp, "/mnt/c/w"))
                .hasSize(3)
                .startsWith("x");
        assertThat(DependencyMounts.with(List.of("x"), tmp.resolve("web/node_modules"), "/w"))
                .containsExactly("x");
    }

    @Test
    void aNodeModulesOfTheWorktreeTakesWritesThatNeverReachTheSourceAndTheNetworkStaysCut(
            @TempDir(deletionStrategy = TempDirDeletionStrategy.IgnoreFailures.class) Path tmp) throws Exception {
        Sandbox s = WslSandbox.ready(tmp.resolve("data"));
        Path source = Files.createDirectories(tmp.resolve("source"));
        Files.createDirectories(source.resolve("web/node_modules/dep"));
        Files.writeString(source.resolve("web/node_modules/dep/index.js"), "dep-ok");
        Path copy = Files.createDirectories(tmp.resolve("copy"));
        String command = String.join(
                "\n",
                "cat web/node_modules/dep/index.js; echo",
                // What vite does on every run: a temp config file inside node_modules (M-15, EROFS when read-only).
                "mkdir -p web/node_modules/.vite-temp && echo x > web/node_modules/.vite-temp/c.mjs; echo write=$?",
                "echo y > web/node_modules/dep/index.js; cat web/node_modules/dep/index.js",
                "getent hosts example.com > /dev/null; echo dns=$?");

        ProofSandboxIsolationTest.Ran ran =
                ProofSandboxIsolationTest.run(s.argvOffline(copy, command, 300, source), copy);

        assertThat(ran.output()).contains("dep-ok").contains("write=0");
        assertThat(source.resolve("web/node_modules/.vite-temp")).doesNotExist();
        assertThat(source.resolve("web/node_modules/dep/index.js")).hasContent("dep-ok");
        assertThat(ran.output()).contains("dns=").doesNotContain("dns=0");
    }
}
