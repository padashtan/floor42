package dev.floor42.server.app;

import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.server.app.Delivered.Blob;
import dev.floor42.server.app.Delivered.DeliverablesView;
import dev.floor42.server.app.Delivered.DeliveredBranch;
import dev.floor42.server.app.Delivered.DeliveredCommit;
import dev.floor42.server.app.Delivered.DeliveredFile;
import dev.floor42.server.app.Delivered.DeliveredTicket;
import dev.floor42.server.app.Delivered.Source;
import dev.floor42.server.app.MissionBranches.Facts;
import dev.floor42.server.app.ResolvedBranch.Known;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * What a mission delivered, open or closed (ADR-0037): each ticket's deliverable text with its reviews, the files each
 * agent's branch added or changed since the worktree was made, the files a worktree holds that were never committed,
 * how far each branch reached the project's branch, and the pull requests. Read from the journal and git when asked;
 * nothing is changed and nothing is journaled.
 *
 * <p>This service assembles the views; git's history is read by {@link GitReader}, worktrees by {@link
 * WorktreeScanner}, single files by {@link BlobSource} and the ZIP written by {@link DeliverablesZip}. Only the
 * mission's repository and Floor42's worktrees of this mission are read.
 */
@ApplicationScoped
public class Deliverables {

    private static final Logger LOG = Logger.getLogger(Deliverables.class);

    /** At most this many files per list (committed, not committed) of one branch. */
    public static final int MAX_FILES = 500;

    /** A text larger than this is offered for download only. */
    public static final long MAX_VIEW_TEXT = 1024L * 1024;

    /** An image or a PDF larger than this is offered for download only. */
    public static final long MAX_VIEW_BYTES = 25L * 1024 * 1024;

    /** The mission's ZIP holds at most this many bytes of files (before compression); the rest is listed as left out. */
    public static final long MAX_ZIP_BYTES = DeliverablesZip.MAX_ZIP_BYTES;

    /** Opens a folder in the operating system's file explorer; replaced in tests. */
    public interface FolderOpener {
        void open(Path folder) throws IOException;
    }

    private final MissionBranches branches;
    private final GitReader history;
    private final WorktreeScanner scanner;
    private final BlobSource blobs;
    private final DeliverablesZip zip;
    private volatile FolderOpener opener = Deliverables::launchExplorer;

    @Inject
    public Deliverables(
            MissionBranches branches,
            GitReader history,
            WorktreeScanner scanner,
            BlobSource blobs,
            DeliverablesZip zip) {
        this.branches = branches;
        this.history = history;
        this.scanner = scanner;
        this.blobs = blobs;
        this.zip = zip;
    }

    /** Tests replace the file explorer: no window ever opens during a build. */
    public void useForTests(FolderOpener replacement) {
        this.opener = replacement == null ? Deliverables::launchExplorer : replacement;
    }

    public DeliverablesView view(MissionId id, boolean ignored) {
        Facts f = branches.facts(id);
        Mission m = f.mission();
        Path repo = f.repo();
        var project = repo != null && Files.isDirectory(repo) ? branches.project(repo) : null;
        boolean available = project != null;
        String projectHead = available ? project.head() : null;
        Map<String, List<String>> ticketsOf = ticketsByAgent(f.tickets());
        List<DeliveredBranch> all = new ArrayList<>();
        for (Known k : branches.worktrees(id, f.known()).values()) {
            ResolvedBranch r = branches.resolve(k, repo, available);
            all.add(branch(r, projectHead, ignored, ticketsOf.getOrDefault(k.agent(), List.of())));
        }
        return new DeliverablesView(
                id.value(),
                m.title(),
                m.status().name(),
                m.origin().name(),
                f.repository(),
                available,
                available ? project.branch() : null,
                Delivered.shortHash(projectHead),
                ignored,
                f.tickets(),
                all,
                f.pullRequests(),
                MAX_VIEW_TEXT,
                MAX_VIEW_BYTES,
                MAX_ZIP_BYTES);
    }

    private static Map<String, List<String>> ticketsByAgent(List<DeliveredTicket> tickets) {
        Map<String, List<String>> ticketsOf = new HashMap<>();
        for (DeliveredTicket t : tickets) {
            if (t.assignee() != null)
                ticketsOf.computeIfAbsent(t.assignee(), a -> new ArrayList<>()).add(t.id());
        }
        return ticketsOf;
    }

    /** How far the branch reached the project, from the commits it has and the project lacks. */
    private record Reach(String merged, int notInProject, Set<String> outside) {}

    private Reach reach(ResolvedBranch r, String projectHead) {
        int total = history.revList(r.gitDir(), r.tip(), r.start()).size();
        if (projectHead == null) return new Reach(total == 0 ? "NOTHING" : "UNKNOWN", -1, null);
        Set<String> outside = new HashSet<>(history.revList(r.gitDir(), r.tip(), projectHead));
        int n = outside.size();
        String merged = total == 0 ? "NOTHING" : n == 0 ? "MERGED" : n < total ? "PARTIAL" : "NOT_MERGED";
        return new Reach(merged, n, outside);
    }

