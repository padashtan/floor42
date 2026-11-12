package dev.floor42.server.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Owner-only rights on a file or a folder: mode 600/700 on POSIX, a single ACL entry for the owner on NTFS. */
public final class OwnerOnly {

    private OwnerOnly() {}

    public static void restrict(Path path) throws IOException {
        boolean dir = Files.isDirectory(path);
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(dir ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException windows) {
            // Handled: not POSIX: the ACL below, which drops the inherited entries, is the owner-only equivalent.
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl == null) throw new IOException("owner-only access impossible on this volume");
            Set<AclEntryFlag> flags = dir
                    ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                    : EnumSet.noneOf(AclEntryFlag.class);
            acl.setAcl(List.of(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .setFlags(flags)
                    .build()));
        }
    }
}
