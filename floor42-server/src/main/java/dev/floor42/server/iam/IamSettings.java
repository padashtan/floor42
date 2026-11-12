package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.security.OwnerOnly;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * IAM mode's configuration, read once at start: {@code <data-dir>/iam/settings.json}, and the MicroProfile properties
 * {@code floor42.iam.enabled}, {@code floor42.iam.issuer} and {@code floor42.iam.max-token-hours} when they are set
 * (they win; tests use them). Saving changes the file only: the running mode changes at the next restart. An unreadable
 * file reads as "off": IAM is opt-in, so a damaged file can never switch it on.
 */
@ApplicationScoped
public class IamSettings {

    private static final Logger LOG = Logger.getLogger(IamSettings.class);
    public static final String DEFAULT_ISSUER = "http://localhost:8180/realms/floor42";
    static final int MAX_HOURS = 8;

    /** The file's content; {@code maxTokenHours} is clamped when read or saved. */
    public record Settings(boolean enabled, String issuer, int maxTokenHours) {}

    private final Path file;
    private final ObjectMapper json;
    private final Settings running;
    private volatile Settings saved;

    @Inject
    IamSettings(
            @ConfigProperty(name = "floor42.data-dir") Path dataDir,
            @ConfigProperty(name = "floor42.iam.enabled") Optional<Boolean> enabled,
            @ConfigProperty(name = "floor42.iam.issuer") Optional<String> issuer,
            @ConfigProperty(name = "floor42.iam.max-token-hours") Optional<Integer> hours,
            ObjectMapper json) {
        this.file = dataDir.resolve("iam").resolve("settings.json");
        this.json = json;
        Settings f = read();
        this.running = new Settings(
                enabled.orElse(f.enabled()),
                issuer.filter(s -> !s.isBlank()).orElse(f.issuer()),
                clamp(hours.orElse(f.maxTokenHours())));
        this.saved = f;
    }

    public boolean enabled() {
        return running.enabled();
    }

    public URI issuer() {
        return URI.create(running.issuer());
    }

    /** 1..8: a delegation never outlives a working day. */
    public int maxTokenHours() {
        return running.maxTokenHours();
    }

    /** What is saved, which is what the next start runs (unless a property overrides it). */
    public Settings saved() {
        return saved;
    }

    /** True when the saved settings differ from the running ones. */
    public boolean restartNeeded() {
        return !running.equals(saved);
    }

    public synchronized void save(Settings s) {
        Settings clean = new Settings(s.enabled(), s.issuer(), clamp(s.maxTokenHours()));
        try {
            Files.createDirectories(file.getParent());
            OwnerOnly.restrict(file.getParent());
            Path tmp = file.resolveSibling("settings.json.tmp");
            Files.write(tmp, json.writeValueAsBytes(clean));
            OwnerOnly.restrict(tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save the IAM settings", e);
        }
        saved = clean;
    }

    private Settings read() {
        Settings off = new Settings(false, DEFAULT_ISSUER, MAX_HOURS);
        if (!Files.exists(file)) return off;
        try {
            var n = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
            return new Settings(
                    n.path("enabled").asBoolean(false),
                    n.path("issuer").asText(DEFAULT_ISSUER),
                    clamp(n.path("maxTokenHours").asInt(MAX_HOURS)));
        } catch (IOException | RuntimeException e) {
            // Handled: IAM is opt-in, so a damaged file reads as off, loudly
            LOG.warn("The IAM settings file cannot be read, IAM stays off: "
                    + e.getClass().getSimpleName());
            return off;
        }
    }

    static int clamp(int hours) {
        return Math.max(1, Math.min(MAX_HOURS, hours));
    }
}
