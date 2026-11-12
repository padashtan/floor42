package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where deliverables may land (ADR-0051), judged on real paths: a junction or a link is followed before any check, and
 * the folders that configure a tool are refused wherever they sit, inside the project or not.
 */
class DeliveryDestinationTest {

    @TempDir
    Path tmp;

    Path project() throws IOException {
        return Files.createDirectories(tmp.resolve("project"));
    }

    Path data() throws IOException {
        return Files.createDirectories(tmp.resolve("data"));
    }

    /** A junction on Windows (no privilege needed), a symbolic link elsewhere; false when the system refuses both. */
    static boolean link(Path link, Path target) {
        try {
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                        .redirectErrorStream(true)
                        .start();
                p.getInputStream().readAllBytes();
                return p.waitFor() == 0 && Files.exists(link);
            }
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            // Handled: no link can be made here: the test that needs one is skipped, never passed falsely
            return false;
        } catch (InterruptedException e) {
            // Handled: interrupted while making the link: the flag is kept and the test that needs it is skipped
            Thread.currentThread().interrupt();
            return false;
        }
    }

    void refused(Path project, String raw) {
        assertThatThrownBy(() -> DeliveryDestination.resolve(project, raw, tmp.resolve("data")))
                .as(raw)
                .isInstanceOf(RefusedException.class)
                .extracting("code")
                .isEqualTo("INVALID_DESTINATION");
    }

    @Test
    void aFolderInsideTheProjectIsKeptAsItsRealPath() throws Exception {
        Path project = project();
        data();
        assertThat(DeliveryDestination.resolve(project, "deliverables", tmp.resolve("data")))
                .isEqualTo(project.toRealPath().resolve("deliverables"));
        assertThat(DeliveryDestination.resolve(project, "  ", tmp.resolve("data")))
                .isNull();
    }

    @Test
    void aJunctionAliasOfTheProjectIsFollowedBeforeAnyCheck() throws Exception {
        Path project = project();
        data();
        Files.createDirectories(project.resolve(".claude/commands"));
        Path alias = tmp.resolve("alias");
        assumeTrue(link(alias, project), "no junction or link can be made here");
        refused(project, alias.resolve(".claude").resolve("commands").toString());
        refused(project, alias.toString());
        assertThat(DeliveryDestination.resolve(
                        project, alias.resolve("deliverables").toString(), tmp.resolve("data")))
                .isEqualTo(project.toRealPath().resolve("deliverables"));
    }

    @Test
    void toolFoldersAreRefusedAnywhereOnDisk() throws Exception {
        Path project = project();
        data();
        Path other = Files.createDirectories(tmp.resolve("other-repo/.claude/agents"));
        Path ide = Files.createDirectories(tmp.resolve("elsewhere/.vscode"));
        Path modules = Files.createDirectories(tmp.resolve("elsewhere/node_modules/pkg"));
        for (Path p : new Path[] {other, ide, modules}) refused(project, p.toString());
        for (String inside : new String[] {".claude", ".git/hooks", ".github/workflows", ".idea", "~/.claude", "."}) {
            refused(project, inside);
        }
        refused(project, "../outside");
    }

    @Test
    void theHomeFolderItsClaudeFolderADiskRootAndTheDataFolderAreRefused() throws Exception {
        Path project = project();
        Path data = data();
        Path home = Path.of(System.getProperty("user.home"));
        refused(project, home.toString());
        Path claude = home.resolve(".claude");
        if (Files.isDirectory(claude)) refused(project, claude.toString());
        refused(project, home.resolve(".claude").resolve("commands").toString());
        refused(project, project.toAbsolutePath().getRoot().toString());
        refused(project, data.toString());
        Files.createDirectories(data.resolve("workspaces"));
        refused(project, data.resolve("workspaces").toString());
        String system = System.getenv("SystemRoot");
        if (system != null) refused(project, system);
    }

    @Test
    void aShareIsRefusedFromItsTextAloneWithoutReachingAServer() throws Exception {
        Path project = project();
        long start = System.nanoTime();
        // 203.0.113.0/24 is reserved for documentation: nothing answers there, so any lookup would hang.
        refused(project, "\\\\203.0.113.7\\share\\deliverables");
        refused(project, "//203.0.113.7/share/deliverables");
        refused(project, "\\\\?\\C:\\deliverables");
        assertThat((System.nanoTime() - start) / 1_000_000).as("milliseconds").isLessThan(1_000);
    }
}
