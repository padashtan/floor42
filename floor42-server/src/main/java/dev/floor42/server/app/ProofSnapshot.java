package dev.floor42.server.app;

import dev.floor42.domain.DomainException;
import dev.floor42.server.runtime.Git;
import dev.floor42.server.runtime.GitProcess;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The code a proof is about, and the throwaway copy it runs on (ADR-0058): the worktree's commit, a digest of the work
 * not committed, and a copy of the files git lists (tracked, and new files not ignored) into a folder Floor42 owns.
 * The steps never touch the real worktree. Nothing is read whole into memory: digests are streamed, and a file past
 * {@link #HASHED_BYTES} is named by its size and time instead of its content.
 */
@ApplicationScoped
public class ProofSnapshot {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(ProofSnapshot.class);

    static final int MAX_FILES = 20_000;
    static final long MAX_BYTES = 500L * 1024 * 1024;
    /** A new file larger than this is digested by its path, size and last change, not its content. */
    static final long HASHED_BYTES = 16L * 1024 * 1024;

    /** The code as it is: its commit, and what is not committed. */
    public record Code(String head, String changesDigest) {}

    /**
     * A copy made for a run, and the files left out of it with why ({@link ProofCopyRules}).
     *
     * @param skipped the first {@link ProofCopyRules#SHOWN} left out, as {@code reason: path}
     * @param skippedCount every file left out
     */
    public record Copy(Path folder, List<String> skipped, int skippedCount) {
        public Copy {
            skipped = skipped == null ? null : Collections.unmodifiableList(new ArrayList<>(skipped));
        }
    }

    @Inject
    Git git;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    /** The worktree's commit and a digest of its uncommitted changes and new files; nothing is written there. */
    public Code code(Path worktree) {
        String head = git.out(worktree, "rev-parse", "HEAD").strip();
        MessageDigest sha = sha();
        Path diff = scratch("diff");
        try {
            git.toFile(worktree, List.of("diff", "--binary", "--no-ext-diff", "HEAD"), diff);
            stream(sha, diff);
        } finally {
            ProofFolders.delete(diff);
        }
        for (String p : new TreeSet<>(listed(worktree, "--others"))) {
            sha.update((p + "\u0000").getBytes(StandardCharsets.UTF_8));
            digestFile(sha, worktree.resolve(p));
        }
        return new Code(head, HexFormat.of().formatHex(sha.digest()).substring(0, 16));
    }

    /** A fresh folder under Floor42's data folder holding the worktree's files as they are now. */
    public Copy copy(Path worktree, String name) {
        // The folder is named from the name: no separator, no climbing, whatever the caller derived it from.
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new DomainException("INVALID_PROOF_NAME", "A proof copy is named without separators or '..'");
        }
        Path root = real(worktree);
        List<String> files = listed(worktree, "--cached", "--others");
        if (files.size() > MAX_FILES)
            throw new DomainException("WORKTREE_TOO_LARGE", "More than " + MAX_FILES + " files to copy");
        ProofCopyRules.Plan plan = ProofCopyRules.plan(root, files);
        if (plan.bytes() > MAX_BYTES)
            throw new DomainException("WORKTREE_TOO_LARGE", "More than " + MAX_BYTES + " bytes to copy");
        Path into = dataDir.resolve("proofs").resolve(name + "-" + System.nanoTime());
        try {
            Files.createDirectories(into);
            for (String p : plan.copied()) {
                Path target = into.resolve(p).normalize();
                Files.createDirectories(target.getParent());
                Files.copy(root.resolve(p), target, LinkOption.NOFOLLOW_LINKS);
            }
        } catch (IOException e) {
            ProofFolders.delete(into);
            throw new UncheckedIOException("Cannot copy the worktree for the proof", e);
        } catch (RuntimeException e) {
            ProofFolders.delete(into);
            throw e;
        }
        List<String> shown = plan.skipped().stream().limit(ProofCopyRules.SHOWN).toList();
        return new Copy(into, shown, plan.skipped().size());
    }

    /** A copy left by a run the daemon never ended (it stopped, or was killed) is of no use: all are deleted. */
    void clean(@Observes StartupEvent ignored) {
        clean(dataDir.resolve("proofs"));
    }

    /** Never throws and never follows a link; only the copy folders go (a scratch file belongs to a request). */
    static void clean(Path root) {
        try {
            // A link or junction at the folder resolves elsewhere than beside its parent: nothing is deleted through
            // it.
            Path real = root.toRealPath();
            if (!real.equals(root.toAbsolutePath().getParent().toRealPath().resolve(root.getFileName()))
                    || !Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                            .isDirectory()) return;
            try (var left = Files.list(root)) {
                left.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                        .forEach(ProofFolders::delete);
            }
        } catch (NoSuchFileException e) {
            // Handled: no proof was ever run here
        } catch (IOException | RuntimeException e) {
            // Handled: leftovers are only disk space; the daemon starts anyway
            LOG.warnf("Cannot clean the proof copies in %s: %s", root, e.getMessage());
        }
    }

    private List<String> listed(Path worktree, String... which) {
        List<String> args = new ArrayList<>(List.of("ls-files", "-z"));
        args.addAll(List.of(which));
        args.add("--exclude-standard");
        return GitProcess.paths(git.out(worktree, java.util.Set.of(0), args));
    }

    /** A plain file: its content streamed, or past the cap its size and last change; a link or folder: its kind. */
    static void digestFile(MessageDigest sha, Path f) {
        try {
            BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile()) {
                sha.update("not a file".getBytes(StandardCharsets.UTF_8));
            } else if (a.size() > HASHED_BYTES) {
                sha.update(
                        ("size " + a.size() + " changed " + a.lastModifiedTime().toMillis())
                                .getBytes(StandardCharsets.UTF_8));
            } else {
                stream(sha, f);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + f.getFileName(), e);
        }
    }

    private static void stream(MessageDigest sha, Path f) {
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(f, LinkOption.NOFOLLOW_LINKS)) {
            int n;
            while ((n = in.read(buffer)) > 0) sha.update(buffer, 0, n);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + f.getFileName(), e);
        }
    }

    private Path scratch(String what) {
        try {
            Path dir = Files.createDirectories(dataDir.resolve("proofs"));
            return Files.createTempFile(dir, what + "-", ".bin");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write in the data folder", e);
        }
    }

    private static Path real(Path worktree) {
        try {
            return worktree.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the worktree", e);
        }
    }

    private static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }
}
