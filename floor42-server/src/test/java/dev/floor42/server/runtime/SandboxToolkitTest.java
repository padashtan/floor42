package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.SandboxTools;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The essential tools: one name for several commands, a state only a complete measure gives, a command to copy. */
class SandboxToolkitTest {

    static SandboxTools measured(String... lines) {
        return new SandboxTools(
                "Debian",
                "debian",
                null,
                "13",
                "Debian GNU/Linux 13 (trixie)",
                true,
                java.util.Arrays.stream(lines)
                        .map(l -> SandboxTools.Tool.parse(l).orElseThrow())
                        .toList(),
                null,
                Instant.parse("2026-11-02T10:00:00Z"));
    }

    @Test
    void theInstallCommandForThisMachineIsSudoOnLinuxAndWslOnWindows() {
        String here = SandboxToolkit.installCommandHere("Debian", List.of("pandoc"), "debian", null)
                .orElseThrow();
        if (SandboxHost.linux(SandboxHost.current())) {
            assertThat(here)
                    .startsWith("sudo sh -c \"apt-get update && apt-get install -y ")
                    .doesNotContain("wsl");
        } else {
            assertThat(here).startsWith("wsl -d Debian -u root -- sh -c ");
        }
    }

    @Test
    void aPlannersWordsBecomeTheEssentialsName() {
        assertThat(SandboxToolkit.normalize("headless Chrome")).isEqualTo("chromium");
        assertThat(SandboxToolkit.normalize("**google-chrome**")).isEqualTo("chromium");
        assertThat(SandboxToolkit.normalize("a browser")).isEqualTo("chromium");
        assertThat(SandboxToolkit.normalize("pdftotext (poppler)")).isEqualTo("pdftotext");
        assertThat(SandboxToolkit.normalize("soffice")).isEqualTo("libreoffice");
        assertThat(SandboxToolkit.normalize("Python")).isEqualTo("python3");
        assertThat(SandboxToolkit.normalize("ffmpeg")).isEqualTo("ffmpeg");
        assertThat(SandboxToolkit.normalize("none")).isNull();
        assertThat(SandboxToolkit.normalize("run the tests with care")).isNull();
    }

    @Test
    void anyOfItsCommandsMakesAToolPresentAndAllOfThemMissingMakeItAbsent() {
        SandboxTools viaChrome = measured("chromium||", "chromium-browser||", "google-chrome|/usr/bin/google-chrome|x");
        assertThat(SandboxToolkit.state(viaChrome, "chromium")).isEqualTo(SandboxToolkit.State.PRESENT);

        SandboxTools none = measured(
                "chromium||", "chromium-browser||", "google-chrome||", "google-chrome-stable||", "pdftotext||");
        assertThat(SandboxToolkit.state(none, "chromium")).isEqualTo(SandboxToolkit.State.ABSENT);
        assertThat(SandboxToolkit.state(none, "pdftotext")).isEqualTo(SandboxToolkit.State.ABSENT);
    }

    @Test
    void whatWasNotAskedOrNotMeasuredIsUnknownNeverAbsent() {
        // google-chrome-stable was not in this measure: the browser may be there under that name.
        SandboxTools partial = measured("chromium||", "chromium-browser||", "google-chrome||");
        assertThat(SandboxToolkit.state(partial, "chromium")).isEqualTo(SandboxToolkit.State.UNKNOWN);
        assertThat(SandboxToolkit.state(null, "git")).isEqualTo(SandboxToolkit.State.UNKNOWN);
        SandboxTools failed = new SandboxTools("Debian", null, null, null, null, false, List.of(), "timeout", null);
        assertThat(SandboxToolkit.state(failed, "git")).isEqualTo(SandboxToolkit.State.UNKNOWN);
    }

    @Test
    void theCommandToCopyInstallsAsRootInTheDistributionAndFloor42NeverRunsIt() {
        assertThat(SandboxToolkit.installCommand("Debian", List.of("chromium", "pdftotext"), "debian", null))
                .contains("wsl -d Debian -u root -- sh -c \"apt-get update && apt-get install -y chromium"
                        + " poppler-utils\"");
        assertThat(SandboxToolkit.installCommand("Debian", List.of("ffmpeg"), "debian", null))
                .as("no package known: nothing invented")
                .isEmpty();
        assertThat(SandboxToolkit.installCommand("Fedora", List.of("chromium"), "fedora", null))
                .isEmpty();
        assertThat(SandboxToolkit.installCommand("x & calc", List.of("git"), null, null))
                .as("a distribution name that is no plain word is not written into a command")
                .contains("wsl -d Debian -u root -- sh -c \"apt-get update && apt-get install -y git\"");
    }

    @Test
    void theProbeAsksEveryCommandOfEveryEssential() {
        assertThat(SandboxProbe.BASELINE)
                .contains(
                        "chromium",
                        "chromium-browser",
                        "google-chrome",
                        "pdftotext",
                        "pandoc",
                        "libreoffice",
                        "soffice")
                .doesNotHaveDuplicates();
        assertThat(SandboxProbe.script(SandboxProbe.BASELINE)).contains("pdftotext) v=$(\"$p\" -v");
    }

