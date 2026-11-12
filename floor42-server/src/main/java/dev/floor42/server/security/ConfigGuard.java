package dev.floor42.server.security;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jboss.logging.Logger;

/**
 * Configuration an agent could have written refuses the start (ADR-0044, amended). Agents may run as the daemon's own
 * OS user, so any file the daemon reads at start and that user can write is theirs to change, then a {@code SIGTERM}
 * makes the daemon read it (in Docker, {@code restart: unless-stopped} starts it again). Quarkus reads {@code .env}
 * and {@code config/application.properties} from the working directory, and nothing turns that off: so a start that
 * finds either refuses, naming the file. The journal's connection is also fixed: its URL must be exactly the one the
 * data folder gives, and nothing may add driver properties or turn SQLite's extension loading on.
 *
 * <p>Configuration through the environment or system properties stays the director's own (the container's
 * environment, the launcher's command line): an agent cannot change those of a running daemon.
 */
@ApplicationScoped
public class ConfigGuard {

    private static final Logger LOG = Logger.getLogger(ConfigGuard.class);

    /** The journal's URL, from the data folder: transactions take the write lock at their start (BEGIN IMMEDIATE). */
    public static final String JOURNAL_URL_SUFFIX = "/floor42.db?transaction_mode=IMMEDIATE";

    private static final Pattern FILE_SOURCE = Pattern.compile("\\[source=(file:[^\\]]+)]");

    @Inject
    Config config;

    private volatile boolean verified;

    /** First of all start observers: before anything acts on the configuration. */
    void atStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent ignored) {
        verify();
    }

    /** Throws when the configuration could have come from an agent; the start then stops. Checked once. */
    public void verify() {
        if (verified) return;
        List<String> problems = new ArrayList<>(
                workingDirectorySources(config.getConfigSources(), Path.of(System.getProperty("user.dir"))));
        problems.addAll(journalConnection(config));
        foreign = foreignFiles(config.getConfigSources(), ownRoot());
        for (String f : foreign) {
            LOG.warnf(
                    "Configuration read from %s, outside Floor42's own files (QUARKUS_CONFIG_LOCATIONS?): make sure no"
                            + " agent can write it",
                    f);
        }
        if (!problems.isEmpty()) {
            String message = "Floor42 refuses to start: " + String.join(" ", problems);
            LOG.error(message);
            throw new IllegalStateException(message);
        }
        verified = true;
    }

    private volatile List<String> foreign = List.of();

    /**
     * Configuration files read from outside the daemon's own build: allowed (the director may point
     * QUARKUS_CONFIG_LOCATIONS somewhere), but logged and shown, since a file an agent can write would configure the
     * next start.
     */
    public List<String> foreignConfigFiles() {
        return foreign;
    }

    /** Where the daemon's own code lies: its jar's folder, or the build's target folder in dev and tests. */
    static Path ownRoot() {
        try {
            Path code = Path.of(ConfigGuard.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath()
                    .normalize();
            return code.getParent() == null ? code : code.getParent();
        } catch (Exception e) {
            // Handled: the code location is unknown: the caller takes null
            return null;
        }
    }

    static List<String> foreignFiles(Iterable<ConfigSource> sources, Path ownRoot) {
        List<String> found = new ArrayList<>();
        for (ConfigSource s : sources) {
            Optional<Path> file = file(s.getName());
            if (file.isEmpty()) continue;
            if (ownRoot != null && file.get().startsWith(ownRoot)) continue;
            // Quarkus's test framework writes its own overrides there (@QuarkusTest, QuarkusTestProfile).
            Path parent = file.get().getParent();
            if (parent != null
                    && parent.getFileName() != null
                    && parent.getFileName().toString().startsWith("startup-override")) continue;
            found.add(file.get().toString());
        }
        return List.copyOf(found);
    }

    /** The configuration files Quarkus read from the working directory ({@code .env}, {@code config/*}). */
    static List<String> workingDirectorySources(Iterable<ConfigSource> sources, Path workingDir) {
        Path cwd = workingDir.toAbsolutePath().normalize();
        List<String> problems = new ArrayList<>();
        for (ConfigSource s : sources) {
            Optional<Path> file = file(s.getName());
            if (file.isEmpty()) continue;
            Path f = file.get();
            boolean dotEnv = f.equals(cwd.resolve(".env"));
            boolean configDir = f.startsWith(cwd.resolve("config"));
            if (dotEnv || configDir) {
                problems.add(f + " sets configuration from the working directory, where a process of the daemon's"
                        + " OS user (an agent) can write. Remove it; configure Floor42 through its environment or"
                        + " -D system properties.");
            }
        }
        return problems;
    }

    static Optional<Path> file(String sourceName) {
        if (sourceName == null) return Optional.empty();
        Matcher m = FILE_SOURCE.matcher(sourceName);
        if (!m.find()) return Optional.empty();
        try {
            return Optional.of(Path.of(URI.create(m.group(1))).toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            // Handled: a source that names no file names no path to check
            return Optional.empty();
        }
    }

    /** The journal's URL as the data folder gives it, no driver property added, no extension loading. */
    static List<String> journalConnection(Config config) {
        List<String> problems = new ArrayList<>();
        String dataDir = config.getValue("floor42.data-dir", String.class);
        String expected = "jdbc:sqlite:" + dataDir + JOURNAL_URL_SUFFIX;
        ConfigValue url = config.getConfigValue("quarkus.datasource.jdbc.url");
        if (!expected.equals(url.getValue())) {
            problems.add("The journal's JDBC URL is set to " + url.getValue() + " by " + url.getSourceName()
                    + ": it must be " + expected + ". Move the journal with FLOOR42_DATA_DIR, never with the URL.");
        }
        for (String name : config.getPropertyNames()) {
            String key = normalized(name);
            if (!key.startsWith("quarkus.datasource.")) continue;
            ConfigValue v;
            try {
                v = config.getConfigValue(name);
            } catch (RuntimeException unresolved) {
                // Handled: a value that does not resolve cannot point the journal anywhere
                continue;
            }
            String value = v.getValue();
            boolean extension = key.contains("load.extension")
                    || (value != null && value.toLowerCase(Locale.ROOT).contains("load_extension"));
            if (extension || key.contains("additional.jdbc.properties")) {
                problems.add(name + " (from " + v.getSourceName()
                        + ") changes how the journal's connection is opened: refused.");
            }
        }
        return problems;
    }

    /** {@code QUARKUS_DATASOURCE_JDBC_URL} and {@code quarkus.datasource.jdbc.url} alike. */
    static String normalized(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", ".");
    }
}
