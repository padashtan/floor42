package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Workspace;
import dev.floor42.server.runtime.ConfinedPath;
import dev.floor42.server.runtime.Confinement;
import dev.floor42.server.runtime.GitFailure;
import dev.floor42.server.runtime.OutsideWorkspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.logging.Logger;

/**
 * Files the director gives a mission. They go into the director's own project, committed on the director's branch on
 * their click (the one case where Floor42 writes to the director's checkout); then the director merges that branch into
 * the agents' worktrees, so the agents see them. Each step is recorded.
 */
@ApplicationScoped
public class DirectorFiles {

    private static final Logger LOG = Logger.getLogger(DirectorFiles.class);

    /** At most this much in one gift, decoded. */
    public static final long MAX_BYTES = 20L * 1024 * 1024;

    public static final int MAX_FILES = 50;

    private static final String REFUSED = "FILES_REFUSED";

    /** A file from the director's browser: its name and its content in base64. */
    public record Upload(String name, String content) {}

    /**
     * @param upToDate the worktree already holds the project's last commit
     * @param atWork a session of this agent runs: no merge until it ends
     */
    public record AgentCopy(String agent, boolean upToDate, boolean atWork) {}

    /**
     * Where the director's files stand for a mission.
     *
     * @param pending what the director put in the project folder and did not commit yet (at most 200 shown)
     */
    public record ProjectState(
            String path, String branch, String head, List<String> pending, int pendingCount, List<AgentCopy> agents) {
        public ProjectState {
            agents = agents == null ? null : Collections.unmodifiableList(new ArrayList<>(agents));
            pending = pending == null ? null : Collections.unmodifiableList(new ArrayList<>(pending));
        }
    }

    /** @param skipped already up to date, nothing merged */
    public record MergeResult(String agent, boolean clean, boolean skipped, String detail) {}

