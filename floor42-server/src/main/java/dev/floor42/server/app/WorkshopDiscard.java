package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Workspace;
import dev.floor42.server.runtime.ConfinedPath;
import dev.floor42.server.runtime.Confinement;
import dev.floor42.server.runtime.Git;
import dev.floor42.server.runtime.GitProcess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The director gives up one pending change of an agent's worktree: a modified or deleted file goes back to the last
 * commit, a new one is deleted. Journaled as the director's own edit of the file, with the diff that was dropped.
 */
@ApplicationScoped
public class WorkshopDiscard {

    private final OfficeService office;
    private final Git git;
    private final Workshop workshop;
    private final AgentReservations reservations;

    @Inject
    public WorkshopDiscard(OfficeService office, Git git, Workshop workshop, AgentReservations reservations) {
        this.office = office;
        this.git = git;
        this.workshop = workshop;
        this.reservations = reservations;
    }

    /** For CDI's client proxy; never used to run git. */
    protected WorkshopDiscard() {
        this(null, null, null, null);
    }

    public String discard(MissionId mission, AgentId agent, String raw) {
        if (raw == null || raw.isBlank()) throw new DomainException("INVALID_ARGUMENT", "path is required");
        Workspace w = workshop.workspace(mission, agent);
        var director = office.context(
                Actor.director(),
                office.query(c -> c.missions().find(mission).orElseThrow().origin()));
        office.query(c -> {
            c.workshop().checkAccess(director, mission, agent);
            return null;
        });
        Path root = Path.of(w.path());
        String path = Confinement.relative(root, raw, ConfinedPath.Mode.WRITE).relative();
        List<String> sides = WorkshopFiles.pick(
                root,
                workshop.changes(w).stream().map(Workshop.Change::path).toList(),
                GitProcess.status(out(root, "status", "--porcelain=v1", "-z", "--untracked-files=all")),
                List.of(path));
        AgentReservations.Reservation held = reservations
                .holdForWorkshop(mission, agent)
                .orElseThrow(() -> new DomainException("AGENT_AT_WORK", Workshop.BUSY));
        try {
            // Recorded first, with the diff: a journal that fails leaves the change in place, never lost unrecorded.
            record(director, mission, agent, path, "Discarded this change", workshop.diff(w, path));
            restoreAll(director, mission, agent, path, root, sides);
            return path;
        } finally {
            reservations.releaseWorkshop(mission, held);
        }
    }

    /** A restore that fails is journaled as such, so the trace never says a change is gone when it may not be. */
    private void restoreAll(
            CommandContext director, MissionId mission, AgentId agent, String path, Path root, List<String> sides) {
        try {
            for (String side : sides) restore(root, side);
        } catch (RuntimeException e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            String note = "Discard failed: the change is still there or only partly undone (" + why + ")";
            try {
                record(director, mission, agent, path, note, "(discard failed)");
            } catch (RuntimeException second) {
                // Handled: logged; the director still gets DISCARD_FAILED below, the journal only lacks the failure
                // note
                org.jboss.logging.Logger.getLogger(WorkshopDiscard.class)
                        .warnf("Discard of %s failed and its failure could not be journaled: %s", path, second);
            }
            throw new DomainException("DISCARD_FAILED", "Could not discard " + path + ": " + why, e);
        }
    }

    private void record(
            CommandContext director, MissionId mission, AgentId agent, String path, String note, String diff) {
        String shown = WorkshopFiles.abbreviate(diff.isBlank() ? "(discarded)" : diff);
        office.execute(c -> c.workshop().recordDirectorEdit(director, mission, agent, path, note, shown));
    }

    /** Back to the last commit: unstaged, then a new file deleted, any other put back as it was. */
    protected void restore(Path root, String side) {
        out(root, "reset", "--quiet", "HEAD", "--", side);
        if (!out(root, "status", "--porcelain=v1", "--untracked-files=all", "--", side)
                .startsWith("??")) {
            out(root, "checkout", "--quiet", "HEAD", "--", side);
            return;
        }
        try {
            Files.deleteIfExists(root.resolve(side));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + side + "; the change is still there", e);
        }
    }

    private String out(Path root, String... args) {
        return git.out(root, args).replace("\r\n", "\n");
    }
}
