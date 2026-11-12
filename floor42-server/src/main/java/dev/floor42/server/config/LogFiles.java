package dev.floor42.server.config;

import dev.floor42.server.security.OwnerOnly;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The log folder {@code <data-dir>/logs} is readable by its owner alone on Linux and macOS (mode 700, files 600): a log
 * names missions, paths and errors. The folder's mode also covers the files the rotation creates later. On Windows
 * the folder keeps the rights of the data folder, as before.
 */
@ApplicationScoped
public class LogFiles {

    private static final Logger LOG = Logger.getLogger(LogFiles.class);

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    void start(@Observes StartupEvent ignored) {
        restrict(dataDir.resolve("logs"));
    }

    /** Creates the folder if needed and restricts it and its files; does nothing on a non-POSIX file system. */
    static void restrict(Path logs) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return;
        try {
            Files.createDirectories(logs);
            OwnerOnly.restrict(logs);
            try (Stream<Path> files = Files.list(logs)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) OwnerOnly.restrict(f);
            }
        } catch (IOException e) {
            // Handled: logging goes on with the rights it has; said once
            LOG.warnf("Cannot restrict the log folder %s to its owner: %s", logs, e.getMessage());
        }
    }
}