    @Test
    void theProbeCommandsAreEveryEssentialsCommandsOnceInTheirOrder() {
        assertThat(SandboxToolkit.commands())
                .containsExactly(
                        "git",
                        "python3",
                        "node",
                        "java",
                        "make",
                        "chromium",
                        "chromium-browser",
                        "google-chrome",
                        "google-chrome-stable",
                        "pdftotext",
                        "pandoc",
                        "libreoffice",
                        "soffice");
    }

    @Test
    void anAliasAndACommandFindTheSameEssentialAndAnyOtherToolHasNone() {
        assertThat(SandboxToolkit.essential("nodejs").orElseThrow().name()).isEqualTo("node");
        assertThat(SandboxToolkit.essential("google-chrome-stable")
                        .orElseThrow()
                        .packages())
                .containsExactly("chromium");
        assertThat(SandboxToolkit.essential("ffmpeg")).isEmpty();
        assertThat(SandboxToolkit.essential("none")).isEmpty();
        assertThat(SandboxToolkit.commandsOf("soffice")).containsExactly("libreoffice", "soffice");
        assertThat(SandboxToolkit.commandsOf("ffmpeg")).containsExactly("ffmpeg");
    }

    @Test
    void normalizeKeepsOrdinaryNamesAndRefusesEverythingElse() {
        assertThat(SandboxToolkit.normalize(null)).isNull();
        assertThat(SandboxToolkit.normalize("  Make.  ")).isEqualTo("make");
        assertThat(SandboxToolkit.normalize("`ripgrep`")).isEqualTo("ripgrep");
        assertThat(SandboxToolkit.normalize("an ffmpeg")).isNull();
        assertThat(SandboxToolkit.normalize("n/a")).isNull();
        assertThat(SandboxToolkit.normalize("")).isNull();
        assertThat(SandboxToolkit.normalize("a".repeat(40))).isEqualTo("a".repeat(40));
        assertThat(SandboxToolkit.normalize("a".repeat(41))).isNull();
        assertThat(SandboxToolkit.normalize("-x")).isNull();
        assertThat(SandboxToolkit.normalize("g++")).isEqualTo("g++");
    }

    @Test
    void theVersionComesFromTheFirstCommandFound() {
        SandboxTools t = measured(
                "chromium||", "chromium-browser|/usr/bin/chromium-browser|Chromium 140", "google-chrome|/x|Chrome 1");
        assertThat(SandboxToolkit.version(t, "chromium")).isEqualTo("Chromium 140");
        assertThat(SandboxToolkit.version(t, "browser")).isEqualTo("Chromium 140");
        assertThat(SandboxToolkit.version(t, "pandoc")).isNull();
        assertThat(SandboxToolkit.version(null, "chromium")).isNull();
        assertThat(SandboxToolkit.version(
                        new SandboxTools("Debian", null, null, null, null, false, List.of(), "timeout", null), "git"))
                .isNull();
    }

    @Test
    void theToolsAreListedEssentialsFirstThenTheOthersWithoutRepeats() {
        assertThat(SandboxToolkit.ordered(List.of("zip", "pandoc", "git", "zip", "ffmpeg")))
                .containsExactly("git", "pandoc", "zip", "ffmpeg");
        assertThat(SandboxToolkit.ordered(List.of())).isEmpty();
    }

    @Test
    void theDistributionNameIsKeptWhenItIsAPlainWordAndAnUbuntuLikeSystemIsSupported() {
        assertThat(SandboxToolkit.installCommand("Ubuntu-24.04", List.of("git"), "ubuntu", null))
                .hasValueSatisfying(c -> assertThat(c).startsWith("wsl -d Ubuntu-24.04 -u root -- sh -c "));
        assertThat(SandboxToolkit.installCommand(null, List.of("git"), null, "ubuntu debian"))
                .hasValueSatisfying(c -> assertThat(c).startsWith("wsl -d Debian -u root"));
        assertThat(SandboxToolkit.installCommand("Debian", List.of("node", "make", "node"), "debian", null))
                .hasValueSatisfying(c -> assertThat(c).endsWith("install -y nodejs npm make\""));
        assertThat(SandboxToolkit.installCommand("Debian", List.of(), "debian", null))
                .isEmpty();
    }

    @Test
    void whenNoCommandIsMeasuredTheStateIsUnknownAndAMissingOneKeepsItUnknown() {
        SandboxTools t = measured("git|/usr/bin/git|git 2", "make||");
        assertThat(SandboxToolkit.state(t, "git")).isEqualTo(SandboxToolkit.State.PRESENT);
        assertThat(SandboxToolkit.state(t, "make")).isEqualTo(SandboxToolkit.State.ABSENT);
        assertThat(SandboxToolkit.state(t, "node")).isEqualTo(SandboxToolkit.State.UNKNOWN);
    }
}
