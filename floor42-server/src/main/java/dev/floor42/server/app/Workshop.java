package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Workspace;
import dev.floor42.server.runtime.ConfinedPath;
import dev.floor42.server.runtime.Confinement;
import dev.floor42.server.runtime.Git;
import dev.floor42.server.runtime.GitProcess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The file side of the director's workshop (spec section 11, ADR-0013): Floor42 runs git in an agent's worktree on the
 * director's behalf, then records what it did. Every path goes through the same confinement as agents' file tools, so
 * the workshop cannot reach the director's checkout, nor git's own data. A git that fails is a {@code GitFailure}.
 */
@ApplicationScoped
public class Workshop {

    private static final Logger LOG = Logger.getLogger(Workshop.class);

    /** One pending change of a workspace, relative to its last commit. */
    public record Change(String path, String status, Integer added, Integer removed) {}

    static final int MAX_FILE_BYTES = 512 * 1024;
    static final int MAX_RECORDED_DIFF = 20_000;
    static final int MAX_LISTED_FILES = 5_000;
    static final int MAX_DIFF_FILES = 500;
    static final int MAX_DIFF_CHARS = 2_000_000;

    private final OfficeService office;
    private final Git git;
    private final Path dataDir;
    /** The agent of a workspace is held while the director commits in it: the dispatcher gives it nothing meanwhile. */
    private final AgentReservations reservations;

    @Inject
    public Workshop(
            OfficeService office,
            Git git,
            @ConfigProperty(name = "floor42.data-dir") Path dataDir,
            AgentReservations reservations) {
        this.office = office;
        this.git = git;
        this.dataDir = dataDir;
        this.reservations = reservations;
    }

    /** For CDI's client proxy and tests that replace one method (StallTest); never used to run git. */
    protected Workshop() {
        this(null, null, null, null);
    }

    public Workspace workspace(MissionId mission, AgentId agent) {
        return office.query(c -> c.workshop().workspace(mission, agent))
                .orElseThrow(() -> new DomainException("UNKNOWN_WORKSPACE", agent + " has no workspace on " + mission));
    }

    public String head(Workspace w) {
        return git(w, "rev-parse", "HEAD").strip();
    }

    /** Pending changes: what an acceptance would commit and a rejection would archive. */
    public List<Change> changes(Workspace w) {
        return changes(w, leftOutKinds(w));
    }

    private List<Change> changes(Workspace w, List<String> leftOut) {
        // Lines added and removed per path; -1 for a binary file.
        Map<String, int[]> counts = WorkshopFiles.numstat(git(w, "diff", "--numstat", "-z", "HEAD"));
        List<Change> changes = new ArrayList<>();
        for (GitProcess.StatusEntry e :
                GitProcess.status(git(w, "status", "--porcelain=v1", "-z", "--untracked-files=all"))) {
            String path = e.path();
            if (e.code().equals("??") && BuildArtefacts.isOne(leftOut, path)) continue;
            String status = switch (e.code().strip()) {
                case "??", "A", "AM" -> "ADDED";
                case "D", "AD" -> "DELETED";
                case "R", "RM" -> "RENAMED";
                default -> "MODIFIED";
            };
            int[] n = counts.get(path);
            if (n == null && status.equals("ADDED"))
                n = new int[] {WorkshopFiles.lineCount(Path.of(w.path()), path, MAX_FILE_BYTES), 0};
            changes.add(
                    new Change(path, status, n == null || n[0] < 0 ? null : n[0], n == null || n[1] < 0 ? null : n[1]));
        }
        return changes;
    }

    /** New files an acceptance leaves out: build and cache artefacts of a kind the repository does not track. */
    public List<String> leftOut(Workspace w) {
        List<String> all = leftOut(w, leftOutKinds(w));
        return all.size() > MAX_LISTED_FILES ? List.copyOf(all.subList(0, MAX_LISTED_FILES)) : all;
    }

    private List<String> leftOut(Workspace w, List<String> kinds) {
        if (kinds.isEmpty()) return List.of();
        return untracked(w).stream().filter(p -> BuildArtefacts.isOne(kinds, p)).toList();
    }

    /** The artefact kinds of which the repository tracks no file ({@link BuildArtefacts}). */
    private List<String> leftOutKinds(Workspace w) {
        return BuildArtefacts.untrackedKinds(GitProcess.paths(git(w, "ls-files", "-z")));
    }

