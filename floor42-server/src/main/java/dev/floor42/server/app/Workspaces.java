package dev.floor42.server.app;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.server.config.DataFolders;
import dev.floor42.server.runtime.Git;
import dev.floor42.server.runtime.GitFailure;
import dev.floor42.server.runtime.GitProcess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Where agents work: never in the director's checkout, but in a git worktree of the mission's repository, one per
 * mission and producing agent, on its own branch. The director's files and branches are never touched; the worktree
 * stays afterwards as evidence of what the agent saw and did.
 *
 * <p>Git that must succeed (making a worktree, a commit) throws a {@link GitFailure}; a read answers empty, -1 or null
 * when git answers that it cannot (no such commit, not a repository), as each method says.
 */
@ApplicationScoped
public class Workspaces {

    private static final Logger LOG = Logger.getLogger(Workspaces.class);
    /** An agent id as a folder suffix: a valid identifier, never a leftover such as "chris.displaced-1". */
    private static final String AGENT_NAME = "[A-Za-z0-9_:-][A-Za-z0-9._:-]{0,127}";

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{40,64}");
    private static final List<String> FLOOR42_IDENTITY =
            List.of("-c", "user.name=Floor42", "-c", "user.email=floor42@floor42.invalid");

    private final Git git;
    private final Path dataDir;
    private final WorkspaceDisplacement displacement;

    @Inject
    public Workspaces(Git git, @ConfigProperty(name = "floor42.data-dir") Path dataDir) {
        this.git = git;
        this.dataDir = dataDir;
        this.displacement = new WorkspaceDisplacement(git, dataDir);
    }

    /** True when no session process of a previous daemon is recorded any more (see {@link StaleIndexLocks}). */
    boolean previousSessionsStopped() {
        return StaleIndexLocks.sessionsStopped(
                dataDir.resolve(dev.floor42.server.runtime.ClaudeCodeRuntime.SESSIONS_DIR));
    }

    /** Refuses (400 {@code INVALID_REPOSITORY}) a directory that is not inside a git work tree. */
    public void requireRepository(String repository) {
        Path dir = Path.of(repository);
        if (!Files.isDirectory(dir))
            throw RefusedException.invalid("INVALID_REPOSITORY", "Not a directory: " + repository);
        GitProcess.Result r = git.run(dir, "rev-parse", "--is-inside-work-tree");
        if (r.timedOut() || r.code() != 0 || !r.out().strip().equals("true")) {
            String why = r.said().isBlank() ? "" : " (" + r.said() + ")";
            throw RefusedException.invalid("INVALID_REPOSITORY", "Not a git repository: " + repository + why);
        }
    }

    /** Creates (or reuses) the worktree of this mission and agent, branched from the repository's current HEAD. */
    public Path worktree(String repository, MissionId mission, AgentId agent) {
        return worktree(repository, mission, agent, p -> true);
    }

    /**
     * As above, but a folder is reused only when it is a worktree of this repository and {@code journalKnows} it: a
     * folder left by another timeline (a restore to an older copy) is moved to {@code workspaces/.displaced}, never
     * deleted, and a fresh worktree is made.
     */
    public Path worktree(
            String repository, MissionId mission, AgentId agent, java.util.function.Predicate<Path> journalKnows) {
        Path target = location(mission, agent);
        if (exists(target)) {
            if (displacement.sameRepository(repository, target) && journalKnows.test(target)) return target;
            displacement.displace(target);
            // git still lists the old folder, which is gone from there: forget it so the name can be used again.
            git.run(Path.of(repository), "worktree", "prune");
        }
        String base = "floor42/" + mission.value().toLowerCase(Locale.ROOT) + "-" + agent.value();
        // A branch of that name may be left by another Floor42 data directory: never reuse it, it may hold work.
        String branch = base;
        for (int n = 2; branchExists(repository, branch); n++) branch = base + "-" + n;
        git.out(Path.of(repository), "worktree", "add", "-b", branch, target.toString(), "HEAD");
        return target;
    }

    private boolean branchExists(String repository, String branch) {
        return git.resolve(Path.of(repository), "refs/heads/" + branch).isPresent();
    }

