package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What of a home folder the native sandbox shows (real layouts of toolchain managers): the caches and the install root
 * of each tool, never a folder above a PATH entry, never credentials, keyrings or history. The system folders are
 * given to the code under test, so the result does not depend on where the checkout or the temporary folder is.
 */
@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
class ToolchainMountsTest {

    @TempDir
    Path home;

    private Path file(String rel) throws Exception {
        Path p = home.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
        assertThat(p.toFile().setExecutable(true)).isTrue();
        return p;
    }

    /** What an agent must never see: the things a home folder holds besides tools. */
    private void plantPrivate() throws Exception {
        file(".local/share/keyrings/login.keyring");
        file(".bash_history");
        file(".local/state/app/state.db");
        file(".m2/settings.xml");
        file(".npmrc");
        Files.createDirectories(home.resolve(".m2/repository"));
        Files.createDirectories(home.resolve(".npm/_cacache"));
    }

    private ToolchainMounts.Result resolve(String path) {
        return ToolchainMounts.resolve(home.toString(), Map.of("PATH", path), List.of(), List.of());
    }

    private void assertNothingPrivate(ToolchainMounts.Result r) throws Exception {
        String listing = ToolchainMounts.listing(r);
        assertThat(listing)
                .contains("cache " + home.toRealPath().resolve(".m2/repository"))
                .doesNotContain("keyrings", "history", "state", "settings.xml", ".npmrc", "_cacache");
        assertThat(listing.lines())
                .as("no folder above a PATH entry")
                .doesNotContain("dir " + home.toRealPath() + "/.local");
        assertThat(listing.lines()).doesNotContain("dir " + home.toRealPath());
    }

    @Test
    void uvPythonBehindLocalBinShowsOnlyItsInterpreterFolder() throws Exception {
        plantPrivate();
        Path real = file(".local/share/uv/python/cpython-3.12-linux/bin/python3.12");
        Files.createSymbolicLink(
                Files.createDirectories(home.resolve(".local/bin")).resolve("python3"), real);

        ToolchainMounts.Result r = resolve(home.resolve(".local/bin").toString());

        assertThat(ToolchainMounts.listing(r))
                .contains("dir " + real.getParent().getParent().toRealPath())
                .contains("bin " + real.getParent().toRealPath());
        assertThat(r.hidden()).isEmpty();
        assertNothingPrivate(r);
    }

    @Test
    void nodeInstalledWithNPrefixLocalIsNotShownAndSaysWhy() throws Exception {
        plantPrivate();
        file(".local/bin/node");
        file(".local/lib/node_modules/npm/package.json");

        ToolchainMounts.Result r = resolve(home.resolve(".local/bin").toString());

        assertThat(r.dirs().stream().map(Path::toString)).noneMatch(d -> d.contains("/.local"));
        assertThat(r.hidden()).containsOnlyKeys("node");
        assertThat(r.hidden().get("node")).startsWith("node (").contains("no known toolchain layout");
        assertNothingPrivate(r);
    }

    @Test
    void pyenvShimsAreHiddenButAVersionsFolderIsShown() throws Exception {
        plantPrivate();
        file(".pyenv/shims/python3");
        Path version = file(".pyenv/versions/3.12.1/bin/python3");

        ToolchainMounts.Result shims = resolve(home.resolve(".pyenv/shims").toString());
        assertThat(shims.hidden()).containsOnlyKeys("python3");
        assertThat(ToolchainMounts.listing(shims)).doesNotContain(".pyenv");
        assertNothingPrivate(shims);

        ToolchainMounts.Result versions = resolve(version.getParent().toString());
        assertThat(ToolchainMounts.listing(versions))
                .contains("dir " + version.getParent().getParent().toRealPath());
        assertNothingPrivate(versions);
    }

    @Test
    void nvmShowsOneNodeVersionNotTheNvmFolder() throws Exception {
        plantPrivate();
        file(".nvm/nvm.sh");
        Path node = file(".nvm/versions/node/v20.11.0/bin/node");

        ToolchainMounts.Result r = resolve(node.getParent().toString());

        assertThat(r.dirs()).contains(node.getParent().getParent().toRealPath());
        assertThat(ToolchainMounts.listing(r))
                .doesNotContain("nvm.sh")
                .doesNotContain("dir " + home.toRealPath() + "/.nvm\n");
        assertNothingPrivate(r);
    }

    @Test
    void sdkmanCurrentLinkIsResolvedToItsVersionFolder() throws Exception {
        plantPrivate();
        Path java = file(".sdkman/candidates/java/17.0.1-tem/bin/java");
        Files.createSymbolicLink(home.resolve(".sdkman/candidates/java/current"), Path.of("17.0.1-tem"));

        ToolchainMounts.Result r =
                resolve(home.resolve(".sdkman/candidates/java/current/bin").toString());

        assertThat(ToolchainMounts.listing(r))
                .contains("dir " + java.getParent().getParent().toRealPath())
                .contains("bin " + java.getParent().toRealPath())
                .doesNotContain("current");
        assertNothingPrivate(r);
    }

