package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** wsl.exe writes UTF-16LE unless WSL_UTF8=1: the probe sets it so a failure reads as text. */
class SandboxProbeUtf8Test {

    @Test
    void theProbeRunsWithWslUtf8SetToOne() throws Exception {
        boolean windows = SandboxHost.windows(SandboxHost.current());
        List<String> echo =
                windows ? List.of("cmd.exe", "/c", "echo [%WSL_UTF8%]") : List.of("sh", "-c", "echo [$WSL_UTF8]");
        var r = SandboxProbe.start(echo, Duration.ofSeconds(30));
        assertThat(r.output()).contains("[1]");
    }
}
