package dev.floor42.server.app;

import dev.floor42.server.config.DataFolders;
import dev.floor42.server.runtime.Git;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Makes a folder of the director's a repository a mission can work on, on their click: {@code git init} when it is
 * none, then a first commit holding the files already there. A preview says what it would do first, changing nothing.
 */
@ApplicationScoped
public class ProjectInit {

    private static final Logger LOG = Logger.getLogger(ProjectInit.class);

    /** Above this many files, Floor42 refuses to make a folder a repository: the director picked the wrong folder. */
    public static final int MAX_INIT_FILES = 5_000;

    /**
     * What making {@code dir} a repository would do, changing nothing.
     *
     * @param repository the folder already is in a git work tree
     * @param hasCommit that work tree has a commit (worktrees need one)
     * @param files the files the first commit would hold, outside {@code .git} (counted up to {@link #MAX_INIT_FILES}
     *     + 1)
     * @param refused why Floor42 will not do it, or null
     */
    public record InitPreview(String path, boolean repository, boolean hasCommit, int files, String refused) {}

    private final Git git;
    private final Workspaces workspaces;
    private final Path dataDir;

    @Inject
    public ProjectInit(Git git, Workspaces workspaces, @ConfigProperty(name = "floor42.data-dir") Path dataDir) {
        this.git = git;
        this.workspaces = workspaces;
        this.dataDir = dataDir;
    }

    public InitPreview preview(Path dir) {
        Path d = dir.toAbsolutePath().normalize();
        boolean repository = workspaces.facts(d).isPresent();
        boolean hasCommit = repository && git.resolve(d, "HEAD").isPresent();
        int files = Files.isDirectory(d) ? countFiles(d) : 0;
        return new InitPreview(d.toString(), repository, hasCommit, files, refusal(d, repository, hasCommit, files));
    }

    /**
     * Makes {@code dir} a repository: {@code git init} when it is none, then a first commit holding the files already
     * there (an empty commit in an empty folder). A git that fails is a {@code GitFailure}, refused as INIT_REFUSED by
     * the resource.
     */
    public Workspaces.RepositoryFacts init(Path dir) {
        InitPreview p = preview(dir);
        if (p.refused() != null) {
            // A repository that already has commits is a state; any other refusal is a folder Floor42 will not take.
            throw p.repository() && p.hasCommit()
                    ? RefusedException.conflict("INIT_REFUSED", p.refused())
                    : RefusedException.invalid("INIT_REFUSED", p.refused());
        }
        Path d = Path.of(p.path());
        if (!p.repository()) git.out(d, "init", "-q");
        git.out(d, "add", "-A");
        git.out(
                d,
                "-c",
                "user.name=Floor42",
                "-c",
                "user.email=floor42@localhost",
                "commit",
                "-q",
                "--allow-empty",
                "-m",
                p.files() == 0 ? "Start of the project" : "Start of the project: the files already there");
        return workspaces
                .facts(d)
                .orElseThrow(
                        () -> RefusedException.conflict("INIT_REFUSED", "git did not make " + d + " a repository"));
    }

    private String refusal(Path d, boolean repository, boolean hasCommit, int files) {
        if (!Files.isDirectory(d)) return "Not a folder: " + d;
        if (repository && hasCommit) return d + " already is a git repository with commits";
        if (d.getParent() == null) return d + " is the root of a drive: choose or create a project folder";
        String home = System.getProperty("user.home");
        if (home != null && d.equals(Path.of(home).toAbsolutePath().normalize())) {
            return d + " is your home folder: choose or create a project folder in it";
        }
        for (Path own : DataFolders.guarded(dataDir)) {
            if (d.startsWith(own.toAbsolutePath().normalize())) return d + " is inside Floor42's own data";
        }
        if (files > MAX_INIT_FILES) {
            return d + " holds more than " + MAX_INIT_FILES + " files: is this really the project folder?";
        }
        return null;
    }

    private static int countFiles(Path d) {
        try (var walk = Files.walk(d)) {
            return (int) walk.filter(p -> !p.equals(d))
                    .filter(p -> !d.relativize(p).startsWith(".git"))
                    .filter(Files::isRegularFile)
                    .limit(MAX_INIT_FILES + 1L)
                    .count();
        } catch (IOException | UncheckedIOException e) {
            // Handled: a folder Floor42 cannot walk whole is refused as too large, never committed half read.
            LOG.debugf("Cannot count the files of %s: %s", d, e.getMessage());
            return MAX_INIT_FILES + 1;
        }
    }
}
