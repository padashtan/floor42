package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The sandbox probe on recorded outputs: a tool is MISSING only when a complete run said so. */
class SandboxProbeTest {

    /** Recorded on Debian 13 in WSL, through the real sandbox script (2026-10-31), trimmed to a few tools. */
    static final String DEBIAN = """
            os|PRETTY_NAME="Debian GNU/Linux 13 (trixie)"
            os|VERSION_ID="13"
            os|ID=debian
            git|/usr/bin/git|git version 2.47.3
            java|/usr/bin/java|openjdk version "25.0.1" 2025-10-21
            mvn|/usr/bin/mvn|Apache Maven 3.9.9
            node||
            docker|/usr/bin/docker|Docker version 26.1.5+dfsg1, build a72d7cd
            floor42-probe-end
            """;

    static final List<String> ASKED = List.of("git", "java", "mvn", "node", "docker");

    @Test
    void aCompleteRunGivesTheDistributionAndEachToolsState() {
        var p = SandboxProbe.read("Debian", ASKED, new SandboxProbe.RunResult(DEBIAN, 0, false));

        assertThat(p.measured()).isTrue();
        assertThat(p.reason()).isNull();
        assertThat(p.osId()).isEqualTo("debian");
        assertThat(p.osVersion()).isEqualTo("13");
        assertThat(p.osName()).isEqualTo("Debian GNU/Linux 13 (trixie)");
        assertThat(p.tools())
                .extracting(SandboxProbe.Tool::name)
                .containsExactly("git", "java", "mvn", "node", "docker");
        assertThat(p.tools().get(1).version()).isEqualTo("openjdk version \"25.0.1\" 2025-10-21");
        assertThat(p.tools().get(3).path()).isEmpty();
    }

    @Test
    void aCutOutputKeepsOnlyCompleteLinesTheRestStaysUnknown() {
        String cut = DEBIAN.substring(0, DEBIAN.indexOf("mvn|") + "mvn|/usr/bi".length());

        var p = SandboxProbe.read("Debian", ASKED, new SandboxProbe.RunResult(cut, null, false));

        assertThat(p.measured()).isTrue();
        assertThat(p.reason()).contains("cut");
        // node was never reached: it is not in the measure, so unknown, never missing.
        assertThat(p.tools()).extracting(SandboxProbe.Tool::name).containsExactly("git", "java");
    }

    @Test
    void aTimeoutMeasuresNothing() {
        var p = SandboxProbe.read("Debian", ASKED, new SandboxProbe.RunResult(DEBIAN, null, true));

        assertThat(p.measured()).isFalse();
        assertThat(p.tools()).isEmpty();
        assertThat(p.reason()).contains("30 s");
    }

    @Test
    void noDistributionMeasuresNothing() {
        // wsl.exe writes its own messages in UTF-16.
        String utf16 = new String(
                "There is no distribution with the supplied name.\r\n".getBytes(StandardCharsets.UTF_16LE),
                StandardCharsets.UTF_8);

        var p = SandboxProbe.read("Debian", ASKED, new SandboxProbe.RunResult(utf16, 1, false));

        assertThat(p.measured()).isFalse();
        assertThat(p.tools()).isEmpty();
        assertThat(p.reason()).contains("no distribution Debian");
    }

    @Test
    void aLineForAToolNotAskedOrWithoutAnAbsolutePathIsNotAMeasure() {
        String out = "evil|/bin/evil|1\nnode|node|v20\nos|ID=debian\noffice-probe-end\n";

        var p = SandboxProbe.read("Debian", ASKED, new SandboxProbe.RunResult(out, 0, false));

        assertThat(p.tools()).isEmpty();
    }