    /** Runs a git command on these paths, the list read from a file: a long one fits no command line. */
    private void gitOn(Workspace w, List<String> paths, String... command) {
        if (paths.isEmpty()) return;
        List<String> args = new ArrayList<>(List.of(command));
        WorkshopFiles.withPathList(paths, f -> {
            args.addAll(List.of("--pathspec-from-file=" + f, "--pathspec-file-nul"));
            return git(w, args.toArray(String[]::new));
        });
    }

    /**
     * Every pending change as one diff, file by file: at most {@link #MAX_DIFF_FILES} files and {@link #MAX_DIFF_CHARS}
     * characters, no file above {@link #MAX_FILE_BYTES}; the artefacts left out of an acceptance are not in it.
     */
    public WorkshopFiles.WholeDiff diff(Workspace w) {
        Path root = Path.of(w.path()).toAbsolutePath().normalize();
        List<String> paths = changes(w).stream().map(Change::path).toList();
        return WorkshopFiles.bounded(
                paths,
                p -> WorkshopFiles.small(root, p, MAX_FILE_BYTES) ? diffOrNull(w, p) : null,
                MAX_DIFF_FILES,
                MAX_DIFF_CHARS);
    }

    /** The unified diff of one pending change. */
    public String diff(Workspace w, String path) {
        return diff(w, path, MAX_FILE_BYTES);
    }

    /** The diff of one change; git is stopped and the refusal is {@code FILE_TOO_LARGE} once it prints past the bound. */
    private String diff(Workspace w, String path, long maxOutput) {
        String relative = confined(w, path, ConfinedPath.Mode.READ).relative();
        if (!WorkshopFiles.small(Path.of(w.path()).toAbsolutePath().normalize(), relative, MAX_FILE_BYTES)) {
            throw new DomainException("FILE_TOO_LARGE", path + " is too large");
        }
        boolean tracked = !git(w, "status", "--porcelain=v1", "--untracked-files=all", "--", relative)
                .startsWith("??");
        try {
            // git diff --no-index exits with 1 when the files differ: that is the expected outcome here.
            return git.outBounded(
                            Path.of(w.path()),
                            tracked ? Set.of(0) : Set.of(0, 1),
                            tracked
                                    ? List.of("diff", "HEAD", "--", relative)
                                    : List.of("diff", "--no-index", "--", "/dev/null", relative),
                            maxOutput)
                    .replace("\r\n", "\n");
        } catch (GitProcess.OutputTooLarge e) {
            throw new DomainException("FILE_TOO_LARGE", path + " has too large a diff");
        }
    }

    private String diffOrNull(Workspace w, String path) {
        try {
            return diff(w, path);
        } catch (DomainException e) {
            if (e.code().equals("FILE_TOO_LARGE")) return null;
            throw e;
        }
    }

    /** Untracked files that are not ignored: what a run of the checks could leave behind. */
    public Set<String> untracked(Workspace w) {
        return new TreeSet<>(GitProcess.paths(git(w, "ls-files", "-z", "--others", "--exclude-standard")));
    }

    /** Deletes untracked files the checks created; confined like every workshop path. Returns what was removed. */
    public List<String> removeCreatedBy(Workspace w, Set<String> before) {
        return WorkshopFiles.removeCreated(Path.of(w.path()).toAbsolutePath().normalize(), untracked(w), before);
    }

    public List<String> files(Workspace w) {
        List<String> all = GitProcess.paths(git(w, "ls-files", "-z", "--cached", "--others", "--exclude-standard"));
        return all.size() > MAX_LISTED_FILES ? List.copyOf(all.subList(0, MAX_LISTED_FILES)) : all;
    }

