package dev.floor42.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** With no browser to start (headless Linux, WSL without a desktop) the link goes to a private file, not the log. */
class DirectorSignInHeadlessTest {

    private static final boolean LINUX =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");

    @Test
    void noGraphicalSessionAndNoBrowserMeansNoDesktopOnLinuxOnly() {
        assertThat(DirectorSignIn.noDesktop(Map.of())).isEqualTo(LINUX);
        assertThat(DirectorSignIn.noDesktop(Map.of("DISPLAY", ":0"))).isFalse();
        assertThat(DirectorSignIn.noDesktop(Map.of("WAYLAND_DISPLAY", "wayland-0")))
                .isFalse();
        assertThat(DirectorSignIn.noDesktop(Map.of("BROWSER", "firefox"))).isFalse();
        assertThat(DirectorSignIn.noDesktop(Map.of("DISPLAY", " "))).isEqualTo(LINUX);
    }

    @Test
    void theLinkFileIsPrivateAndHoldsTheLink(@TempDir Path data) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                LINUX || Files.getFileStore(data).supportsFileAttributeView("posix"));
        DirectorSignIn signIn = new DirectorSignIn();
        signIn.dataDir = data;
        signIn.ttlSeconds = 60;

        assertThat(signIn.keepInFile("http://127.0.0.1:7420/auth?code=abc")).isTrue();

        Path file = data.resolve("sign-in-link");
        assertThat(Files.readString(file)).contains("/auth?code=abc");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
    }

    @Test
    void theLinkFileDisappearsAtExpiryWithoutAnyLaterCall(@TempDir Path data) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.getFileStore(data).supportsFileAttributeView("posix"), "POSIX file permissions");
        DirectorSignIn signIn = new DirectorSignIn();
        signIn.dataDir = data;
        signIn.ttlSeconds = 1;
        assertThat(signIn.keepInFile("http://127.0.0.1:7420/auth?code=abc")).isTrue();
        Path file = data.resolve("sign-in-link");
        assertThat(file).exists();

        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (Files.exists(file) && System.nanoTime() < until) Thread.sleep(100);

        assertThat(file).doesNotExist();
    }

    @Test
    void redeemingAnOlderLinkLeavesTheFileOfANewerOne(@TempDir Path data) throws Exception {
        DirectorSignIn signIn = new DirectorSignIn();
        signIn.dataDir = data;
        Path file = data.resolve("sign-in-link");
        Files.writeString(file, "http://127.0.0.1:7420/auth?code=NEWER\n");

        signIn.dropLinkFileOf("OLDER");
        assertThat(file).exists();
        signIn.dropLinkFileOf("NEWER");
        assertThat(file).doesNotExist();
    }

    @Test
    void theFileIsRemovedWhenTheDaemonStops(@TempDir Path data) throws Exception {
        DirectorSignIn signIn = new DirectorSignIn();
        signIn.dataDir = data;
        Path file = Files.writeString(data.resolve("sign-in-link"), "http://127.0.0.1:7420/auth?code=X\n");

        signIn.dropLinkFile(); // what @PreDestroy calls

        assertThat(file).doesNotExist();
    }

    @Test
    void withoutADataFolderNothingIsKept() {
        assertThat(new DirectorSignIn().keepInFile("http://x/auth?code=1")).isFalse();
    }
}
