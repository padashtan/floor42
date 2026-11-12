package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirDeletionStrategy;

/**
 * A graph node's test runs as a proof step does (ADR-0062, ADR-0058): through the real WSL sandbox, offline, even when
 * the director opened the network for the agents. A test that tries the network therefore fails, and a failure is a
 * rejection of the node. Skipped where the sandbox does not work (no Windows, no WSL distribution).
 */
class NodeTestSandboxTest {

    @Test
    void aNodeTestThatReachesForTheNetworkFailsOffline(
            @TempDir(deletionStrategy = TempDirDeletionStrategy.IgnoreFailures.class) Path tmp) throws Exception {
        Sandbox s = WslSandbox.ready(tmp.resolve("data"));
        Path copy = Files.createDirectories(tmp.resolve("copy"));

        ProofSandboxIsolationTest.Ran dns =
                ProofSandboxIsolationTest.run(s.argvOffline(copy, "getent hosts example.com", 300), copy);
        ProofSandboxIsolationTest.Ran tcp = ProofSandboxIsolationTest.run(
                s.argvOffline(copy, "timeout 10 bash -c 'exec 3<>/dev/tcp/1.1.1.1/53'", 300), copy);
        ProofSandboxIsolationTest.Ran local =
                ProofSandboxIsolationTest.run(s.argvOffline(copy, "echo fine > made.txt && cat made.txt", 300), copy);

        assertThat(dns.exit()).as(dns.output()).isNotZero();
        assertThat(tcp.exit()).as(tcp.output()).isNotZero();
        assertThat(local.exit())
                .as("a test that stays offline passes: " + local.output())
                .isZero();
    }
}
