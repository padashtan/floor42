package dev.floor42.server.app;

import dev.floor42.server.app.Delivered.DeliveredFile;
import dev.floor42.server.runtime.Git;
import dev.floor42.server.runtime.GitProcess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * What a worktree holds that was never committed, as git reports it: changed, new, deleted, and ignored files when
 * asked. Links and junctions are listed, never followed.
 */
@ApplicationScoped
public class WorktreeScanner {

    private static final Logger LOG = Logger.getLogger(WorktreeScanner.class);

    /** What a scan found; {@code ignoredHidden} counts ignored entries left out because they were not asked for. */
    public record Scan(List<DeliveredFile> files, int ignoredHidden, boolean truncated) {
        public Scan {
            files = files == null ? null : Collections.unmodifiableList(new ArrayList<>(files));
        }
    }

    private final Git git;

    @Inject
    public WorktreeScanner(Git git) {
        this.git = git;
    }

    public Scan scan(Path worktree, boolean ignored, int max) {
        List<GitProcess.StatusEntry> entries = ignored
                ? git.status(worktree, "--untracked-files=all", "--ignored=traditional")
                : git.status(worktree, "--untracked-files=all");
        Path root = worktree.toAbsolutePath().normalize();
        List<DeliveredFile> files = new ArrayList<>();
        boolean truncated = false;
        for (GitProcess.StatusEntry e : entries) {
            if (files.size() >= max) {
                truncated = true;
                break;
            }
            if (e.path().endsWith("/")) continue; // an ignored folder git did not open
            files.add(file(root, e));
        }
        int hidden = ignored
                ? 0
                : (int) git.status(worktree, "--ignored=traditional").stream()
                        .filter(s -> s.code().equals("!!"))
                        .count();
        return new Scan(List.copyOf(files), hidden, truncated);
    }

    private static DeliveredFile file(Path root, GitProcess.StatusEntry e) {
        String code = e.code();
        String status = code.equals("??") ? "?" : code.equals("!!") ? "!" : status(code);
        Path file = root.resolve(e.path());
        boolean link = Files.isSymbolicLink(file) || isJunction(file, root);
        Long size = status.equals("D") || link ? null : size(file);
        return new DeliveredFile(
                e.path(), e.from(), status, size, null, false, status.equals("!"), link, FileKinds.kind(e.path()));
    }

    private static Long size(Path file) {
        try {
            return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? Files.size(file) : null;
        } catch (IOException e) {
            // Handled: the file is listed with an unknown size, as the view documents.
            LOG.debugf("Size of %s unknown: %s", file, e.getMessage());
            return null;
        }
    }

    /** True when git reports {@code relative} as not committed (and not deleted) in the worktree. */
    public boolean uncommitted(Path root, String relative) {
        return git.status(root, "--untracked-files=all", "--ignored=traditional", "--", relative).stream()
                .anyMatch(e -> e.path().equals(relative) && !status(e.code()).equals("D"));
    }

    /** The one-letter status of a porcelain code: D, R, A or M. */
    static String status(String code) {
        String c = code.strip();
        if (c.contains("D")) return "D";
        if (c.startsWith("R")) return "R";
        if (c.startsWith("A")) return "A";
        return "M";
    }

    /** A Windows junction (or any reparse point) leading elsewhere: its real path leaves the folder it is listed in. */
    static boolean isJunction(Path file, Path root) {
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
            Path real = file.toRealPath();
            Path expected = root.toRealPath()
                    .resolve(root.relativize(file.toAbsolutePath().normalize()));
            return !real.equals(expected.normalize());
        } catch (IOException e) {
            // Handled: what cannot be resolved is treated as a link, so it is listed and never read.
            return true;
        }
    }
}
