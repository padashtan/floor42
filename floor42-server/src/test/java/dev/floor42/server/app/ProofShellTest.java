package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.server.runtime.Sandbox;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A proof step runs in the sandbox, offline, or not at all: never on the host (ADR-0058). */
class ProofShellTest {

    /** A sandbox that does not work here, and remembers every command line asked of it. */
    static final class BrokenSandbox extends Sandbox {
        final List<String> asked = new ArrayList<>();
        long seconds;

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public List<String> argvOffline(Path worktree, String command, long seconds) {
            this.seconds = seconds;
            asked.add(command);
            return List.of("wsl.exe", "off");
        }
    }

    @Test
    void withoutAWorkingSandboxAStepIsRefusedAndNothingStarts() {
        BrokenSandbox sandbox = new BrokenSandbox();
        ProofShell shell = new ProofShell();
        shell.sandbox = sandbox;
        shell.timeoutSeconds = 5;
        assertThat(shell.works()).isFalse();
        assertThatThrownBy(() -> shell.run(Path.of("."), "echo escaped > ../x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never runs on the host");
        assertThat(sandbox.asked).isEmpty();
    }

    @Test
    void theOnlyCommandLineIsTheSandboxesOffline() {
        BrokenSandbox sandbox = new BrokenSandbox();
        ProofShell shell = new ProofShell();
        shell.sandbox = sandbox;
        shell.timeoutSeconds = 7;
        assertThat(shell.argv(Path.of("."), "curl example.com")).containsExactly("wsl.exe", "off");
        assertThat(sandbox.asked).containsExactly("curl example.com");
        assertThat(sandbox.seconds).as("the sandbox wall timer is the step's").isEqualTo(7);
    }
}
