package dev.floor42.server.app;

import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.Workspace;
import dev.floor42.server.app.Delivered.DeliveredPullRequest;
import dev.floor42.server.app.Delivered.DeliveredReview;
import dev.floor42.server.app.Delivered.DeliveredTicket;
import dev.floor42.server.app.ResolvedBranch.Known;
import dev.floor42.server.config.DataFolders;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * A mission's branches as the deliverables read them: what the journal says (tickets, worktrees, pull requests,
 * copied under the lock), the worktrees found on disk under the mission's name, and each branch resolved by git now.
 */
@ApplicationScoped
public class MissionBranches {

    private static final Logger LOG = Logger.getLogger(MissionBranches.class);

    /** What the journal says of a mission, copied under the lock. */
    record Facts(
            Mission mission,
            String repository,
            List<DeliveredTicket> tickets,
            List<Known> known,
            List<DeliveredPullRequest> pullRequests) {

        /** The mission's repository, null when it has none. */
        Path repo() {
            return repository == null || repository.isBlank() ? null : Path.of(repository);
        }
    }

    private final OfficeService office;
    private final Workspaces workspaces;
    private final GitReader history;
    private final Path dataDir;

    @Inject
    public MissionBranches(
            OfficeService office,
            Workspaces workspaces,
            GitReader history,
            @ConfigProperty(name = "floor42.data-dir") Path dataDir) {
        this.office = office;
        this.workspaces = workspaces;
        this.history = history;
        this.dataDir = dataDir;
    }

    /** The project's current branch (empty on a detached HEAD) and last commit (null when it has none). */
    record Project(String head, String branch) {}

    /** What git says of the mission's repository now; null when git cannot read it as one. */
    Project project(Path repo) {
        return workspaces
                .facts(repo)
                .map(f -> new Project(workspaces.resolve(repo, "HEAD"), f.branch()))
                .orElse(null);
    }

    Facts facts(MissionId id) {
        return office.query(c -> {
            Mission m = c.missions().find(id).orElseThrow(() -> new NotFoundException("Unknown mission " + id.value()));
            List<DeliveredTicket> tickets =
                    c.tickets().of(id).stream().map(MissionBranches::ticket).toList();
            List<Known> known = new ArrayList<>();
            for (Workspace w : c.workshop().workspaces(id)) {
                known.add(new Known(
                        w.agent().value(), w.path() == null ? null : Path.of(w.path()), w.branch(), w.startCommit()));
            }
            List<DeliveredPullRequest> prs = c.deliveries().pullRequests(id).stream()
                    .map(p -> new DeliveredPullRequest(
                            p.number(),
                            p.url(),
                            p.workspace() == null ? null : p.workspace().value(),
                            p.branch(),
                            p.base(),
                            p.openedAt() == null ? null : p.openedAt().toString()))
                    .toList();
            return new Facts(m, m.workspace(), tickets, known, prs);
        });
    }

    private static DeliveredTicket ticket(Ticket t) {
        List<DeliveredReview> reviews =
                t.reviews().stream().map(DeliveredReview::of).toList();
        return new DeliveredTicket(
                t.id().value(),
                t.title(),
                t.status().name(),
                t.assignee() == null ? null : t.assignee().value(),
                t.deliverable(),
                reviews.isEmpty() ? null : reviews.getLast().verdict(),
                reviews);
    }

    /**
     * The journaled worktrees of the mission, then the ones found on disk under its name (Office's former folder
     * included): by agent, in name order.
     */
    Map<String, Known> worktrees(MissionId id, List<Known> journaled) {
        Map<String, Known> all = new TreeMap<>();
        for (Known k : journaled) all.put(k.agent(), k);
        workspaces.worktrees(id).forEach((agent, path) -> {
            Known j = all.get(agent);
            if (j == null) all.put(agent, new Known(agent, path, null, null));
            else if (j.worktree() == null || !Workspaces.exists(j.worktree()))
                all.put(agent, new Known(agent, path, j.branch(), j.start()));
        });
        return all;
    }

    /** The branches of the mission as resolved now, by agent: the ZIP reads every file through them. */
    Map<String, ResolvedBranch> resolved(MissionId id) {
        Facts f = facts(id);
        Path repo = f.repo();
        boolean available = repo != null && Files.isDirectory(repo);
        Map<String, ResolvedBranch> all = new HashMap<>();
        worktrees(id, f.known()).forEach((agent, k) -> all.put(agent, resolve(k, repo, available)));
        return all;
    }

    /** What git says of one branch: resolved by Floor42, never from a name the caller gave. */
    ResolvedBranch resolve(Known k, Path repo, boolean available) {
        boolean present = k.worktree() != null && Workspaces.exists(k.worktree());
        Path gitDir = present ? k.worktree() : (available ? repo : null);
        if (gitDir == null) return new ResolvedBranch(k.agent(), k.worktree(), null, false, null, null, k.branch());
        String branch = k.branch();
        if (present && (branch == null || branch.isBlank())) {
            branch = workspaces
                    .facts(gitDir)
                    .map(Workspaces.RepositoryFacts::branch)
                    .orElse(null);
        }
        String tip = present
                ? workspaces.resolve(gitDir, "HEAD")
                : branch == null || branch.isBlank() ? null : workspaces.resolve(gitDir, "refs/heads/" + branch);
        String start = k.start() != null && GitReader.HASH.matcher(k.start()).matches() ? k.start() : null;
        if (start == null && tip != null && available) {
            String head = workspaces.resolve(repo, "HEAD");
            if (head != null) start = history.mergeBase(gitDir, head, tip);
        }
        return new ResolvedBranch(k.agent(), k.worktree(), gitDir, present, tip, start, branch);
    }

    /** The worktree sits in Office's former data folder (made before 1.5.0). */
    boolean legacy(Path worktree) {
        return worktree != null
                && DataFolders.legacy(dataDir)
                        .map(old -> worktree.toAbsolutePath().normalize().startsWith(old))
                        .orElse(false);
    }

    /** True when {@code folder} really is under a {@code workspaces} folder of Floor42's data (links resolved). */
    boolean madeByFloor42(Path folder) {
        try {
            Path real = folder.toRealPath();
            for (Path data : DataFolders.guarded(dataDir)) {
                Path root = data.resolve("workspaces").toAbsolutePath().normalize();
                if (Files.isDirectory(root) && real.startsWith(root.toRealPath())) return true;
            }
            return false;
        } catch (IOException e) {
            // Handled: a folder whose real path cannot be read is not opened (refused as not a mission worktree).
            LOG.debugf("Cannot resolve %s: %s", folder, e.getMessage());
            return false;
        }
    }
}
