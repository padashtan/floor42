package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.runtime.DockerAccess.InSandbox;
import dev.floor42.server.runtime.DockerAccess.Measure;
import dev.floor42.server.runtime.MachineChecklist.ChecklistRow;
import dev.floor42.server.runtime.MachineChecklist.ChecklistState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Docker in the sandbox is measured by asking its daemon, not by finding the client: a client that is present does not
 * make Docker usable there (the sandbox hides /run and drops the docker group). The measure stays in memory, never
 * among the journaled tools. The checklist says so, with the director's step, the time measured and no install command.
 */
class DockerAccessTest {

    static final Instant AT = Instant.parse("2026-11-03T10:00:00Z");

    static final String HEAD = """
            os|PRETTY_NAME="Debian GNU/Linux 13 (trixie)"
            os|ID=debian
            git|/usr/bin/git|git version 2.47.3
            """;

    static final String UNREACHABLE = HEAD + """
            docker|/usr/bin/docker|Docker version 27.5.1, build 9f9e405
            docker-daemon||
            floor42-probe-end
            """;

    static Measure measured(String out) {
        var r = new SandboxProbe.RunResult(out, 0, false);
        return DockerAccess.measure(SandboxProbe.read("Debian", List.of("git", "docker"), r), r, AT);
    }

    @Test
    void theProbeAsksTheDaemonInTheSameRunAndNeverJournalsIt(@TempDir Path data) {
        String script = SandboxProbe.script(SandboxProbe.names(List.of()));
        assertThat(script).contains("timeout 5", "version --format '{{.Server.Version}}'", "docker-daemon");
        assertThat(script.indexOf("docker-daemon")).isLessThan(script.indexOf(SandboxProbe.END));

        SandboxProbe probe = SandboxProbeTest.probe(data);
        assertThat(probe.docker().state()).isEqualTo(InSandbox.UNKNOWN);
        assertThat(probe.docker().at()).isNull();
        probe.useForTests((argv, limit) -> new SandboxProbe.RunResult(UNREACHABLE, 0, false), () -> "Windows 11");

        var p = probe.probe(List.of());

        assertThat(p.tools()).extracting(SandboxProbe.Tool::name).doesNotContain("docker-daemon");
        assertThat(probe.docker().state()).isEqualTo(InSandbox.DAEMON_UNREACHABLE_IN_SANDBOX);
        assertThat(probe.docker().at()).isNotNull();

        probe.useForTests(
                (argv, limit) -> {
                    throw new java.io.IOException("Cannot run program \"wsl.exe\"");
                },
                () -> "Windows 11");
        probe.probe(List.of());
        assertThat(probe.docker().state()).isEqualTo(InSandbox.UNKNOWN);
        assertThat(probe.docker().reason()).contains("wsl.exe");
    }

    @Test
    void aPresentClientWhoseDaemonDoesNotAnswerIsUnreachableNotAvailable() {
        Measure m = measured(UNREACHABLE);

        assertThat(m.state()).isEqualTo(InSandbox.DAEMON_UNREACHABLE_IN_SANDBOX);
        assertThat(DockerAccess.note(m)).isEqualTo("docker.unreachable");
        ChecklistRow row = DockerAccess.row(true, m);
        assertThat(row.state()).isEqualTo(ChecklistState.OPTIONAL);
        assertThat(row.note()).isEqualTo("dockerUnreachable");
        assertThat(row.measuredAt()).isEqualTo(AT.toString());
        assertThat(row.command()).as("installing does not help").isNull();
        assertThat(row.required()).isFalse();
        assertThat(MachineChecklist.text(new MachineChecklist.Checklist(true, "Debian", null, true, List.of(row))))
                .contains("docker (in the sandbox)", "cannot reach the Docker daemon", "installing will not help");
    }

    @Test
    void eachStateFromTheMeasure() {
        assertThat(measured(HEAD + "docker||\nfloor42-probe-end\n").state()).isEqualTo(InSandbox.CLIENT_MISSING);
        // Cut before the daemon's line: nothing is said of the daemon.
        assertThat(measured(HEAD + "docker|/usr/bin/docker|Docker version 27\n").state())
                .isEqualTo(InSandbox.UNKNOWN);
        Measure reachable = measured(HEAD + """
                docker|/usr/bin/docker|Docker version 27.5.1
                docker-daemon|/usr/bin/docker|27.5.1
                floor42-probe-end
                """);
        assertThat(reachable.state()).isEqualTo(InSandbox.DAEMON_REACHABLE);
        assertThat(reachable.version()).isEqualTo("27.5.1");
        assertThat(DockerAccess.note(reachable)).isEqualTo("docker.reachable");
        assertThat(DockerAccess.row(true, reachable).note()).isEqualTo("dockerReachable");
        assertThat(DockerAccess.row(true, reachable).state()).isEqualTo(ChecklistState.PRESENT);
        assertThat(DockerAccess.row(false, reachable).note()).isEqualTo("notWindows");
        Measure timedOut = DockerAccess.measure(
                SandboxProbe.read("Debian", List.of("docker"), new SandboxProbe.RunResult("", null, true)),
                new SandboxProbe.RunResult("", null, true),
                AT);
        assertThat(timedOut.state()).isEqualTo(InSandbox.UNKNOWN);
        assertThat(timedOut.reason()).contains("did not finish");
    }

    @Test
    void theMachineChecklistHasADockerRowNotMeasuredSinceTheStart() {
        var c = MachineChecklist.of(
                true,
                new ClaudeCodeProbe.Availability(true, "2.1.300", null),
                "git version 2.51",
                new HostChecks.Wsl(true, Map.of("Debian", 2)),
                "Debian",
                null);

        assertThat(c.rows())
                .filteredOn(r -> r.id().equals("docker"))
                .singleElement()
                .satisfies(r -> assertThat(r.note()).isEqualTo("dockerNotMeasured"));
    }
}
