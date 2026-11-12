package dev.floor42.server.iam;

import dev.floor42.server.security.OwnerOnly;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Secrets at rest: AES-256-GCM, a fresh 12-byte IV per write (stored first in the file), the entry name as additional
 * authenticated data so a file cannot be passed off as another entry. The key is a file next to the vault, owner-only,
 * in the data folder (never in a worktree); IAM mode requires the sandbox, so an agent's shell cannot read it.
 */
@ApplicationScoped
public class SecretVault {

    private static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,80}");
    private static final int IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;
    private byte[] key;

    @Inject
    SecretVault(@ConfigProperty(name = "floor42.data-dir") Path dataDir) {
        this.root = dataDir.resolve("iam");
    }

    public Path keyFile() {
        return root.resolve("vault.key");
    }

    /**
     * Why the key cannot be used, without creating it: a key file that exists but is not 32 bytes (a crash while it was
     * first written). It is never regenerated: a new key would orphan every stored secret. Absent is fine (made on the
     * first write).
     */
    public synchronized Optional<String> keyProblem() {
        try {
            if (!Files.exists(keyFile()) || Files.size(keyFile()) == 32) return Optional.empty();
            return Optional.of(
                    "The vault key file " + keyFile() + " exists but is not 32 bytes. Floor42 will not replace"
                            + " it (stored secrets would be lost): restore it from a backup, or delete it and the vault"
                            + " folder to start again.");
        } catch (IOException e) {
            // Handled: reported as the reason IAM mode cannot start
            return Optional.of(
                    "The vault key file cannot be read: " + e.getClass().getSimpleName());
        }
    }

    public synchronized void put(String name, byte[] secret) {
        Path file = file(name);
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        byte[] sealed = crypt(Cipher.ENCRYPT_MODE, name, iv, secret);
        byte[] out = new byte[IV_BYTES + sealed.length];
        System.arraycopy(iv, 0, out, 0, IV_BYTES);
        System.arraycopy(sealed, 0, out, IV_BYTES, sealed.length);
        try {
            Path folder = file.getParent();
            if (!Files.isDirectory(folder)) {
                Files.createDirectories(folder);
                OwnerOnly.restrict(folder);
            }
            // Rights first, content second: the secret never sits in a file others can read.
            Path tmp = Files.createTempFile(folder, name, ".tmp");
            try {
                OwnerOnly.restrict(tmp);
                Files.write(tmp, out);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the vault entry " + name, e);
        }
    }

    public synchronized Optional<byte[]> get(String name) {
        Path file = file(name);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            byte[] in = Files.readAllBytes(file);
            if (in.length <= IV_BYTES) throw new IllegalStateException("The vault entry " + name + " is damaged");
            return Optional.of(crypt(
                    Cipher.DECRYPT_MODE,
                    name,
                    Arrays.copyOf(in, IV_BYTES),
                    Arrays.copyOfRange(in, IV_BYTES, in.length)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the vault entry " + name, e);
        }
    }

    public synchronized void delete(String name) {
        try {
            Files.deleteIfExists(file(name));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete the vault entry " + name, e);
        }
    }

    private Path file(String name) {
        if (name == null || !NAME.matcher(name).matches())
            throw new IllegalArgumentException("A vault name matches [a-z0-9-]{1,80}");
        return root.resolve("vault").resolve(name + ".bin");
    }

    private byte[] crypt(int mode, String name, byte[] iv, byte[] data) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            // The cause is dropped on purpose: it must not echo anything about the data.
            throw new IllegalStateException("The vault entry " + name + " cannot be opened (tampered or wrong key)", e);
        }
    }

    private byte[] key() {
        if (key != null) return key;
        try {
            Path file = keyFile();
            if (!Files.exists(file)) {
                Files.createDirectories(root);
                OwnerOnly.restrict(root);
                byte[] fresh = new byte[32];
                RANDOM.nextBytes(fresh);
                try {
                    Files.createFile(file); // CREATE_NEW: never overwrites a key another process just made
                    OwnerOnly.restrict(file);
                    Files.write(file, fresh);
                } catch (FileAlreadyExistsException other) {
                    // Handled: another process created the key first; it is read below.
                }
            }
            byte[] read = Files.readAllBytes(file);
            if (read.length != 32) throw new IllegalStateException("The vault key file is not 32 bytes");
            key = read;
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare the vault key", e);
        }
    }
}
