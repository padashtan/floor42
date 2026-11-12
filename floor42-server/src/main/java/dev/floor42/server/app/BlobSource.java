package dev.floor42.server.app;

import dev.floor42.server.app.Delivered.Blob;
import dev.floor42.server.runtime.ConfinedPath;
import dev.floor42.server.runtime.Confinement;
import dev.floor42.server.runtime.Git;
import dev.floor42.server.runtime.GitFailure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * The bytes of one delivered file, only when the listing would name it: a committed one through git ({@code cat-file}
 * at the branch tip Floor42 resolved itself), a worktree one when git reports it as not committed and its real path
 * stays inside the worktree. Links are never followed; {@code .git} is never read.
 */
@ApplicationScoped
public class BlobSource {

    private final Git git;
    private final GitReader history;
    private final WorktreeScanner scanner;

    @Inject
    public BlobSource(Git git, GitReader history, WorktreeScanner scanner) {
        this.git = git;
        this.history = history;
        this.scanner = scanner;
    }

    Blob fromWorktree(ResolvedBranch r, String path) {
        if (!r.present()) throw RefusedException.conflict("WORKTREE_GONE", "The worktree of " + r.agent() + " is gone");
        ConfinedPath confined = Confinement.relative(r.worktree(), path, ConfinedPath.Mode.READ);
        String relative = confined.relative();
        if (confined.isRoot()) throw RefusedException.invalid("NOT_A_FILE", "the worktree itself is not a file");
        // Only what git reports as not committed: never a file of .git, never the rest of the disk.
        if (!scanner.uncommitted(confined.root(), relative)) {
            throw RefusedException.invalid(
                    "NOT_A_DELIVERABLE", relative + " is not a file of the worktree that was left uncommitted");
        }
        return onDisk(confined.root(), confined.file(), relative);
    }

    /**
     * The file checked, then opened once: every byte later read comes from that open channel ({@link BlobBytes}), and
     * the checks run again once it is open, so a link or junction put in its place in between is refused too.
     */
    static Blob onDisk(Path root, Path file, String relative) {
        try {
            confined(root, file, relative);
            Blob blob = BlobBytes.open(file.getFileName().toString(), file, false);
            try {
                confined(root, file, relative);
            } catch (RuntimeException e) {
                blob.release();
                throw e;
            }
            return blob;
        } catch (IOException e) {
            throw RefusedException.conflict("UNREADABLE", "Cannot read " + relative + ": " + e.getMessage(), e);
        }
    }

    private static void confined(Path root, Path file, String relative) throws IOException {
        if (Files.isSymbolicLink(file) || WorktreeScanner.isJunction(file, root)) {
            throw RefusedException.invalid("LINK_REFUSED", relative + " is a link: Floor42 does not follow it");
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw RefusedException.invalid("NOT_A_FILE", relative + " is not a file");
        }
        if (!file.toRealPath().startsWith(root.toRealPath())) {
            throw RefusedException.invalid("OUTSIDE_WORKSPACE", relative + " leads outside the worktree");
        }
    }

    /** {@code check}: refuse a path the branch did not add or change since the mission began. */
    Blob fromBranch(ResolvedBranch r, String path, boolean check) {
        if (!r.readable()) {
            throw RefusedException.conflict("BRANCH_UNREADABLE", "git cannot read the branch of " + r.agent());
        }
        String wanted = path.replace('\\', '/');
        boolean listed = !check
                || history.committedFiles(r.gitDir(), r.start(), r.tip()).stream()
                        .anyMatch(f -> f.path().equals(wanted) && !f.status().equals("D"));
        if (!listed) {
            throw RefusedException.invalid(
                    "NOT_A_DELIVERABLE", wanted + " is not a file the branch added or changed since the mission began");
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("floor42-deliverable-", ".bin"); // NOSONAR owner-only temp
            git.toFile(r.gitDir(), List.of("cat-file", "blob", r.tip() + ":" + wanted), tmp);
            String name = wanted.contains("/") ? wanted.substring(wanted.lastIndexOf('/') + 1) : wanted;
            return BlobBytes.open(name, tmp, true);
        } catch (GitFailure e) {
            discard(tmp);
            throw RefusedException.conflict("BRANCH_UNREADABLE", "git could not read " + wanted + ": " + e.stderr(), e);
        } catch (IOException e) {
            discard(tmp);
            throw RefusedException.conflict("BRANCH_UNREADABLE", "Cannot read " + wanted + ": " + e.getMessage(), e);
        }
    }

    private static void discard(Path tmp) {
        if (tmp != null) new Blob("", tmp, true, 0, new byte[0]).release();
    }
}
