package dev.floor42.server.security;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The director's token, random at each start (ADR-0007), kept in memory only (ADR-0034): agents run as the same OS user
 * as the director, so a file would be theirs to read. The browser signs in with a one-time link instead
 * ({@link DirectorSignIn}). Scripts that need a bearer token can ask for the old file with
 * {@code floor42.director-token-file=true}, which gives it back to unconfined agents too. It is never logged.
 */
@ApplicationScoped
public class SessionToken {

    static final String FILE_NAME = "session.token";

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    @ConfigProperty(name = "floor42.director-token-file", defaultValue = "false")
    boolean writeFile;

    private volatile String value;

    @PostConstruct
    void generate() {
        rotate();
    }

    /**
     * A new director token: the previous one stops working at once ("sign out everywhere", ADR-0044). With the
     * deprecated file opt-in, the file is rewritten too.
     */
    public synchronized void rotate() {
        value = random(32);
        Path file = file();
        try {
            Files.createDirectories(dataDir);
            if (writeFile) {
                Files.writeString(file, value, StandardCharsets.UTF_8);
                OwnerOnly.restrict(file);
            } else {
                // A file left by an earlier version or an earlier start: no token at rest.
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare the session token in " + dataDir, e);
        }
    }

    public Path file() {
        return dataDir.resolve(FILE_NAME);
    }

    /** Whether the token is written to {@link #file()} for scripts (the opt-in that re-opens D12). */
    public boolean writtenToFile() {
        return writeFile;
    }

    public boolean matches(String candidate) {
        return sameSecret(value, candidate);
    }

    /** For tests and the CLI started in-process; never expose it over HTTP. */
    public String value() {
        return value;
    }

    static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static boolean sameSecret(String secret, String candidate) {
        if (candidate == null || secret == null) return false;
        return MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }

    private static final SecureRandom RANDOM = new SecureRandom();
}
