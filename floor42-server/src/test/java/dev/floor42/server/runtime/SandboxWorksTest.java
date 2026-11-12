package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The sandbox probe is bounded: a run that does not answer in time is cut and read as "not working". */
class SandboxWorksTest {

    private static List<String> java(String... args) {
        String exe = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(exe), java.util.stream.Stream.of(args))
                .toList();
    }

    @Test
    void aRunThatAnswersTheWordInTimeWorks() throws Exception {
        var r = SandboxProbe.start(
                java("-Dx=floor42-sandbox-ok", "-XshowSettings:properties", "-version"), Duration.ofSeconds(60));
        assertThat(Sandbox.works(r)).isTrue();
    }

    @Test
    void aRunWithoutTheWordDoesNot() throws Exception {
        assertThat(Sandbox.works(SandboxProbe.start(java("-version"), Duration.ofSeconds(60))))
                .isFalse();
    }

    @Test
    void aRunThatDoesNotFinishIsCutAtTheLimitAndDoesNotWork() throws Exception {
        long start = System.nanoTime();
        var r = SandboxProbe.start(java("-XshowSettings:all", "-version"), Duration.ZERO);
        assertThat(r.timedOut()).isTrue();
        assertThat(Sandbox.works(r)).isFalse();
        assertThat(System.nanoTime() - start).isLessThan(20_000_000_000L);
    }
}
