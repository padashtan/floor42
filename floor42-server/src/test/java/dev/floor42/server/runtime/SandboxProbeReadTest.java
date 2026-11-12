package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.runtime.SandboxProbe.RunResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The probe's reading of an output, its list of names and its process seam, one rule at a time. */
class SandboxProbeReadTest {

    private static SandboxProbe.Probe read(List<String> asked, String out) {
        return SandboxProbe.read("Debian", asked, new RunResult(out, 0, false));
    }

    @Test
    void theNamesAreTheBaselineThenOnlyPlainCommandNamesOnceEach() {
        assertThat(SandboxProbe.names(null)).isEqualTo(SandboxProbe.BASELINE);
        List<String> wanted = new ArrayList<>(
                List.of("zig", "zig", "Zig", "-x", "a b", "x".repeat(41), "y".repeat(40), "git", "g++", "a.b_c-d"));
        wanted.add(null);
        List<String> names = SandboxProbe.names(wanted);

        assertThat(names.subList(0, SandboxProbe.BASELINE.size())).isEqualTo(SandboxProbe.BASELINE);
        assertThat(names.subList(SandboxProbe.BASELINE.size(), names.size()))
                .containsExactly("zig", "y".repeat(40), "g++", "a.b_c-d");
    }

    @Test
    void anOsReleaseFieldNeedsAKeyAndIsUnquotedWithoutItsSeparators() {
        var p = read(
                List.of("git"),
                String.join(
                        "\n",
                        "os|=nokey",
                        "os|A=b",
                        "os|ID=\"deb|ian\"",
                        "os|ID_LIKE='ubuntu'",
                        "os|VERSION_ID=\"",
                        "os|PRETTY_NAME=\"x'",
                        "os|NAME=\"\"",
                        "floor42-probe-end",
                        ""));

        assertThat(p.osId()).isEqualTo("debian");
        assertThat(p.osLike()).isEqualTo("ubuntu");
        assertThat(p.osVersion()).isEqualTo("\"");
        assertThat(p.osName()).isEqualTo("\"x'");
    }

    @Test
    void aVersionIsClippedAt200CharactersAndAMissingToolHasNone() {
        var p = read(
                List.of("git", "node", "java"),
                "git|/usr/bin/git|" + "v".repeat(200) + "\nnode|/usr/bin/node|" + "v".repeat(201)
                        + "\njava||ignored\nfloor42-probe-end\n");

        assertThat(p.tools())
                .extracting(SandboxProbe.Tool::version)
                .containsExactly("v".repeat(200), "v".repeat(200), "");
        assertThat(p.tools().getLast().path()).isEmpty();
        assertThat(p.tools().getFirst().line()).isEqualTo("git|/usr/bin/git|" + "v".repeat(200));
    }

    @Test
    void theFirstLineOfAToolWinsAndTheOrderIsTheOneAsked() {
        var p = read(
                List.of("node", "git"),
                "git|/usr/bin/git|first\ngit|/usr/local/bin/git|second\nnode|/usr/bin/node|n\nfloor42-probe-end\n");

        assertThat(p.tools()).extracting(SandboxProbe.Tool::name).containsExactly("node", "git");
        assertThat(p.tools().getLast().version()).isEqualTo("first");
    }

    @Test
    void aLineWithoutThreeFieldsOrAnAbsolutePathIsNotAMeasure() {
        var p = read(List.of("git", "node"), "git|/usr/bin/git\nnode|bin/node|v\nos|ID=debian\nfloor42-probe-end\n");

        assertThat(p.tools()).isEmpty();
        assertThat(p.measured()).isTrue();
    }

    @Test
    void windowsLineEndingsAndNulBytesFromWslAreReadAsLines() {
        var p = read(List.of("git"), "o\u0000s|ID=debian\r\ngit|/usr/bin/git|g 2\r\nfloor42-probe-end\r\n");

        assertThat(p.osId()).isEqualTo("debian");
        assertThat(p.tools())
                .singleElement()
                .extracting(SandboxProbe.Tool::version)
                .isEqualTo("g 2");
        assertThat(p.reason()).as("the end marker was there").isNull();
    }

    @Test
    void aLastLineWithoutItsBreakIsNotAFactAndAnEndMarkerAloneIsComplete() {
        var cut = read(List.of("git", "node"), "git|/usr/bin/git|g\nnode|/usr/bin/node|n");
        assertThat(cut.tools()).extracting(SandboxProbe.Tool::name).containsExactly("git");
        assertThat(cut.reason()).contains("cut");

        var done = read(List.of("git"), "floor42-probe-end\ngit|/usr/bin/git|g\n");
        assertThat(done.reason()).isNull();
    }

    @Test
    void silenceIsQuotedWithItsExitCodeAndClippedAt300Characters() {
        var none = SandboxProbe.read("Debian", List.of("git"), new RunResult(null, null, false));
        assertThat(none.measured()).isFalse();
        assertThat(none.reason()).isEqualTo("The sandbox did not answer");

        var exit = SandboxProbe.read("Debian", List.of("git"), new RunResult("boom\n", 2, false));
        assertThat(exit.reason()).isEqualTo("The sandbox did not answer (exit 2): boom");

        var long300 = SandboxProbe.read("Debian", List.of("git"), new RunResult("q".repeat(300), 1, false));
        assertThat(long300.reason()).endsWith(": " + "q".repeat(300));
        var long301 = SandboxProbe.read("Debian", List.of("git"), new RunResult("q".repeat(301), 1, false));
        assertThat(long301.reason()).endsWith(": " + "q".repeat(300) + "…");

        var emptyWithExit = SandboxProbe.read("Debian", List.of("git"), new RunResult("  ", 3, false));
        assertThat(emptyWithExit.reason()).isEqualTo("The sandbox did not answer (exit 3)");
    }

    @Test
    void aTimedOutRunNamesTheLimit() {
        var p = SandboxProbe.read("Debian", List.of("git"), new RunResult("git|/x|1\n", null, true));
        assertThat(p.reason()).isEqualTo("The probe did not finish within 30 s");
        assertThat(p.distro()).isEqualTo("Debian");
        assertThat(p.tools()).isEmpty();
    }

    @Test
    void aRealProcessIsReadToTheEndAndItsExitCodeKept() throws Exception {
        RunResult r = SandboxProbe.start(echo("hello"), Duration.ofSeconds(20));

        assertThat(r.timedOut()).isFalse();
        assertThat(r.exitCode()).isZero();
        assertThat(r.output()).contains("hello");
    }

    @Test
    void aProcessPastTheLimitIsStoppedAndSaidToHaveTimedOut() throws Exception {
        long begin = System.nanoTime();
        RunResult r = SandboxProbe.start(sleep(), Duration.ofMillis(500));

        assertThat(r.timedOut()).isTrue();
        assertThat(r.exitCode()).isNull();
        assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void anInterruptedWaitIsKeptForTheCallerAndStopsTheProcess() throws Exception {
        Thread.currentThread().interrupt();
        try {
            RunResult r = SandboxProbe.start(sleep(), Duration.ofSeconds(20));

            assertThat(r.timedOut()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static List<String> echo(String word) {
        return SandboxHost.windows(SandboxHost.current())
                ? List.of("cmd", "/c", "echo " + word)
                : List.of("sh", "-c", "echo " + word);
    }

    private static List<String> sleep() {
        return SandboxHost.windows(SandboxHost.current())
                ? List.of("ping", "-n", "30", "127.0.0.1")
                : List.of("sh", "-c", "sleep 30");
    }
}