    /** A file's text, or null when it is binary. */
    public String read(Workspace w, String path) {
        Path file = confined(w, path, ConfinedPath.Mode.READ).file();
        try {
            if (!Files.isRegularFile(file)) throw new DomainException("NO_FILE", path + " is not a file");
            if (Files.size(file) > MAX_FILE_BYTES) throw new DomainException("FILE_TOO_LARGE", path + " is too large");
            return WorkshopText.text(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    /** The director edits a file: Floor42 writes it, measures the change with git, and records it. */
    public boolean edit(MissionId mission, AgentId agent, String path, String content, String note) {
        Workspace w = workspace(mission, agent);
        checkAccess(mission, agent);
        ConfinedPath target = confined(w, path, ConfinedPath.Mode.WRITE);
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
            throw new DomainException("FILE_TOO_LARGE", path + " is too large");
        }
        WorkshopText.refuseANonTextFile(target.file(), path);
        WorkshopFiles.write(target.file(), content, path);
        String diff;
        try {
            diff = diff(w, target.relative(), 4L * MAX_FILE_BYTES);
        } catch (DomainException e) {
            // The file is written: a diff too large to show does not undo it, and the edit is still recorded.
            if (!e.code().equals("FILE_TOO_LARGE")) throw e;
            diff = "(the diff is too large to show)";
        }
        if (diff.isBlank()) return false;
        String recorded = diff;
        office.execute(c -> c.workshop()
                .recordDirectorEdit(
                        director(mission),
                        mission,
                        agent,
                        target.relative(),
                        WorkshopFiles.blankToNull(note),
                        WorkshopFiles.abbreviate(recorded)));
        return true;
    }

    /**
     * Commits the pending changes (the ticked paths only, when given) on the workspace's branch; the director merges the branch when they choose.
     *
     * <p>What keeps agents out of the worktree is the dispatch lock, not the office's: sessions start from tasks the
     * dispatcher chooses under it. {@link AgentReservations#holdForWorkshop} refuses while a task of the mission runs,
     * then holds the workspace's agent and the mission, so nothing starts on it until the record is written. Git runs
     * outside the office's lock, which only the record takes.
     *
     * <p>The order never loses the director's work: commit, then record. A commit that fails unstages what it staged; a
     * record that fails (a refusal, the journal) moves the branch back to where it was and unstages, the files kept in
     * the worktree as they were. Recording first would need an event that names a commit not made yet.
     */
    public String accept(MissionId mission, AgentId agent, String message) {
        return accept(mission, agent, message, null);
    }

    public String accept(MissionId mission, AgentId agent, String message, List<String> paths) {
        Workspace w = workspace(mission, agent);
        checkAccess(mission, agent);
        if (message == null || message.isBlank()) throw new DomainException("INVALID_ARGUMENT", "message is required");
        List<String> files = picked(w, paths);
        if (files.isEmpty()) throw new DomainException("EMPTY_PATCH", "Nothing to accept");
        WorkshopText.refuseConfiguration(files);
        AgentReservations.Reservation held = reservations
                .holdForWorkshop(mission, agent)
                .orElseThrow(() -> atWork(mission, new DomainException("AGENT_AT_WORK", BUSY)));
        try {
            // Looked at again once held: a session the dispatcher started just before is in the journal by now.
            checkAccess(mission, agent);
            String before = head(w);
            String sha =
                    paths == null ? commit(w, mission, message.strip()) : commit(w, mission, message.strip(), files);
            try {
                record(mission, agent, sha, message.strip(), files);
            } catch (RuntimeException e) {
                // Handled: the commit is undone, the changes kept; the failure is passed on
                undo(w, before, sha);
                throw e instanceof DomainException d ? atWork(mission, d) : e;
            }
            return sha;
        } finally {
            reservations.releaseWorkshop(mission, held);
        }
    }

    /** The files an accept commits: the asked paths, each a pending change, or all of them. */
    private List<String> picked(Workspace w, List<String> paths) {
        return WorkshopFiles.pick(
                Path.of(w.path()),
                changes(w, leftOutKinds(w)).stream().map(Change::path).toList(),
                GitProcess.status(git(w, "status", "--porcelain=v1", "-z", "--untracked-files=all")),
                paths);
    }

    static final String BUSY = "the agent is busy, on a task or at the director's meeting: try again once it is free";

    /**
     * Stages and commits, the build and cache files {@link #leftOut(Workspace)} lists taken back out; on any failure,
     * unstages what it staged, so the worktree is as it was.
     */
    protected String commit(Workspace w, MissionId mission, String message) {
        return commit(w, mission, message, null);
    }

    /** The same for the ticked files only ({@code files} null: all of them), the others left in the worktree. */
    protected String commit(Workspace w, MissionId mission, String message, List<String> files) {
        try {
            MissionBranch.checked(git, w);
            if (files != null) {
                git(w, "reset", "--quiet");
                gitOn(w, files, "add", "--all");
            } else {
                List<String> leftOut = leftOut(w, leftOutKinds(w));
                git(w, "add", "--all");
                gitOn(w, leftOut, "reset", "--quiet");
            }
            List<String> commit = new ArrayList<>(identity(w));
            commit.addAll(List.of(
                    "commit",
                    "--quiet",
                    "-m",
                    message,
                    "-m",
                    "Accepted by the director in Floor42 (" + mission + ")."));
            git(w, commit.toArray(String[]::new));
            return head(w);
        } catch (RuntimeException e) {
            // Handled: unstaged, then passed on
            git(w, "reset", "--quiet");
            throw e;
        }
    }

    /** The journal's record of the commit, under the office's lock. */
    protected void record(MissionId mission, AgentId agent, String sha, String message, List<String> files) {
        office.execute(c -> c.workshop().acceptPatch(director(mission), mission, agent, sha, message, files));
    }

    /** Moves the branch back before an unrecorded commit, unstaged, the files kept; never over someone else's commit. */
    private void undo(Workspace w, String before, String sha) {
        try {
            if (head(w).equals(sha)) git(w, "reset", "--quiet", "--mixed", before);
            else LOG.errorf("%s: the branch moved past %s, which stays unrecorded: check it in git", w.path(), sha);
        } catch (RuntimeException e) {
            // Handled: logged with what to look at; the first failure is the one the director sees
            LOG.errorf(e, "%s: could not undo the unrecorded commit %s: check it in git", w.path(), sha);
        }
    }

    /** Archives the pending changes as a patch file, then restores the workspace to its last commit. */
    public String reject(MissionId mission, AgentId agent, String reason) {
        Workspace w = workspace(mission, agent);
        checkAccess(mission, agent);
        if (reason == null || reason.isBlank()) throw new DomainException("INVALID_ARGUMENT", "reason is required");
        List<String> files = changes(w).stream().map(Change::path).toList();
        if (files.isEmpty()) throw new DomainException("EMPTY_PATCH", "Nothing to reject");
        git(w, "add", "--all");
        String patch = git(w, "diff", "--cached", "--binary", "HEAD");
        String name =
                mission.value() + "-" + agent.value() + "-" + Instant.now().toEpochMilli() + ".patch";
        try {
            Path archive = dataDir.resolve("patches").resolve(name);
            Files.createDirectories(archive.getParent());
            Files.writeString(archive, patch, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot archive the rejected changes; nothing was discarded", e);
        }
        git(w, "reset", "--quiet", "--hard", "HEAD");
        git(w, "clean", "--quiet", "-d", "--force");
        String archive = "patches/" + name;
        office.execute(
                c -> c.workshop().rejectPatch(director(mission), mission, agent, reason.strip(), archive, files));
        return archive;
    }

    private void checkAccess(MissionId mission, AgentId agent) {
        try {
            office.query(c -> {
                c.workshop().checkAccess(director(mission), mission, agent);
                return null;
            });
        } catch (DomainException e) {
            // Handled: the same refusal, naming who is at work when that is the reason
            throw atWork(mission, e);
        }
    }

    /**
     * The domain's AGENT_AT_WORK names the mission only; the director needs to know whose session holds the workspaces
     * and on which ticket, since the workshop may have shown none a moment earlier (a review ending, the next ticket
     * starting). Any other refusal is passed on as it is.
     */
    private DomainException atWork(MissionId mission, DomainException e) {
        if (!"AGENT_AT_WORK".equals(e.code())) return e;
        String who = office.query(c -> c.sessions().of(mission).stream()
                .filter(dev.floor42.domain.AgentSession::isOpen)
                .map(x -> x.agent().value() + " on " + x.ticket().value())
                .distinct()
                .collect(java.util.stream.Collectors.joining(", ")));
        if (who.isEmpty()) return e;
        return new DomainException(
                "AGENT_AT_WORK", "A session is still running on " + mission + " (" + who + "): try again once it ends");
    }

    private CommandContext director(MissionId mission) {
        Origin origin =
                office.query(c -> c.missions().find(mission).orElseThrow().origin());
        return office.context(Actor.director(), origin);
    }

    /** The path once confinement accepts it (refused as OUTSIDE_WORKSPACE otherwise). */
    private static ConfinedPath confined(Workspace w, String path, ConfinedPath.Mode mode) {
        if (path == null || path.isBlank()) throw new DomainException("INVALID_ARGUMENT", "path is required");
        return Confinement.relative(Path.of(w.path()), path, mode);
    }

    /** The director's own git identity when the repository has one; otherwise a neutral one naming Floor42. */
    private List<String> identity(Workspace w) {
        String email = git.outIfOk(Path.of(w.path()), "config", "user.email")
                .orElse("")
                .strip();
        if (!email.isEmpty()) return List.of();
        return List.of("-c", "user.name=Floor42 director", "-c", "user.email=director@floor42.invalid");
    }

    /** Git in the workspace, which must succeed; CRLF read as LF (worktrees of other repositories may hold CRLF). */
    private String git(Workspace w, String... args) {
        return git.out(Path.of(w.path()), args).replace("\r\n", "\n");
    }
}