    /** One file of a gift, checked: where it goes in the project and its decoded bytes. */
    private record Gift(ConfinedPath target, byte[] bytes) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Gift p
                    && java.util.Objects.equals(target, p.target)
                    && java.util.Arrays.equals(bytes, p.bytes);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(target, java.util.Arrays.hashCode(bytes));
        }

        @Override
        public String toString() {
            return "Gift[" + "target=" + target + ", " + bytes.length + " bytes" + "]";
        }
    }

    private final OfficeService office;
    private final Workspaces workspaces;

    @Inject
    public DirectorFiles(OfficeService office, Workspaces workspaces) {
        this.office = office;
        this.workspaces = workspaces;
    }

    public ProjectState state(MissionId id) {
        Mission m = mission(id);
        Path project = Path.of(m.workspace());
        String head = workspaces.resolve(project, "HEAD");
        List<String> pending = workspaces.pending(project);
        List<AgentCopy> agents = office.query(c -> c.workshop().workspaces(id).stream()
                .map(w -> new AgentCopy(
                        w.agent().value(),
                        head != null && workspaces.contains(Path.of(w.path()), head),
                        c.sessions().of(id).stream()
                                .anyMatch(s -> s.isOpen() && s.agent().equals(w.agent()))))
                .toList());
        String branch = workspaces
                .facts(project)
                .map(Workspaces.RepositoryFacts::branch)
                .orElse("");
        return new ProjectState(
                project.toString(), branch, head, pending.stream().limit(200).toList(), pending.size(), agents);
    }

    /**
     * Writes the files into {@code folder} of the project (never over an existing file, never outside the project or
     * into git's own files) and commits them on the director's branch.
     */
    public ProjectState give(MissionId id, String folder, List<Upload> uploads) {
        Mission m = mission(id);
        if (uploads == null || uploads.isEmpty()) throw invalid("Which files?");
        if (uploads.size() > MAX_FILES) {
            throw RefusedException.tooLarge(REFUSED, "At most " + MAX_FILES + " files at once");
        }
        Path project = Path.of(m.workspace()).toAbsolutePath().normalize();
        String base = folder == null ? "" : trimSlashes(folder.strip().replace('\\', '/'));
        List<Gift> gifts = new ArrayList<>();
        long total = 0;
        for (Upload u : uploads) {
            Gift g = gift(project, base, u, gifts);
            total += g.bytes().length;
            if (total > MAX_BYTES) throw RefusedException.tooLarge(REFUSED, "At most 20 MB at once");
            gifts.add(g);
        }
        writeAll(gifts);
        List<String> paths = gifts.stream().map(g -> g.target().relative()).toList();
        String commit = commit(project, paths, "Director's files for " + id + ": " + String.join(", ", paths));
        office.execute(
                c -> c.workshop().recordDirectorFiles(office.context(Actor.director(), m.origin()), id, commit, paths));
        return state(id);
    }

    /** One upload, refused when it has no name, leaves the project, exists already, comes twice or is not base64. */
    /** The text without its leading and trailing slashes, in linear time (a regex on a hostile run of slashes is not). */
    static String trimSlashes(String s) {
        int from = 0;
        int to = s.length();
        while (from < to && s.charAt(from) == '/') from++;
        while (to > from && s.charAt(to - 1) == '/') to--;
        return s.substring(from, to);
    }

    private static Gift gift(Path project, String base, Upload u, List<Gift> before) {
        String name = u.name() == null ? "" : u.name().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) throw invalid("A file needs a name");
        String rel = base.isEmpty() ? name : base + "/" + name;
        ConfinedPath target;
        try {
            target = Confinement.relative(project, rel, ConfinedPath.Mode.WRITE);
        } catch (OutsideWorkspace e) {
            throw RefusedException.invalid(REFUSED, e.getMessage(), e);
        }
        if (Files.exists(target.file())) {
            throw RefusedException.conflict(REFUSED, rel + " already exists in the project: rename the file");
        }
        if (before.stream().anyMatch(g -> g.target().equals(target))) throw invalid(rel + " is given twice");
        try {
            return new Gift(target, Base64.getDecoder().decode(u.content() == null ? "" : u.content()));
        } catch (IllegalArgumentException e) {
            throw RefusedException.invalid(REFUSED, name + " is not readable (base64 expected)", e);
        }
    }

    /** All or nothing: on a failure the files already written go, so no half set waits uncommitted in the project. */
    private static void writeAll(List<Gift> gifts) {
        List<Path> written = new ArrayList<>();
        try {
            for (Gift g : gifts) {
                Path target = g.target().file();
                Files.createDirectories(target.getParent());
                // CREATE_NEW: a file or a link that appeared since the check is never overwritten.
                Files.write(target, g.bytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                written.add(target);
            }
        } catch (IOException e) {
            for (Path w : written) {
                try {
                    Files.deleteIfExists(w);
                } catch (IOException left) {
                    // Handled: the director still sees the file in the project state, pending; the log says why.
                    LOG.warnf("Could not remove %s after a failed gift: %s", w, left.getMessage());
                }
            }
            throw RefusedException.conflict(REFUSED, "Cannot write the files: " + e.getMessage(), e);
        }
    }

    /**
     * Commits on the director's branch the pending paths the director confirmed, and only those: what else waits in
     * the folder (a stray {@code .env}) stays out of the commit. The paths come from {@link ProjectState#pending()};
     * one that is not pending (any more) refuses the whole commit.
     */
    public ProjectState commitPending(MissionId id, List<String> confirmed) {
        Mission m = mission(id);
        Path project = Path.of(m.workspace());
        if (confirmed == null || confirmed.isEmpty()) {
            throw invalid("Which paths? Confirm the pending paths to commit");
        }
        List<String> pending = workspaces.pending(project);
        if (pending.isEmpty()) throw RefusedException.conflict(REFUSED, "Nothing to commit in " + project);
        List<String> paths = new ArrayList<>(new LinkedHashSet<>(confirmed));
        List<String> unknown = paths.stream()
                .filter(p -> p == null || !pending.contains(p))
                .map(String::valueOf)
                .toList();
        if (!unknown.isEmpty()) {
            throw RefusedException.conflict(REFUSED, "Not pending in the project: " + String.join(", ", unknown));
        }
        String commit = commit(project, paths, "Director's changes for " + id);
        office.execute(
                c -> c.workshop().recordDirectorFiles(office.context(Actor.director(), m.origin()), id, commit, paths));
        return state(id);
    }

    private String commit(Path project, List<String> paths, String message) {
        try {
            return workspaces.commit(project, paths, message);
        } catch (GitFailure e) {
            throw RefusedException.conflict(REFUSED, e.getMessage(), e);
        }
    }

    /**
     * Merges the director's branch, at its last commit, into each worktree of the mission that does not hold it yet.
     * Like every action of the director on workspaces, refused while an agent of the mission is at work. A conflict
     * undoes that merge and is reported: the agent's work is never lost.
     */
    public List<MergeResult> updateAgents(MissionId id) {
        Mission m = mission(id);
        Path project = Path.of(m.workspace());
        String head = workspaces.resolve(project, "HEAD");
        if (head == null) throw RefusedException.conflict(REFUSED, "The project has no commit yet");
        office.query(c -> {
            refuseMerge(c, id);
            return null;
        });
        List<Workspace> copies = office.query(c -> c.workshop().workspaces(id));
        List<MergeResult> results = new ArrayList<>();
        for (Workspace w : copies) {
            if (workspaces.contains(Path.of(w.path()), head)) {
                results.add(new MergeResult(w.agent().value(), true, true, null));
                continue;
            }
            Workspaces.Merge merge = mergeInto(m, w, head);
            results.add(new MergeResult(w.agent().value(), merge.clean(), false, merge.detail()));
        }
        return results;
    }

    /**
     * The check, the merge and its record run under the service's one lock: no session can start in between, so the
     * domain never refuses a merge already made. Should the record fail all the same (the journal), the merge is
     * undone: a worktree never holds a merge the journal does not know.
     */
    private Workspaces.Merge mergeInto(Mission m, Workspace w, String head) {
        Path worktree = Path.of(w.path());
        AgentId agent = w.agent();
        AtomicReference<Workspaces.Merge> made = new AtomicReference<>();
        AtomicReference<String> before = new AtomicReference<>();
        try {
            office.execute(c -> {
                refuseMerge(c, m.id());
                before.set(workspaces.head(worktree));
                Workspaces.Merge merge = workspaces.merge(worktree, List.of(head));
                made.set(merge);
                return c.workshop()
                        .recordProjectMerged(
                                office.context(Actor.director(), m.origin()),
                                m.id(),
                                agent,
                                head,
                                merge.clean(),
                                merge.detail());
            });
        } catch (RuntimeException e) {
            // Handled, then rethrown as is: the merge the journal does not know is undone first.
            if (made.get() != null && made.get().clean()) workspaces.undoMerge(worktree, before.get());
            throw e;
        }
        return made.get();
    }

    /** What refuses a merge into the worktrees, read under the same lock as the merge's record. */
    private static void refuseMerge(dev.floor42.domain.Company c, MissionId id) {
        if (c.sessions().of(id).stream().anyMatch(AgentSession::isOpen)) {
            throw new DomainException("AGENT_AT_WORK", "An agent session is running on " + id + ": wait for it to end");
        }
        if (c.missions().find(id).map(x -> x.status().isTerminal()).orElse(true)) {
            throw new DomainException("MISSION_CLOSED", id + " is closed");
        }
    }

    private static RefusedException invalid(String message) {
        return RefusedException.invalid(REFUSED, message);
    }

    private Mission mission(MissionId id) {
        Mission m =
                office.query(c -> c.missions().find(id)).orElseThrow(() -> new NotFoundException("No mission " + id));
        if (m.workspace() == null || m.workspace().isBlank()) {
            throw RefusedException.conflict(REFUSED, id + " works on no project folder");
        }
        return m;
    }
}
