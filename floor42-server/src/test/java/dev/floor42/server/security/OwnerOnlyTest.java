package dev.floor42.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerOnlyTest {

    @TempDir
    Path dir;

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    @Test
    void aFileIs600AndAFolderIs700OnPosix() throws Exception {
        assumeTrue(posix());
        Path f = Files.createFile(dir.resolve("f"));
        Path d = Files.createDirectory(dir.resolve("d"));
        OwnerOnly.restrict(f);
        OwnerOnly.restrict(d);
        assertThat(Files.getPosixFilePermissions(f))
                .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        assertThat(Files.getPosixFilePermissions(d))
                .isEqualTo(Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
    }

    @Test
    void onlyTheOwnerHasAnAclEntryOnNtfs() throws Exception {
        assumeTrue(!posix());
        Path f = Files.createFile(dir.resolve("f"));
        Path d = Files.createDirectory(dir.resolve("d"));
        for (Path p : new Path[] {f, d}) {
            OwnerOnly.restrict(p);
            AclFileAttributeView acl = Files.getFileAttributeView(p, AclFileAttributeView.class);
            assertThat(acl.getAcl()).hasSize(1).allSatisfy(e -> {
                assertThat(e.type()).isEqualTo(AclEntryType.ALLOW);
                assertThat(e.principal()).isEqualTo(acl.getOwner());
            });
        }
    }
}
