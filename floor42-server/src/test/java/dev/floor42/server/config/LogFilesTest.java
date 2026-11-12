package dev.floor42.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Owner-only logs on POSIX systems; skipped where the file system has no POSIX modes (Windows keeps its rights). */
class LogFilesTest {

    @TempDir
    Path tmp;

    @Test
    void theLogFolderAndItsFilesBecomeOwnerOnly() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path logs = tmp.resolve("logs");
        Files.createDirectories(logs);
        Path log = Files.writeString(logs.resolve("floor42.log"), "x");
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-r--r--"));

        LogFiles.restrict(logs);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(logs)))
                .isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(log)))
                .isEqualTo("rw-------");
    }

    @Test
    void aMissingFolderIsCreatedOnPosixAndLeftAloneElsewhere() {
        Path logs = tmp.resolve("new/logs");

        LogFiles.restrict(logs);

        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        assertThat(Files.isDirectory(logs)).isEqualTo(posix);
    }
}
