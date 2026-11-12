package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

class WslSandboxTest {

    private static Sandbox broken() {
        Sandbox s = new Sandbox() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String probeFailure() {
                return "the script is broken";
            }
        };
        s.distro = "Debian";
        return s;
    }

    @Test
    void aDistributionThatExistsWithABrokenProbeFailsWithTheReason() {
        assertThatThrownBy(() -> WslSandbox.ready(broken(), () -> true))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("the script is broken");
    }

    @Test
    void noDistributionSkips() {
        assertThatThrownBy(() -> WslSandbox.ready(broken(), () -> false)).isInstanceOf(TestAbortedException.class);
        assertThat(true).isTrue();
    }
}
