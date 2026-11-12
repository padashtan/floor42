package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** A REAL mission's shell needs the sandbox unless unsafe mode is on (ADR-0063, pays D16). */
class SandboxMandatoryTest {

    private static Sandbox sandbox(boolean settingOn, boolean required, Boolean works) {
        Sandbox s = new Sandbox();
        s.distro = "Debian";
        s.dataDir = Path.of("target", "mandatory-test");
        s.policy = new SandboxPolicy() {
            @Override
            public boolean sandboxEnabled() {
                return settingOn;
            }

            @Override
            public boolean sandboxNetwork() {
                return false;
            }

            @Override
            public boolean sandboxRequired() {
                return required;
            }
        };
        s.setAvailable(works);
        return s;
    }

    private static String refusal(Sandbox s) {
        return new SessionTally(null, Path.of("C:/w"), s).startRefusal();
    }

    @Test
    void withoutAWorkingSandboxARealSessionDoesNotStartAndSaysWhy() {
        assertThat(refusal(sandbox(false, true, false)))
                .startsWith("SANDBOX_REQUIRED")
                .contains("unsafe mode");
    }

    @Test
    void theSettingOffDoesNotLiftTheDutyButUnsafeModeDoes() {
        Sandbox imposed = sandbox(false, true, true);
        assertThat(imposed.enabled()).as("imposed although the setting is off").isTrue();
        assertThat(refusal(imposed)).isNull();
        Sandbox unsafe = sandbox(false, false, false);
        assertThat(unsafe.enabled()).isFalse();
        assertThat(refusal(unsafe))
                .as("unsafe mode: the shell may run as the daemon's user")
                .isNull();
    }
}
