package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.runtime.MachineChecklist.ChecklistRow;
import dev.floor42.server.runtime.MachineChecklist.ChecklistState;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What is missing on this machine: required rows decide the exit code, sandbox tools are optional. */
class MachineChecklistTest {

    static final ClaudeCodeProbe.Availability CLAUDE = new ClaudeCodeProbe.Availability(true, "2.1.300", null);

    static Map<String, Integer> distros(String name, int version) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put(name, version);
        return m;
    }

    static ChecklistRow row(MachineChecklist.Checklist c, String id) {
        return c.rows().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void everythingRequiredPresentIsReadyEvenWithoutABrowser() {
        var c = MachineChecklist.of(
                true,
                CLAUDE,
                "git version 2.51.0.windows.1",
                new HostChecks.Wsl(true, distros("Debian", 2)),
                "Debian",
                SandboxToolkitTest.measured(
                        "git|/usr/bin/git|git version 2.47.3",
                        "chromium||",
                        "chromium-browser||",
                        "google-chrome||",
                        "google-chrome-stable||"));

        assertThat(c.ready()).isTrue();
        assertThat(c.rows())
                .extracting(ChecklistRow::id)
                .startsWith("claude", "git", "wsl", "distro", "isolation", "git", "python3");
        ChecklistRow chromium = row(c, "chromium");
        assertThat(chromium.state()).isEqualTo(ChecklistState.OPTIONAL);
        assertThat(chromium.required()).isFalse();
        assertThat(chromium.command()).isEqualTo(WslSandbox.installHint("chromium"));
        assertThat(row(c, "pandoc").state()).as("not measured: unknown").isEqualTo(ChecklistState.UNKNOWN);
        assertThat(MachineChecklist.text(c)).contains("Every required item is present.");
    }

    @Test
    void noWslOrADistributionOnWsl1IsMissingWithTheCommandToCopy() {
        var none = MachineChecklist.of(
                true, CLAUDE, "git version 2.51", new HostChecks.Wsl(false, Map.of()), "Debian", null);
        assertThat(none.ready()).isFalse();
        assertThat(row(none, "wsl").command()).isEqualTo("wsl --install --no-distribution");
        assertThat(row(none, "distro").state()).isEqualTo(ChecklistState.UNKNOWN);

        var wsl1 = MachineChecklist.of(
                true, CLAUDE, "git version 2.51", new HostChecks.Wsl(true, distros("Debian", 1)), "Debian", null);
        assertThat(row(wsl1, "distro").note()).isEqualTo("wsl1");
        assertThat(row(wsl1, "distro").command()).isEqualTo("wsl --set-version Debian 2");

        var other = MachineChecklist.of(
                true, CLAUDE, "git version 2.51", new HostChecks.Wsl(true, distros("Ubuntu", 2)), "Debian", null);
        assertThat(row(other, "distro").command()).isEqualTo("wsl --install -d Debian");
        assertThat(MachineChecklist.text(other))
                .contains("[MISSING]")
                .contains("to install, copy: wsl --install -d Debian");
    }

    @Test
    void anOldClaudeCodeIsMissingAndUpdated() {
        var c = MachineChecklist.of(
                false,
                new ClaudeCodeProbe.Availability(false, "2.1.100", "Claude Code 2.1.100 is older than 2.1.295"),
                "git version 2.47",
                new HostChecks.Wsl(false, Map.of()),
                "Debian",
                null);
        assertThat(row(c, "claude").state()).isEqualTo(ChecklistState.MISSING);
        assertThat(row(c, "claude").command()).isEqualTo("claude update");
        assertThat(row(c, "wsl").state()).as("only on Windows").isEqualTo(ChecklistState.OPTIONAL);
        assertThat(c.ready()).isFalse();
    }

    @Test
    void claudeNotFoundIsACodeNeverTheServersEnglish() {
        var c = MachineChecklist.of(
                true,
                new ClaudeCodeProbe.Availability(false, null, "claude not found: Cannot run program \"claude\""),
                "git version 2.51",
                new HostChecks.Wsl(true, distros("Debian", 2)),
                "Debian",
                null);
        ChecklistRow claude = row(c, "claude");
        assertThat(claude.note()).isEqualTo("notFound");
        assertThat(claude.detail()).isNull();
        assertThat(claude.command()).isEqualTo("irm https://claude.ai/install.ps1 | iex");
    }

    @Test
    void wslListsItsDistributionsInUtf16WithAHeaderInTheSystemsLanguage() {
        String utf16 = new String(
                "  NOM       ÉTAT       VERSION\r\n* Debian    Running    2\r\n  Ubuntu    Stopped    1\r\n"
                        .getBytes(StandardCharsets.UTF_16LE),
                StandardCharsets.UTF_8);
        // As SandboxProbe.start reads it: the bytes decoded as UTF-8. Only the ASCII names and digits survive intact.
        Map<String, Integer> d = HostChecks.distros(utf16);
        assertThat(d).containsEntry("Debian", 2).containsEntry("Ubuntu", 1).hasSize(2);
        assertThat(HostChecks.distros(null)).isEmpty();
        assertThat(List.copyOf(d.keySet())).doesNotContain("NOM");
    }
}
