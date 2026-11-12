package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The native Linux script is made from the WSL one, and refuses to be made from a script it does not recognise. */
class SandboxNativeScriptTest {

    private static final String MASKS_FROM = "D=/media/.dev\n";
    private static final String MASKS_TO = "export HOME=/tmp";
    private static final String NETWORK_FROM = "[ \"$3\" = \"on\" ] && NET=\n";
    private static final String EXEC_FROM = "exec unshare -Urm -C -i -u $NET";

    @Test
    void theWslScriptBecomesTheNativeOneWithItsOwnRootAndNoWslVariables() {
        String linux = SandboxNative.script(Sandbox.RUN);

        assertThat(linux)
                .contains("pivot_root . old", "unset WSL_INTEROP WSLENV\n", "exec $PRE unshare -Urm -C -i -u $NET");
        assertThat(linux).doesNotContain(EXEC_FROM).contains("$XP").isNotEqualTo(Sandbox.RUN);
        assertThat(linux.indexOf("pivot_root")).isLessThan(linux.indexOf(MASKS_TO));
        assertThat(linux).contains(MASKS_TO);
    }

    @Test
    void mavenConfigurationIsBoundButItsSettingsAreMaskedAndNpmrcIsNotBound() {
        String linux = SandboxNative.script(Sandbox.RUN);
        // Debian's mvn reads /etc/maven/m2.conf: the folder is there; its settings (passwords) and npmrc are not.
        assertThat(linux).contains(" /etc/maven ").doesNotContain("/etc/npmrc");
        assertThat(linux)
                .contains("settings.xml settings-security.xml")
                // An empty read-only file, never /dev/null: a device stays writable in their place.
                .contains("mount --bind /tmp/.empty \"$NR$p/$f\"; rr \"$NR$p/$f\"")
                .doesNotContain("mount --bind /dev/null");
    }

    @Test
    void bothScriptsExportTheCacheAsAReadOnlyMavenTailAndOnlyWslMountsItBeforeTheMntMask() {
        String wsl = Sandbox.RUN;
        String linux = SandboxNative.script(wsl);

        assertThat(linux).contains("-Dmaven.repo.local.tail=${MR-}$p").contains("done < /media/.tools");
        assertThat(wsl).contains("tools /media/.tools \"$MR\" /media").contains("MR=/media/.mroot");
        assertThat(wsl.indexOf("MR=/media/.mroot"))
                .as("the mount comes before /mnt is masked")
                .isLessThan(wsl.indexOf("for d in /mnt /run"));
        assertThat(linux).doesNotContain("MR=/media/.mroot");
        assertThat(linux.indexOf("-Dmaven.repo.local.tail"))
                .as("after the pivot, once the home is set")
                .isGreaterThan(linux.indexOf("pivot_root"));
        assertThat(wsl).doesNotContain("settings.xml", "wrapper");
    }

    @Test
    void bothScriptsBringLoUpWhenTheNetworkIsOffAndDoNotLeakTheSwitch() {
        String wsl = Sandbox.RUN;
        String linux = SandboxNative.script(wsl);

        for (String script : new String[] {wsl, linux}) {
            assertThat(script).contains("[ \"$F42_NET\" = on ] || python3 -I /media/.mounts.py loopback");
        }
        assertThat(wsl.indexOf("loopback")).isLessThan(wsl.indexOf("unset F42_NET"));
        assertThat(wsl).as("unset before the command").contains("unset F42_NET\nMR=");
    }

    @Test
    void aWindowsHomeBecomesItsWslMavenRepositoryAndNothingElseOfDotM2() {
        assertThat(ToolchainMounts.wslRepository("C:\\Users\\me")).isEqualTo("/mnt/c/Users/me/.m2/repository");
        assertThat(ToolchainMounts.wslRepository("D:/home/me/")).isEqualTo("/mnt/d/home/me/.m2/repository");
        assertThat(ToolchainMounts.wslRepository("C:\\Users\\Jane Doe")).isNull();
        assertThat(ToolchainMounts.wslRepository("\\\\server\\share\\me")).isNull();
        assertThat(ToolchainMounts.wslRepository(null)).isNull();
    }

    @Test
    void theInnerShellIsOneSingleQuotedStringSoNoPartOfItHoldsASingleQuote() {
        String linux = SandboxNative.script(Sandbox.RUN);
        int from = linux.indexOf("INNER='") + "INNER='".length();
        int to = linux.indexOf("\n'\nexec ", from);
        assertThat(to).isGreaterThan(from);
        // An apostrophe in a comment ends the string early: "done unexpected" on every sandbox start (2026-11-11).
        assertThat(linux.substring(from, to)).doesNotContain("'");
    }

    @Test
    void aScriptThatLostAnAnchorIsRefusedByName() {
        String run = Sandbox.RUN;
        for (String anchor : new String[] {MASKS_FROM, MASKS_TO, NETWORK_FROM, EXEC_FROM}) {
            String broken = run.replace(anchor, "");
            assertThatThrownBy(() -> SandboxNative.script(broken))
                    .as(anchor)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("The sandbox script changed");
        }
    }

    @Test
    void theMaskEndMustComeAfterItsStart() {
        String run = "x" + MASKS_TO + "\n" + MASKS_FROM + NETWORK_FROM + EXEC_FROM;
        assertThatThrownBy(() -> SandboxNative.script(run)).isInstanceOf(IllegalStateException.class);
    }

    /**
     * On Ubuntu 24.04 (util-linux 2.39) {@code mount --move} run as an unprivileged user in its own user namespace
     * ends with "failed to update userspace mount table" and exit code 16, and the script runs under {@code set -e}:
     * every sandbox start broke on the GitHub runners (2026-11-10). {@code -n} leaves the table alone.
     */
    @Test
    void everyMoveMountOfTheNativeScriptLeavesTheUserspaceMountTableAlone() {
        String linux = SandboxNative.script(Sandbox.RUN);

        var moves = linux.lines()
                .map(String::strip)
                .filter(l -> l.startsWith("mount ") && l.contains("--move"))
                .toList();
        assertThat(moves).isNotEmpty();
        assertThat(moves).allSatisfy(l -> {
            var words = java.util.List.of(l.split("\s+"));
            assertThat(words.indexOf("-n")).as(l).isPositive().isLessThan(words.indexOf("--move"));
        });
    }
}
