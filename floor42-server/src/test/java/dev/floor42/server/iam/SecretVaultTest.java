package dev.floor42.server.iam;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretVaultTest {

    @TempDir
    Path dataDir;

    private static byte[] bytes(String s) {
        return s.getBytes(UTF_8);
    }

    @Test
    void roundTripAndTamper() throws IOException {
        var vault = new SecretVault(dataDir);
        vault.put("agent-ada", bytes("s3cret"));
        assertThat(vault.get("agent-ada")).hasValue(bytes("s3cret"));
        Path file = dataDir.resolve("iam/vault/agent-ada.bin");
        byte[] raw = Files.readAllBytes(file);
        raw[raw.length - 1] ^= 1;
        Files.write(file, raw);
        assertThatThrownBy(() -> vault.get("agent-ada"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("s3cret");
    }

    @Test
    void keyLivesOutsideEveryWorktreeAndTheFileHoldsNoPlaintext() throws IOException {
        var vault = new SecretVault(dataDir);
        vault.put("agent-ada", bytes("s3cret"));
        assertThat(vault.keyFile()).isEqualTo(dataDir.resolve("iam/vault.key"));
        assertThat(vault.keyFile().startsWith(Path.of("").toAbsolutePath())).isFalse();
        assertThat(Files.readString(vault.keyFile().resolveSibling("vault/agent-ada.bin"), ISO_8859_1))
                .doesNotContain("s3cret");
        assertThat(Files.readAllBytes(vault.keyFile())).hasSize(32);
    }

    @Test
    void everyWriteUsesAFreshIvAndAnEntryCannotBePassedOffAsAnother() throws IOException {
        var vault = new SecretVault(dataDir);
        Path a = dataDir.resolve("iam/vault/agent-a.bin");
        vault.put("agent-a", bytes("same"));
        byte[] first = Files.readAllBytes(a);
        vault.put("agent-a", bytes("same"));
        byte[] second = Files.readAllBytes(a);
        assertThat(Arrays.copyOf(first, 12)).isNotEqualTo(Arrays.copyOf(second, 12));
        Files.copy(a, dataDir.resolve("iam/vault/agent-b.bin"));
        assertThatThrownBy(() -> vault.get("agent-b")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void absentDeleteAndNameRules() {
        var vault = new SecretVault(dataDir);
        assertThat(vault.get("nothing")).isEmpty();
        vault.put("floor42-api", bytes("x"));
        vault.delete("floor42-api");
        vault.delete("floor42-api");
        assertThat(vault.get("floor42-api")).isEmpty();
        for (String bad :
                new String[] {"", "A", "../x", "a/b", "a.b", "x".repeat(81), "/etc/x", "..\\x", "C:\\x", "a\u0000b"})
            assertThatThrownBy(() -> vault.put(bad, bytes("x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> vault.get("../key")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filesAreOwnerOnly() throws IOException {
        var vault = new SecretVault(dataDir);
        vault.put("agent-ada", bytes("s3cret"));
        Path file = dataDir.resolve("iam/vault/agent-ada.bin");
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                    .isEqualTo("rw-------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(vault.keyFile())))
                    .isEqualTo("rw-------");
        } else {
            var acl = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
            assertThat(acl.getAcl()).hasSize(1);
            assertThat(acl.getAcl().get(0).principal()).isEqualTo(acl.getOwner());
            var keyAcl =
                    Files.getFileAttributeView(vault.keyFile(), java.nio.file.attribute.AclFileAttributeView.class);
            assertThat(keyAcl.getAcl()).hasSize(1);
        }
    }

    @Test
    void aKeyThatIsNot32BytesIsReportedNeverReplacedAndStopsTheVault() throws IOException {
        var vault = new SecretVault(dataDir);
        assertThat(vault.keyProblem()).as("absent is fine").isEmpty();
        vault.put("agent-ada", bytes("s3cret"));
        assertThat(vault.keyProblem()).as("32 bytes is fine").isEmpty();

        byte[] good = Files.readAllBytes(vault.keyFile());
        Files.write(vault.keyFile(), Arrays.copyOf(good, 31));
        assertThat(vault.keyProblem())
                .hasValueSatisfying(m -> assertThat(m)
                        .contains(vault.keyFile().toString())
                        .contains("is not 32 bytes")
                        .contains("will not replace"));
        assertThat(Files.size(vault.keyFile())).isEqualTo(31);

        var fresh = new SecretVault(dataDir);
        assertThatThrownBy(() -> fresh.put("agent-bob", bytes("x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not 32 bytes");
        assertThat(Files.size(vault.keyFile())).as("never regenerated").isEqualTo(31);
    }

    @Test
    void theKeyIsMadeOnceAndAnotherInstanceReadsTheSameSecrets() throws IOException {
        new SecretVault(dataDir).put("agent-ada", bytes("s3cret"));
        byte[] key = Files.readAllBytes(dataDir.resolve("iam/vault.key"));

        var other = new SecretVault(dataDir);
        assertThat(other.get("agent-ada")).hasValue(bytes("s3cret"));
        other.put("agent-bob", bytes("b"));
        assertThat(Files.readAllBytes(dataDir.resolve("iam/vault.key"))).isEqualTo(key);
    }

    @Test
    void aWriteReplacesTheEntryLeavesNoTemporaryFileAndKeepsEmptySecrets() throws IOException {
        var vault = new SecretVault(dataDir);
        vault.put("agent-ada", bytes("one"));
        vault.put("agent-ada", bytes("two"));
        vault.put("agent-empty", new byte[0]);

        assertThat(vault.get("agent-ada")).hasValue(bytes("two"));
        assertThat(vault.get("agent-empty"))
                .hasValueSatisfying(b -> assertThat(b).isEmpty());
        try (var names = Files.list(dataDir.resolve("iam/vault"))) {
            assertThat(names.map(f -> f.getFileName().toString()))
                    .containsExactlyInAnyOrder("agent-ada.bin", "agent-empty.bin");
        }
    }

    @Test
    void anEntryTooShortToHoldAnIvAndATagIsDamagedNotTampered() throws IOException {
        var vault = new SecretVault(dataDir);
        vault.put("agent-ada", bytes("s3cret"));
        Path file = dataDir.resolve("iam/vault/agent-ada.bin");

        Files.write(file, new byte[12]);
        assertThatThrownBy(() -> vault.get("agent-ada"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The vault entry agent-ada is damaged");

        Files.write(file, new byte[13]);
        assertThatThrownBy(() -> vault.get("agent-ada"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent-ada cannot be opened (tampered or wrong key)");
    }

    @Test
    void aDirectoryInPlaceOfAnEntryIsNotAnEntry() throws IOException {
        var vault = new SecretVault(dataDir);
        vault.put("agent-ada", bytes("x"));
        vault.delete("agent-ada");
        Files.createDirectories(dataDir.resolve("iam/vault/agent-dir.bin"));
        assertThat(vault.get("agent-dir")).isEmpty();
    }

    @Test
    void theKeyIsRandomAndTheFoldersAreOwnerOnlyToo() throws IOException {
        var vault = new SecretVault(dataDir);
        vault.put("agent-ada", bytes("s3cret"));
        assertThat(Files.readAllBytes(vault.keyFile())).isNotEqualTo(new byte[32]);

        for (Path p : new Path[] {dataDir.resolve("iam"), dataDir.resolve("iam/vault"), vault.keyFile()}) {
            if (Files.getFileAttributeView(p, PosixFileAttributeView.class) != null) {
                String mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
                assertThat(mode).as(p.toString()).endsWith("------");
            } else {
                var acl = Files.getFileAttributeView(p, java.nio.file.attribute.AclFileAttributeView.class);
                assertThat(acl.getAcl()).as(p.toString()).hasSize(1);
                assertThat(acl.getAcl().get(0).principal()).isEqualTo(acl.getOwner());
            }
        }
    }
}
