package dev.floor42.server.api;

import dev.floor42.domain.MissionId;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.Workspaces;
import dev.floor42.server.app.Workspaces.CommitLine;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The archive wall (W6): the commits of a mission's repository and, for each agent's worktree, the commits its branch
 * holds beyond them, and the git graph of both: every commit of the director's branch and of the agents' branches
 * with its parents, so lanes, branchings and merges can be drawn. Read from git when asked, never stored; nothing is
 * changed. Only the mission's repository and Floor42's worktrees of this mission are read.
 */
@Path("/api/missions/{id}/archive")
@Produces(MediaType.APPLICATION_JSON)
public class ArchiveResource {

    /** At most this many commits per line of the wall. */
    static final int MAX_COMMITS = 12;

    /** At most this many commits in the git graph. */
    static final int MAX_GRAPH = 200;

    /**
     * A branch head in the graph.
     *
     * @param agent null for the director's branch (the repository's current branch)
     * @param branch empty on a detached HEAD
     * @param uncommitted files changed in that checkout and not committed: not in the graph
     */
    public record GraphHead(String agent, String branch, String commit, int uncommitted) {}

    /**
     * @param heads the director's branch first, then each agent's, in name order
     * @param commits children before parents; a parent may be missing when the graph is truncated
     * @param truncated more commits exist than {@link #MAX_GRAPH}
     */
    public record GraphView(List<GraphHead> heads, List<Workspaces.GraphCommit> commits, boolean truncated) {
        public GraphView {
            commits = commits == null ? null : Collections.unmodifiableList(new ArrayList<>(commits));
            heads = heads == null ? null : Collections.unmodifiableList(new ArrayList<>(heads));
        }
    }

    /**
     * @param ahead commits on the agent's branch that the repository's current branch lacks; -1 if git cannot count
     */
    public record BranchLine(String agent, String branch, int ahead, List<CommitLine> commits) {
        public BranchLine {
            commits = commits == null ? null : Collections.unmodifiableList(new ArrayList<>(commits));
        }
    }

    /**
     * @param available false when the mission has no repository, or git cannot read it: the wall says so, empty
     * @param trunk the newest commits of the repository's current branch, newest first
     */
    public record ArchiveView(
            String mission,
            String repository,
            String branch,
            boolean available,
            List<CommitLine> trunk,
            List<BranchLine> branches,
            GraphView graph) {
        public ArchiveView {
            branches = branches == null ? null : Collections.unmodifiableList(new ArrayList<>(branches));
            trunk = trunk == null ? null : Collections.unmodifiableList(new ArrayList<>(trunk));
        }
    }

    @Inject
    OfficeService office;

    @Inject
    Workspaces workspaces;

    @GET
    public ArchiveView archive(@PathParam("id") String id) {
        MissionId mission = new MissionId(id);
        String repository = office.query(c -> c.missions()
                .find(mission)
                .map(m -> m.workspace() == null ? "" : m.workspace())
                .orElseThrow(() -> new NotFoundException("Unknown mission " + id)));
        if (repository.isBlank()) {
            return new ArchiveView(id, null, null, false, List.of(), List.of(), EMPTY);
        }
        java.nio.file.Path repo = java.nio.file.Path.of(repository);
        var facts = Files.isDirectory(repo)
                ? workspaces.facts(repo)
                : java.util.Optional.<Workspaces.RepositoryFacts>empty();
        if (facts.isEmpty()) return new ArchiveView(id, repository, null, false, List.of(), List.of(), EMPTY);
        String head = workspaces.resolve(repo, "HEAD");
        List<BranchLine> branches = new ArrayList<>();
        List<GraphHead> heads = new ArrayList<>();
        if (head != null)
            heads.add(
                    new GraphHead(null, facts.get().branch(), head, facts.get().uncommitted()));
        workspaces.worktrees(mission).forEach((agent, wt) -> {
            String range = head == null ? "HEAD" : head + "..HEAD";
            var wtFacts = workspaces.facts(wt);
            String branch = wtFacts.map(f -> f.branch()).orElse("");
            branches.add(
                    new BranchLine(agent, branch, workspaces.count(wt, range), workspaces.log(wt, range, MAX_COMMITS)));
            String tip = workspaces.resolve(wt, "HEAD");
            if (tip != null) {
                heads.add(new GraphHead(
                        agent, branch, tip, wtFacts.map(f -> f.uncommitted()).orElse(0)));
            }
        });
        // The worktrees share the repository's objects: one log over every head draws the whole graph.
        List<Workspaces.GraphCommit> commits =
                workspaces.graph(repo, heads.stream().map(GraphHead::commit).toList(), MAX_GRAPH + 1);
        boolean truncated = commits.size() > MAX_GRAPH;
        return new ArchiveView(
                id,
                repository,
                facts.get().branch(),
                true,
                head == null ? List.of() : workspaces.log(repo, "HEAD", MAX_COMMITS),
                branches,
                new GraphView(heads, truncated ? commits.subList(0, MAX_GRAPH) : commits, truncated));
    }

    private static final GraphView EMPTY = new GraphView(List.of(), List.of(), false);
}
