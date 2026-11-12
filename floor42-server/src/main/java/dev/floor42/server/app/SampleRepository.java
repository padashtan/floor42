package dev.floor42.server.app;

import dev.floor42.server.runtime.Git;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The throwaway repository the guided first mission works on (ADR-0045): created by Floor42 under its own data folder,
 * never one of the director's. Its README is the one the replayed session read when it was recorded, so what the
 * replay shows matches the files on disk. Each rehearsal works in a worktree of its own: the sample stays as created.
 */
@ApplicationScoped
public class SampleRepository {

    static final String FOLDER = "tutorial";
    static final String NAME = "sample-repository";
    /** The README of the recorded session's sandbox, byte for byte what it read (transcripts/inventory-with-notes). */
    static final String README = "# Sandbox project\nUsed by the Office runtime spike.\n";

    private final Git git;
    private final Path dataDir;

    @Inject
    public SampleRepository(Git git, @ConfigProperty(name = "floor42.data-dir") Path dataDir) {
        this.git = git;
        this.dataDir = dataDir;
    }

    /** Where the sample lives, whether it exists yet or not. */
    public Path location() {
        return dataDir.resolve(FOLDER).resolve(NAME).toAbsolutePath().normalize();
    }

    /**
     * The sample, created with one commit the first time; the same folder afterwards. Anything else at its place is
     * refused, never rewritten: a link (it could lead into another repository), a folder holding files without a git
     * of its own, a gitfile, a repository without a commit. The director removes it, then asks again.
     */
    public synchronized Path prepare() {
        Path dir = location();
        refuseLinks(dir);
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            if (isSample(dir)) return dir;
            if (!empty(dir)) throw unusable(dir, "it holds files but is not Floor42's sample repository");
        }
        create(dir);
        return dir;
    }

    /** Its own .git folder, with a commit: a data folder inside a checkout must not make that checkout the sample. */
    private boolean isSample(Path dir) {
        return Files.isDirectory(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
                && git.outIfOk(dir, "rev-parse", "HEAD").isPresent();
    }

    private void refuseLinks(Path dir) {
        for (Path p : List.of(dir.getParent(), dir, dir.resolve(".git"))) {
            if (redirected(p)) throw unusable(dir, p + " is a link");
        }
    }

    /**
     * A symbolic link, or a Windows junction (not a symbolic link to {@link Files#isSymbolicLink}): its attributes say
     * "other", or its real path is not its parent's real path plus its own name.
     */
    static boolean redirected(Path p) {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (a.isSymbolicLink() || a.isOther()) return true;
            Path parent = p.toAbsolutePath().normalize().getParent();
            return !samePath(
                    p.toRealPath(), parent.toRealPath().resolve(p.getFileName().toString()));
        } catch (IOException e) {
            // Handled: a path that cannot be resolved is treated as a link: never written through
            return true;
        }
    }

    /** Windows names files case-insensitively: "Tutorial" and "tutorial" are one folder there, not a link. */
    static boolean samePath(Path a, Path b) {
        return WINDOWS ? a.toString().equalsIgnoreCase(b.toString()) : a.equals(b);
    }

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    private static boolean empty(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + dir, e);
        }
    }

    private static RefusedException unusable(Path dir, String why) {
        return RefusedException.conflict(
                "SAMPLE_UNUSABLE", "The sample repository cannot be used at " + dir + ": " + why);
    }

    private void create(Path dir) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("README.md"), README, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare the sample repository in " + dir, e);
        }
        git.out(dir, "init", "-q");
        git.out(dir, "add", "README.md");
        git.out(
                dir,
                "-c",
                "user.name=Floor42",
                "-c",
                "user.email=floor42@floor42.invalid",
                "-c",
                "commit.gpgsign=false",
                "commit",
                "-q",
                "-m",
                "Sample repository for the guided first mission");
    }
}