    /**
     * A new, empty git repository for a project that does not exist yet (an idea committee), under Floor42's data
     * directory: nothing of the director's is touched. One empty commit gives worktrees a base.
     */
    public Path newProject(String title) {
        String slug =
                title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(?:^-)|(?:-$)", "");
        if (slug.isEmpty()) slug = "project";
        if (slug.length() > 40) slug = slug.substring(0, 40);
        Path dir = dataDir.resolve("projects").resolve(slug).toAbsolutePath();
        for (int n = 2; Files.exists(dir); n++) {
            dir = dataDir.resolve("projects").resolve(slug + "-" + n).toAbsolutePath();
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // Reported to the director as a repository Floor42 could not prepare (400), the cause kept for the log.
            throw new IllegalArgumentException("Cannot create " + dir + ": " + e.getMessage(), e);
        }
        git.out(dir, "init", "-q");
        git.out(
                dir,
                "-c",
                "user.name=Floor42",
                "-c",
                "user.email=floor42@localhost",
                "commit",
                "-q",
                "--allow-empty",
                "-m",
                "New project: " + title);
        return dir;
    }

    /** An empty directory for a research session: no repository, no file of the director's (ADR-0018). */
    public Path research(MissionId mission) {
        Path dir = dataDir.resolve("workspaces")
                .resolve(mission.value() + "-research")
                .toAbsolutePath();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create " + dir, e);
        }
        return dir;
    }

    /** The worktree of this mission and agent if it was created, whether or not the journal knows it yet. */
    public Optional<Path> existing(MissionId mission, AgentId agent) {
        Path target = location(mission, agent);
        return exists(target) ? Optional.of(target) : Optional.empty();
    }

    /** Every worktree made for this mission, by agent name, in name order: what is on disk, journaled or not. */
    public SortedMap<String, Path> worktrees(MissionId mission) {
        SortedMap<String, Path> found = new TreeMap<>();
        // Office's former folder first, so that a worktree of the same name in the data folder wins.
        DataFolders.legacy(dataDir).ifPresent(old -> list(old.resolve("workspaces"), mission, found));
        list(dataDir.resolve("workspaces"), mission, found);
        return found;
    }

    private static void list(Path root, MissionId mission, SortedMap<String, Path> found) {
        String prefix = mission.value() + "-";
        if (!Files.isDirectory(root)) return;
        try (var dirs = Files.list(root)) {
            dirs.filter(d -> d.getFileName().toString().startsWith(prefix) && exists(d))
                    .filter(d -> d.getFileName()
                            .toString()
                            .substring(prefix.length())
                            .matches(AGENT_NAME))
                    .forEach(d -> found.put(d.getFileName().toString().substring(prefix.length()), d.toAbsolutePath()));
        } catch (IOException e) {
            // Handled: an unreadable folder lists no branch rather than a guess; the log keeps why.
            LOG.debugf("Cannot list the worktrees in %s: %s", root, e.getMessage());
        }
    }

    /**
     * Where the worktree of this mission and agent is: in the data folder, or, made before Office became Floor42, in
     * Office's former folder (git knows it there by its absolute path: it is not moved). A new one goes in the data
     * folder.
     */
    private Path location(MissionId mission, AgentId agent) {
        String name = mission.value() + "-" + agent.value();
        Path current = dataDir.resolve("workspaces").resolve(name).toAbsolutePath();
        if (exists(current)) return current;
        return DataFolders.legacy(dataDir)
                .map(old -> old.resolve("workspaces").resolve(name))
                .filter(Workspaces::exists)
                .orElse(current);
    }

    static boolean exists(Path worktree) {
        return Files.isDirectory(worktree.resolve(".git")) || Files.isRegularFile(worktree.resolve(".git"));
    }

    /** What merging other branches into a worktree gave: clean, or the conflict that undid it. */
    public record Merge(boolean clean, String detail) {}

    /**
     * Merges each branch into the worktree's own, in order, with a merge commit. On a conflict, that merge is undone
     * (the worktree is back as it was before it) and the conflicting files are named; the branches merged before it
     * stay merged.
     */
    @SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop") // the first conflict ends the merge
    public Merge merge(Path worktree, List<String> branches) {
        List<String> identity = identity(worktree);
        for (String branch : branches) {
            List<String> args = new ArrayList<>(identity);
            // --end-of-options: a branch name is never read as an option of git's.
            args.addAll(List.of(
                    "merge", "--no-edit", "--no-ff", "-m", "Floor42 merges " + branch, "--end-of-options", branch));
            GitProcess.Result r = git.run(worktree, args);
            if (r.code() == 0 && !r.timedOut()) continue;
            String files = String.join(
                    " ",
                    GitProcess.paths(git.outIfOk(worktree, "diff", "--name-only", "-z", "--diff-filter=U")
                            .orElse("")));
            git.run(worktree, "merge", "--abort");
            String said = r.timedOut() ? "git merge timed out" : r.said();
            return new Merge(false, "conflict with " + branch + (files.isBlank() ? ": " + said : " in " + files));
        }
        return new Merge(true, null);
    }

    /**
     * Takes the worktree back to {@code before}, keeping its uncommitted changes: a merge the journal could not
     * record does not stay.
     */
    public void undoMerge(Path worktree, String before) {
        if (before == null || !HASH.matcher(before).matches()) return;
        GitProcess.Result r = git.run(worktree, "reset", "--keep", before);
        if (r.code() != 0) LOG.warnf("Could not undo the merge in %s: %s", worktree, r.said());
    }

    /**
     * The paths git reports as changed or new in {@code dir}, untracked folders listed file by file; a renamed file
     * is listed under its new name, then its former one (committing the rename needs both). Empty when git cannot
     * read the folder.
     */
    public List<String> pending(Path dir) {
        List<String> paths = new ArrayList<>();
        for (GitProcess.StatusEntry e : git.status(dir, "--untracked-files=all")) {
            paths.add(e.path());
            if (e.from() != null && e.code().charAt(0) == 'R') paths.add(e.from());
        }
        return paths;
    }

    /**
     * The same paths as {@link #pending}, or empty when git did not answer (a failure, a time-out, a git that cannot
     * start): a failed call is never read as a clean worktree (ADR-0055).
     */
    public Optional<List<String>> pendingIfKnown(Path dir) {
        GitProcess.Result r;
        try {
            r = git.run(dir, "status", "--porcelain=v1", "-z", "--untracked-files=all");
        } catch (GitFailure e) {
            // Handled: git could not run; the state is unknown, said so to the caller, never taken for clean
            LOG.debugf("git status failed in %s: %s", dir, e.getMessage());
            return Optional.empty();
        }
        if (r.timedOut() || r.code() != 0) return Optional.empty();
        List<String> paths = new ArrayList<>();
        for (GitProcess.StatusEntry e : GitProcess.status(r.out())) {
            paths.add(e.path());
            if (e.from() != null && e.code().charAt(0) == 'R') paths.add(e.from());
        }
        return Optional.of(paths);
    }

    /**
     * The files git ignores in {@code dir} ({@code .gitignore}, {@code info/exclude}): in no commit, so no merge, push
     * or pull request carries them (ADR-0039). Empty when git cannot read the folder.
     */
    public List<String> ignored(Path dir) {
        return git.outIfOk(dir, "ls-files", "-z", "--others", "--ignored", "--exclude-standard")
                .map(GitProcess::paths)
                .orElse(List.of());
    }

    /**
     * Commits {@code paths} (every pending change when null) on the current branch of {@code dir}, with the
     * repository's own identity, or Floor42's when it has none; returns the new commit's hash.
     */
    public String commit(Path dir, List<String> paths, String message) {
        List<String> add = new ArrayList<>(List.of("add", "-A", "--"));
        add.addAll(paths == null ? List.of(".") : paths);
        git.out(dir, add.toArray(String[]::new));
        List<String> args = new ArrayList<>(identity(dir));
        args.addAll(List.of("commit", "-q", "-m", message));
        if (paths != null) {
            args.add("--");
            args.addAll(paths);
        }
        git.out(dir, args.toArray(String[]::new));
        return head(dir);
    }

    /** No identity of its own in the repository: Floor42's neutral one, never the director's name made up. */
    private List<String> identity(Path dir) {
        return git.outIfOk(dir, "config", "user.email").orElse("").isBlank() ? FLOOR42_IDENTITY : List.of();
    }

    /** True when {@code commit} is already part of the worktree's history. */
    public boolean contains(Path worktree, String commit) {
        return git.run(worktree, "merge-base", "--is-ancestor", "--end-of-options", commit, "HEAD")
                        .code()
                == 0;
    }

    /**
     * What git says of a repository right now, read only (W2): its branch, last commit and how many files have
     * uncommitted changes.
     *
     * @param branch empty on a detached HEAD
     * @param commit null in a repository without commits
     */
    public record RepositoryFacts(String branch, String commit, String subject, String committedAt, int uncommitted) {}

    /** Empty when the folder is not a git work tree or git cannot read it. */
    public Optional<RepositoryFacts> facts(Path dir) {
        if (!Files.isDirectory(dir)) return Optional.empty();
        boolean inside = git.outIfOk(dir, "rev-parse", "--is-inside-work-tree")
                .map(o -> o.strip().equals("true"))
                .orElse(false);
        if (!inside) return Optional.empty();
        String branch = git.outIfOk(dir, "branch", "--show-current").orElse("").strip();
        String[] last = git.outIfOk(dir, "log", "-1", "--format=%h%x1f%s%x1f%cI")
                .map(o -> o.strip().split("\u001f", 3))
                .orElse(new String[0]);
        int uncommitted = git.status(dir).size();
        boolean known = last.length == 3;
        return Optional.of(new RepositoryFacts(
                branch, known ? last[0] : null, known ? last[1] : null, known ? last[2] : null, uncommitted));
    }

    /** One commit as git records it (W6). {@code at} is the committer date, ISO 8601. */
    public record CommitLine(String hash, String subject, String author, String at) {}

    /**
     * The newest commits of {@code range} in {@code dir} (a revision such as {@code HEAD}, or {@code a..b}), at most
     * {@code max}; empty when git cannot read them.
     */
    public List<CommitLine> log(Path dir, String range, int max) {
        return git.outIfOk(dir, "log", "-" + max, "--format=%h%x1f%s%x1f%an%x1f%cI", "--end-of-options", range, "--")
                .orElse("")
                .lines()
                .filter(l -> !l.isBlank())
                .map(l -> l.split("\u001f", 4))
                .filter(p -> p.length == 4)
                .map(p -> new CommitLine(p[0], p[1], p[2], p[3]))
                .toList();
    }

    /**
     * One commit of the git graph: its full hash, its parents' full hashes (first parent first) and the author date,
     * ISO 8601.
     */
    public record GraphCommit(String hash, List<String> parents, String subject, String author, String at) {
        public GraphCommit {
            parents = parents == null ? null : Collections.unmodifiableList(new ArrayList<>(parents));
        }
    }

    /**
     * The commits reachable from {@code revisions} (full hashes only: no ref name reaches the command line), children
     * before their parents (topological order), at most {@code max}; empty when git cannot read them.
     */
    public List<GraphCommit> graph(Path dir, List<String> revisions, int max) {
        List<String> revs = revisions.stream()
                .filter(r -> r != null && HASH.matcher(r).matches())
                .distinct()
                .toList();
        if (revs.isEmpty()) return List.of();
        List<String> args =
                new ArrayList<>(List.of("log", "--topo-order", "-" + max, "--format=%H%x1f%P%x1f%s%x1f%an%x1f%aI"));
        args.add("--end-of-options");
        args.addAll(revs);
        args.add("--");
        return git.outIfOk(dir, args.toArray(String[]::new))
                .orElse("")
                .lines()
                .map(l -> l.split("\u001f", 5))
                .filter(p -> p.length == 5 && HASH.matcher(p[0]).matches())
                .map(p -> new GraphCommit(
                        p[0], p[1].isBlank() ? List.of() : List.of(p[1].strip().split(" ")), p[2], p[3], p[4].strip()))
                .toList();
    }

    /** The paths {@code range} (base..head) changes; empty when git cannot say. */
    public List<String> changedPaths(Path dir, String range) {
        return git.outIfOk(dir, "diff", "--name-only", "-z", "--end-of-options", range, "--")
                .map(GitProcess::paths)
                .orElse(List.of());
    }

    /** How many commits {@code range} holds, or -1 when git cannot count them. */
    public int count(Path dir, String range) {
        String out = git.outIfOk(dir, "rev-list", "--count", "--end-of-options", range, "--")
                .orElse("")
                .strip();
        // Handled: anything but a number (nothing, a warning) is "cannot count", as documented.
        return out.matches("\\d{1,9}") ? Integer.parseInt(out) : -1;
    }

    /** The full hash {@code revision} names in {@code dir}, or null. */
    public String resolve(Path dir, String revision) {
        return git.resolve(dir, revision).orElse(null);
    }

    public String branch(Path worktree) {
        return git.out(worktree, "branch", "--show-current").strip();
    }

    public String head(Path worktree) {
        return git.out(worktree, "rev-parse", "HEAD").strip();
    }
}
