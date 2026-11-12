package dev.floor42.server.app;

import dev.floor42.server.security.LogSafe;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Deletes a proof's copy (ADR-0058) without ever following a link: a symbolic link or a junction a step left in it is
 * removed itself, never what it points to. What cannot be deleted is logged and left under Floor42's data folder.
 */
final class ProofFolders {

    private static final Logger LOG = Logger.getLogger(ProofFolders.class);

    private ProofFolders() {}

    /** Deletes {@code path} and, for a real folder, what it holds; returns what was left behind. */
    static List<Path> delete(Path path) {
        List<Path> left = new ArrayList<>();
        if (path != null) remove(path, left);
        if (!left.isEmpty())
            LOG.warnf(
                    "A proof's copy was not deleted whole: %d item(s) left, first %s",
                    left.size(), LogSafe.of(left.getFirst()));
        return left;
    }

    private static void remove(Path p, List<Path> left) {
        BasicFileAttributes a;
        try {
            a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            // Handled: nothing there (or unreadable, then counted below when it is still there)
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) left.add(p);
            return;
        }
        // A junction reads as a folder that is also "other" (a reparse point): it is unlinked, never entered.
        boolean link = a.isSymbolicLink() || a.isOther();
        if (a.isDirectory() && !link) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(p)) {
                for (Path child : children) remove(child, left);
            } catch (IOException e) {
                // Handled: the folder is kept and named in the log
                left.add(p);
                return;
            }
        }
        try {
            Files.delete(p);
        } catch (IOException e) {
            // Handled: named in the log by delete
            left.add(p);
        }
    }
}
