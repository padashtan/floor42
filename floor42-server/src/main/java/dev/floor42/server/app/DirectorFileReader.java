package dev.floor42.server.app;

import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.server.app.Delivered.Blob;
import dev.floor42.server.app.Delivered.DeliveredFile;
import dev.floor42.server.runtime.ConfinedPath;
import dev.floor42.server.runtime.Confinement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * One of the director's own pending files, read for the viewer panel (ADR-0051): only a path that {@code GET
 * .../files} lists as pending in the mission's project, inside the project by its real path, never under {@code .git},
 * never through a link. Reading is never journaled.
 */
@ApplicationScoped
public class DirectorFileReader {

    /**
     * What the viewer needs to decide before it asks for the bytes: the file as a listing entry, and the view caps.
     *
     * @param file its status is {@code ?} when it is on disk, {@code D} when git lists it but it is gone
     */
    public record DirectorFileView(DeliveredFile file, long maxViewText, long maxViewBytes) {}

    private final OfficeService office;
    private final Workspaces workspaces;

    @Inject
    public DirectorFileReader(OfficeService office, Workspaces workspaces) {
        this.office = office;
        this.workspaces = workspaces;
    }

    public DirectorFileView entry(MissionId id, String path) {
        ConfinedPath confined = pending(id, path);
        Path file = confined.file();
        boolean link = Files.isSymbolicLink(file) || WorktreeScanner.isJunction(file, confined.root());
        boolean present = link || Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
        Long size = null;
        if (present && !link) {
            try {
                size = Files.size(file);
            } catch (IOException e) {
                // Handled: the size stays unknown; reading the bytes says why, Download stays
                size = null;
            }
        }
        DeliveredFile entry = new DeliveredFile(
                confined.relative(),
                null,
                present ? "?" : "D",
                size,
                null,
                false,
                false,
                link,
                Deliverables.kind(confined.relative()));
        return new DirectorFileView(entry, Deliverables.MAX_VIEW_TEXT, Deliverables.MAX_VIEW_BYTES);
    }

    /** The bytes of a pending file, refused for anything else (see the class). */
    public Blob file(MissionId id, String path) {
        ConfinedPath confined = pending(id, path);
        return BlobSource.onDisk(confined.root(), confined.file(), confined.relative());
    }

    private ConfinedPath pending(MissionId id, String path) {
        if (path == null || path.isBlank() || path.indexOf('\0') >= 0) {
            throw RefusedException.invalid("INVALID_ARGUMENT", "path is required");
        }
        Mission m =
                office.query(c -> c.missions().find(id)).orElseThrow(() -> new NotFoundException("No mission " + id));
        if (m.workspace() == null || m.workspace().isBlank()) {
            throw RefusedException.conflict("FILES_REFUSED", id + " works on no project folder");
        }
        ConfinedPath confined = Confinement.relative(Path.of(m.workspace()), path, ConfinedPath.Mode.READ);
        if (confined.isRoot()) throw RefusedException.invalid("NOT_A_FILE", "the project itself is not a file");
        // Only what git lists as pending, as the director's panel shows it: never the rest of the project or the disk.
        if (!workspaces.pending(confined.root()).contains(confined.relative())) {
            throw RefusedException.invalid(
                    "NOT_A_PENDING_FILE", confined.relative() + " is not a pending file of the project");
        }
        return confined;
    }
}