    private Path executable(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
    }

    @Test
    void outsideTheHomeFolderOnlyAFolderWithToolchainMarkersIsShown(@TempDir Path opt) throws Exception {
        plantPrivate();
        Path jdk = opt.resolve("jdk-21");
        executable(jdk.resolve("bin/java"));
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=21");
        Path node = opt.resolve("node-20");
        executable(node.resolve("bin/node"));
        Files.createDirectories(node.resolve("lib/node_modules/npm/bin"));
        executable(node.resolve("lib/node_modules/npm/bin/npm-cli.js"));
        Files.createSymbolicLink(node.resolve("bin/npm"), Path.of("../lib/node_modules/npm/bin/npm-cli.js"));
        Path maven = opt.resolve("maven");
        executable(maven.resolve("bin/mvn"));
        Files.createDirectories(maven.resolve("boot"));

        ToolchainMounts.Result r = resolve(jdk.resolve("bin") + ":" + node.resolve("bin") + ":" + maven.resolve("bin"));

        assertThat(r.dirs()).containsExactlyInAnyOrder(jdk.toRealPath(), node.toRealPath(), maven.toRealPath());
        assertThat(r.hidden()).isEmpty();
        assertNothingPrivate(r);
    }

    @Test
    void aStrayBinFolderOutsideTheHomeNeverShowsTheFolderAroundIt(@TempDir Path data) throws Exception {
        plantPrivate();
        executable(data.resolve("u/bin/java"));
        executable(data.resolve("u/.aws/credentials"));
        Files.createSymbolicLink(data.resolve("bin2"), data.resolve("u/bin"));

        ToolchainMounts.Result r = resolve(data.resolve("bin2").toString());

        assertThat(r.dirs()).isEmpty();
        assertThat(r.hidden()).containsOnlyKeys("java");
        assertThat(ToolchainMounts.listing(r))
                .doesNotContain(".aws", data.resolve("u").toString());
    }

    @Test
    void emptyAndRelativePathEntriesAreIgnored() throws Exception {
        plantPrivate();
        Path here = Path.of("").toAbsolutePath();
        Path tools = Files.createTempDirectory(here.resolve("target"), "relative-path");
        Path java = executable(tools.resolve("bin/java"));
        Files.writeString(tools.resolve("release"), "x");
        String relative = here.relativize(java.getParent()).toString();

        ToolchainMounts.Result r = resolve(":" + relative + "::.");

        assertThat(r.dirs())
                .as("found only through the daemon's working folder")
                .isEmpty();
        assertThat(r.hidden()).isEmpty();
    }

    @Test
    void aHomeThatIsALinkIsJudgedByItsRealPath(@TempDir Path elsewhere) throws Exception {
        Path link = elsewhere.resolve("home-link");
        Files.createSymbolicLink(link, home);
        Files.createDirectories(home.resolve(".m2/repository"));

        ToolchainMounts.Result r = ToolchainMounts.resolve(link.toString(), Map.of("PATH", ""), List.of(), List.of());

        assertThat(r.caches()).containsExactly(home.toRealPath().resolve(".m2/repository"));
        assertThat(r.dirs()).isEmpty();
    }

    @Test
    void aToolUnderAFolderTheSandboxDoesNotHaveIsReportedNotSilentlySkipped(@TempDir Path anywhere) throws Exception {
        Path bin = Files.createDirectories(anywhere.resolve("tools/bin"));
        executable(bin.resolve("java"));
        Files.writeString(anywhere.resolve("tools/release"), "x");

        // The same folder, given as one the sandbox does not have (/mnt, /media, /var, /run, /etc, /tmp in production).
        ToolchainMounts.Result r = ToolchainMounts.resolve(
                home.toString(), Map.of("PATH", bin.toString()), List.of(), List.of(anywhere.toRealPath()));

        Path real = anywhere.toRealPath();
        assertThat(r.dirs()).noneMatch(d -> d.startsWith(real));
        assertThat(r.hidden()).containsOnlyKeys("java");
        assertThat(r.hidden().get("java")).contains("is under", "does not have");
        assertThat(ToolchainMounts.UNAVAILABLE)
                .contains(
                        Path.of("/media"),
                        Path.of("/mnt"),
                        Path.of("/tmp"),
                        Path.of("/var"),
                        Path.of("/run"),
                        Path.of("/etc"));
    }

    @Test
    void aToolUnderAnInstallMarkedForAnotherToolIsNotShown(@TempDir Path opt) throws Exception {
        plantPrivate();
        Path python = opt.resolve("py");
        executable(python.resolve("bin/python3"));
        Files.createDirectories(python.resolve("lib/python3.12"));
        executable(python.resolve("bin/node")); // a node binary dropped into a Python prefix: no lib/node_modules

        ToolchainMounts.Result r = resolve(python.resolve("bin").toString());

        assertThat(r.dirs()).as("python3 is shown by its own markers").containsExactly(python.toRealPath());
        assertThat(r.hidden()).as("node is not: the markers are Python's").containsOnlyKeys("node");
    }
}
