package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.runtime.LaunchMode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** How the sandbox is started on each system, and the two small guards around it. */
class SandboxHostTest {

    @Test
    void linuxRunsTheScriptAsItIsAndWindowsGoesThroughWslExe() {
        List<String> tail = List.of("sh", "run.sh", "x");
        assertThat(SandboxHost.argv("Linux", "Debian", tail)).isEqualTo(tail);
        assertThat(SandboxHost.argv("Windows 11", "Ubuntu", tail))
                .containsExactly("wsl.exe", "-d", "Ubuntu", "--exec", "sh", "run.sh", "x");
        assertThat(SandboxHost.argv("Mac OS X", "Debian", tail).getFirst()).isEqualTo("wsl.exe");
    }

    @Test
    void theSandboxPolicyIsNotRequiredUnlessItSaysSo() {
        SandboxPolicy policy = new SandboxPolicy() {
            @Override
            public boolean sandboxEnabled() {
                return true;
            }

            @Override
            public boolean sandboxNetwork() {
                return false;
            }
        };
        assertThat(policy.sandboxRequired()).isFalse();
    }

    @Test
    void anAssumedSandboxOutsideTheTestsRefusesTheStart() {
        assertThatThrownBy(() -> SandboxStartCheck.refuseAssumedOutsideTests(Optional.of(true), LaunchMode.NORMAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("for tests only")
                .hasMessageContaining("NORMAL");
        SandboxStartCheck.refuseAssumedOutsideTests(Optional.of(true), LaunchMode.TEST);
        SandboxStartCheck.refuseAssumedOutsideTests(Optional.empty(), LaunchMode.NORMAL);

        SandboxStartCheck check = new SandboxStartCheck();
        check.assumed = Optional.empty();
        check.start(null);
    }
}