    private DeliveredBranch branch(ResolvedBranch r, String projectHead, boolean ignored, List<String> tickets) {
        Reach reach = new Reach("UNKNOWN", -1, null);
        List<DeliveredCommit> commits = List.of();
        List<DeliveredFile> committed = List.of();
        boolean truncated = false;
        if (r.readable()) {
            reach = reach(r, projectHead);
            commits = history.commits(r.gitDir(), r.start(), r.tip(), reach.outside());
            List<DeliveredFile> files = history.committedFiles(r.gitDir(), r.start(), r.tip());
            truncated = files.size() > MAX_FILES;
            committed = List.copyOf(truncated ? files.subList(0, MAX_FILES) : files);
        }
        WorktreeScanner.Scan wt = r.present() ? scanner.scan(r.worktree(), ignored, MAX_FILES) : null;
        return new DeliveredBranch(
                r.agent(),
                r.branch(),
                r.worktree() == null ? null : r.worktree().toString(),
                r.present(),
                branches.legacy(r.worktree()),
                Delivered.shortHash(r.start()),
                Delivered.shortHash(r.tip()),
                reach.merged(),
                reach.notInProject(),
                commits,
                committed,
                wt == null ? List.of() : wt.files(),
                wt == null ? 0 : wt.ignoredHidden(),
                truncated || (wt != null && wt.truncated()),
                List.copyOf(tickets));
    }

    /**
     * The bytes of one file the listing names, refused otherwise: an unknown agent is a 404; a path that leaves the
     * worktree, names {@code .git}, is a link, or is not one the listing would show is refused.
     */
    public Blob file(MissionId id, String agent, String path, Source source) {
        if (path == null || path.isBlank() || path.indexOf('\0') >= 0) {
            throw RefusedException.invalid("INVALID_ARGUMENT", "path is required");
        }
        if (source == null) throw RefusedException.invalid("INVALID_ARGUMENT", "source is COMMITTED or WORKTREE");
        Facts f = branches.facts(id);
        Path repo = f.repo();
        Known k = branches.worktrees(id, f.known()).get(agent);
        if (k == null) throw new NotFoundException("No branch of " + agent + " on " + id.value());
        ResolvedBranch r = branches.resolve(k, repo, repo != null && Files.isDirectory(repo));
        return source == Source.WORKTREE ? blobs.fromWorktree(r, path) : blobs.fromBranch(r, path, true);
    }

    /** Writes the whole mission as a ZIP (see {@link DeliverablesZip}). */
    public void zip(MissionId id, boolean ignored, OutputStream target) throws IOException {
        int files = zip.write(view(id, ignored), branches.resolved(id), target);
        LOG.infof("Deliverables of %s downloaded by the director as a ZIP (%d files)", id.value(), files);
    }

    /** TEXT, IMAGE, PDF or OTHER, from the name (see {@link FileKinds}). */
    public static String kind(String path) {
        return FileKinds.kind(path);
    }

    /**
     * Opens a worktree of the mission in the operating system's file explorer, on the director's own machine. Only a
     * folder Floor42 made for this mission, inside its data folder (or Office's former one), is ever opened.
     */
    public void openFolder(MissionId id, String agent) {
        if (agent == null || agent.isBlank()) throw RefusedException.invalid("INVALID_ARGUMENT", "agent is required");
        Facts f = branches.facts(id);
        Known k = branches.worktrees(id, f.known()).get(agent);
        if (k == null || k.worktree() == null)
            throw new NotFoundException("No worktree of " + agent + " on " + id.value());
        Path folder = k.worktree().toAbsolutePath().normalize();
        if (!Workspaces.exists(folder))
            throw RefusedException.conflict("WORKTREE_GONE", "The worktree of " + agent + " is gone");
        if (!branches.madeByFloor42(folder)) {
            throw RefusedException.conflict(
                    "NOT_A_MISSION_WORKTREE", folder + " is not a worktree Floor42 made in its data folder");
        }
        try {
            opener.open(folder);
        } catch (IOException e) {
            throw RefusedException.conflict(
                    "OPEN_FOLDER_UNAVAILABLE", "No file explorer could be started here: " + e.getMessage(), e);
        }
    }

    /** The explorer of the director's OS, given the folder as its only argument: no shell, nothing to interpret. */
    private static void launchExplorer(Path folder) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> cmd = os.contains("win")
                ? List.of("explorer.exe", folder.toString())
                : os.contains("mac")
                        ? List.of("open", "--", folder.toString())
                        : List.of("xdg-open", folder.toString());
        new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
