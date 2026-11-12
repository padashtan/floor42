package dev.floor42.server.app;

import dev.floor42.domain.DomainException;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.ProofTarget;
import dev.floor42.domain.TicketKind;
import dev.floor42.domain.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Each producer's worktree as it is now, for a delivery's proof (ADR-0058): its recipe, its code (commit and work not
 * committed) and the target a run, a director's report or a verdict is bound to. Read only.
 */
@ApplicationScoped
public class ProofReader {

    /**
     * One worktree now.
     *
     * @param code null when git did not answer, or the folder is not one Floor42 made: never taken for a known state
     */
    public record Current(Workspace workspace, ProofRecipe recipe, ProofSnapshot.Code code) {

        /** What a proof of this worktree is about now; null without a recipe file or a known code. */
        public ProofTarget target() {
            return recipe.digest() == null || code == null
                    ? null
                    : new ProofTarget(workspace.agent(), recipe.digest(), code.head(), code.changesDigest());
        }

        public Path dir() {
            return Path.of(workspace.path());
        }
    }

    @Inject
    OfficeService office;

    @Inject
    MissionBranches branches;

    @Inject
    ProofSnapshot snapshot;

    public List<Current> current(MissionId id) {
        mission(id);
        return producers(id).stream().map(this::current).toList();
    }

    Current current(Workspace w) {
        Path dir = Path.of(w.path());
        boolean ours = Files.isDirectory(dir) && branches.madeByFloor42(dir);
        return new Current(w, read(dir, ours), ours ? code(dir) : null);
    }

    private ProofSnapshot.Code code(Path dir) {
        try {
            return snapshot.code(dir);
        } catch (RuntimeException e) {
            // Handled: git failed, a file could not be read: the code is unknown, said as such (the acceptance asks),
            // never a failed request; nothing is bound to an unknown code
            return null;
        }
    }

    Mission mission(MissionId id) {
        return office.query(c ->
                c.missions().find(id).orElseThrow(() -> new DomainException("UNKNOWN_MISSION", "No mission " + id)));
    }

    /** The worktrees of the agents who produced the mission's work: their recipes are the delivery's proof. */
    List<Workspace> producers(MissionId id) {
        return office.query(c -> {
            Set<String> producers = new HashSet<>();
            c.tickets().of(id).stream()
                    .filter(t -> t.kind() == TicketKind.WORK && t.assignee() != null)
                    .forEach(t -> producers.add(t.assignee().value()));
            return c.workshop().workspaces(id).stream()
                    .filter(w -> producers.contains(w.agent().value()))
                    .toList();
        });
    }

    /** Only a folder Floor42 made is read, and {@code PROVE.md} must be a plain file in it, never a link. */
    ProofRecipe read(Path dir) {
        return read(dir, Files.isDirectory(dir) && branches.madeByFloor42(dir));
    }

    private ProofRecipe read(Path dir, boolean ours) {
        if (!Files.isDirectory(dir)) return ProofRecipe.unknown("WORKTREE_MISSING");
        if (!ours) return ProofRecipe.unknown("NOT_A_MISSION_WORKTREE");
        Path file = dir.resolve(ProofRecipe.FILE);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return ProofRecipe.missing();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return ProofRecipe.unknown("NOT_A_FILE");
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return ProofRecipe.parse(in.readNBytes(ProofRecipe.MAX_BYTES + 1));
        } catch (IOException e) {
            // Handled: a recipe Floor42 cannot read is said, never taken for a pass
            return ProofRecipe.unknown("UNREADABLE");
        }
    }
}
