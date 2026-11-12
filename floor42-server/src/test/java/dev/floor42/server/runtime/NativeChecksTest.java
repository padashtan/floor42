package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.runtime.MachineChecklist.Checklist;
import dev.floor42.server.runtime.MachineChecklist.ChecklistRow;
import dev.floor42.server.runtime.MachineChecklist.ChecklistState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeChecksTest {

    private static Checklist base(ChecklistState isolation) {
        return new Checklist(
                false,
                "Debian",
                null,
                false,
                List.of(
                        new ChecklistRow("git", false, ChecklistState.PRESENT, true, "2.47", null, null, null),
                        new ChecklistRow("isolation", false, isolation, true, null, "probeFailed", "x", null)));
    }

    @Test
    void anAppArmorRestrictionIsReadAndTheRemedyIsNamedNeverApplied(@TempDir Path sys) throws Exception {
        Files.createDirectories(sys.resolve("kernel"));
        Files.writeString(sys.resolve("kernel/apparmor_restrict_unprivileged_userns"), "1\n");

        var f = NativeChecks.measure(true, "", sys);
        Checklist c = NativeChecks.apply(base(ChecklistState.MISSING), f);

        assertThat(f.usernsRestriction()).contains("apparmor_restrict_unprivileged_userns");
        ChecklistRow isolation = c.rows().stream()
                .filter(r -> r.id().equals("isolation"))
                .findFirst()
                .orElseThrow();
        assertThat(isolation.command())
                .startsWith("echo 'profile floor42-unshare /usr/bin/unshare flags=(unconfined) { userns, }'")
                .contains(
                        "sudo tee /etc/apparmor.d/floor42-unshare",
                        "sudo apparmor_parser -r /etc/apparmor.d/floor42-unshare")
                .endsWith("(persist it in /etc/sysctl.d/)");
        assertThat(isolation.command().indexOf("apparmor_parser"))
                .as("the profile comes first, the sysctl is the weaker alternative")
                .isLessThan(
                        isolation.command().indexOf("sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0"));
        assertThat(isolation.detail()).contains("AppArmor", "Ubuntu");
        assertThat(isolation.note())
                .as("a note the screen shows, with the remedy")
                .isEqualTo("userns");
        assertThat(c.rows()).extracting(ChecklistRow::id).containsExactly("git", "isolation", "pasta");
    }

    @Test
    void pastaIsAnOptionalRowWithItsInstallCommandWhenAbsent(@TempDir Path sys, @TempDir Path bin) throws Exception {
        Checklist missing = NativeChecks.apply(base(ChecklistState.PRESENT), NativeChecks.measure(true, "", sys));
        ChecklistRow row = missing.rows().get(2);
        assertThat(row.id()).isEqualTo("pasta");
        assertThat(row.state()).isEqualTo(ChecklistState.OPTIONAL);
        assertThat(row.command()).isEqualTo("sudo apt install passt");
        assertThat(missing.ready())
                .as("network is optional: the checklist is still ready")
                .isTrue();

        Path pasta = Files.createFile(bin.resolve("pasta"));
        assertThat(pasta.toFile().setExecutable(true)).isTrue();
        Checklist present =
                NativeChecks.apply(base(ChecklistState.PRESENT), NativeChecks.measure(true, bin.toString(), sys));
        assertThat(present.rows().get(2).state()).isEqualTo(ChecklistState.PRESENT);
    }

    @Test
    void anUnmeasuredIsolationRowOnLinuxIsNotMeasuredYetNotOnlyOnWindows(@TempDir Path sys) {
        Checklist c = new Checklist(
                false,
                "Debian",
                null,
                false,
                List.of(new ChecklistRow(
                        "isolation", false, ChecklistState.OPTIONAL, false, null, "notWindows", null, null)));

        ChecklistRow row = NativeChecks.apply(c, NativeChecks.measure(true, "", sys))
                .rows()
                .get(0);

        assertThat(row.note()).isEqualTo("notMeasured");
        assertThat(row.state()).isEqualTo(ChecklistState.UNKNOWN);
    }

    @Test
    void aToolTheSandboxDoesNotShowGetsARowSayingWhich() {
        var f = new NativeChecks.Facts(
                true, true, null, java.util.Map.of("node", "node (/home/u/.local/bin/node) is in no known layout"));

        Checklist c = NativeChecks.apply(base(ChecklistState.PRESENT), f);

        ChecklistRow row = c.rows().getLast();
        assertThat(row.id()).as("one row per tool, named on screen").isEqualTo("hidden.node");
        assertThat(row.note()).isEqualTo("hiddenTool");
        assertThat(row.detail()).contains("node");
        assertThat(row.required()).isFalse();
    }

    @Test
    void offLinuxNothingIsAdded(@TempDir Path sys) {
        Checklist c = base(ChecklistState.PRESENT);
        assertThat(NativeChecks.apply(c, NativeChecks.measure(false, "", sys))).isSameAs(c);
    }
}
