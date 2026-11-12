package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What Floor42 measures when it runs checks: exit code, duration, the end of the output, and a hard time limit. */
class ChecksTest {

    static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    @TempDir
    Path dir;

    Checks checks(long timeoutSeconds) {
        Checks c = new Checks();
        c.timeoutSeconds = timeoutSeconds;
        return c;
    }

    @Test
    void exitCodeAndOutputAreMeasured() {
        Checks.Outcome o = checks(30).execute("echo checked&& exit 3", dir);

        assertThat(o.exitCode()).isEqualTo(3);
        assertThat(o.timedOut()).isFalse();
        assertThat(o.output()).contains("checked");
        assertThat(o.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void aHangingCommandIsStoppedAtTheLimit() {
        String hang = WINDOWS ? "ping -n 60 127.0.0.1 > NUL" : "sleep 60";
        long start = System.nanoTime();

        Checks.Outcome o = checks(1).execute(hang, dir);

        assertThat(o.timedOut()).isTrue();
        assertThat(o.exitCode()).isNull();
        assertThat(o.output()).contains("Floor42 stopped the checks after 1 s");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(20_000);
    }

    @Test
    void onlyTheEndOfALongOutputIsKept() {
        String loud = WINDOWS
                ? "for /L %i in (1,1,3000) do @echo line %i"
                : "i=1; while [ $i -le 3000 ]; do echo line $i; i=$((i+1)); done";

        Checks.Outcome o = checks(60).execute(loud, dir);

        assertThat(o.output()).startsWith("…").contains("line 3000").doesNotContain("line 1\n");
        assertThat(o.output().length()).isLessThanOrEqualTo(BoundedProcess.MAX_OUTPUT + 2);
    }
}