    @Test
    void oneRunThroughTheSandboxAsRealWorkAndOnlyProbing(@TempDir Path data) {
        SandboxProbe probe = probe(data);
        List<List<String>> runs = new ArrayList<>();
        probe.useForTests(
                (argv, limit) -> {
                    runs.add(argv);
                    assertThat(limit).isEqualTo(SandboxProbe.LIMIT);
                    return new SandboxProbe.RunResult(DEBIAN, 0, false);
                },
                () -> "Windows 11");

        var p = probe.probe(List.of("node", "not a tool; rm -rf /"));

        assertThat(runs).hasSize(1);
        List<String> argv = runs.getFirst();
        assertThat(argv.subList(0, 4)).containsExactly("wsl.exe", "-d", "Debian", "--exec");
        assertThat(argv.get(5)).endsWith("/sandbox/run.sh");
        // A scratch folder of Floor42's, never a project.
        assertThat(argv.get(6)).endsWith("/sandbox/probe");
        String script = new String(Base64.getDecoder().decode(argv.get(7)), StandardCharsets.UTF_8);
        assertThat(script).contains("command -v", "--version", "-version 2>&1", "/etc/os-release");
        assertThat(script).doesNotContain("rm -rf", "apt", "sudo");
        assertThat(p.measured()).isTrue();
    }

    @Test
    void offWindowsOrWithoutWslNothingRunsAndNothingIsMeasured(@TempDir Path data) {
        SandboxProbe probe = probe(data);
        List<List<String>> runs = new ArrayList<>();
        probe.useForTests(
                (argv, limit) -> {
                    runs.add(argv);
                    return new SandboxProbe.RunResult("", 0, false);
                },
                () -> "Mac OS X");
        assertThat(probe.probe(List.of()).measured()).isFalse();
        assertThat(runs).isEmpty();

        probe.useForTests(
                (argv, limit) -> {
                    throw new java.io.IOException("Cannot run program \"wsl.exe\"");
                },
                () -> "Windows 11");
        var p = probe.probe(List.of());
        assertThat(p.measured()).isFalse();
        assertThat(p.reason()).contains("wsl.exe could not start");
    }

    @Test
    void recipesForDebianAndUbuntuOnlyNodeNewerThanTheDistributionsFromTheTarball() {
        var java = InstallRecipes.recipe("java", "21", "debian", null, "13");
        assertThat(java.packages()).containsExactly("openjdk-21-jdk-headless");

        var node = InstallRecipes.recipe("node", ">=22", "debian", null, "13");
        assertThat(node.packages()).contains("curl", "xz-utils");
        assertThat(String.join("\n", node.script()))
                .contains("https://nodejs.org/dist/latest-v22.x", "sha256sum -c", "-C /usr/local")
                .doesNotContain("nvm");
        assertThat(InstallRecipes.recipe("node", "18", "ubuntu", null, "24.04").packages())
                .containsExactly("nodejs", "npm");
        assertThat(InstallRecipes.recipe("mvn", null, "linuxmint", "ubuntu debian", "22")
                        .packages())
                .containsExactly("maven");

        assertThat(InstallRecipes.recipe("mvn", null, "fedora", null, "40").manual())
                .isTrue();
        assertThat(InstallRecipes.recipe("docker", null, "debian", null, "13").manual())
                .isTrue();
        assertThat(InstallRecipes.recipe("zig", null, "debian", null, "13").manual())
                .isTrue();

        assertThat(InstallRecipes.commands(List.of(java, InstallRecipes.recipe("git", null, "debian", null, "13"))))
                .containsExactly("sudo apt-get update && sudo apt-get install -y openjdk-21-jdk-headless git");
    }

    static SandboxProbe probe(Path data) {
        Sandbox sandbox = new Sandbox();
        sandbox.policy = new SandboxPolicy() {
            @Override
            public boolean sandboxEnabled() {
                return true;
            }

            @Override
            public boolean sandboxNetwork() {
                return false;
            }
        };
        sandbox.distro = "Debian";
        sandbox.osName = "Windows 11"; // the argv asserted here is the Windows one
        sandbox.dataDir = data;
        SandboxProbe probe = new SandboxProbe();
        probe.sandbox = sandbox;
        probe.distro = "Debian";
        probe.dataDir = data;
        return probe;
    }
}
