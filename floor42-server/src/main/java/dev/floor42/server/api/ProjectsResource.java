package dev.floor42.server.api;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.Origin;
import dev.floor42.server.app.NewFolder;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.ProjectInit;
import dev.floor42.server.app.RefusedException;
import dev.floor42.server.app.Workspaces;
import dev.floor42.server.app.Workspaces.RepositoryFacts;
import dev.floor42.server.runtime.GitFailure;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

/**
 * The projects a mission can work on (W2): those real missions already used, and a folder browser to discover another
 * one on this machine. Read only: it lists folder names and what git says of a repository, never a file's content, and
 * changes nothing, but for making a folder a repository (init) or a new empty folder when the director asks. Git facts are read from git
 * when asked.
 */
@Path("/api/projects")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectsResource {

    /** At most this many folders in one listing: a larger folder is cut, and says so. */
    static final int MAX_ENTRIES = 400;

    /**
     * @param missions the real missions that worked on it, newest first, archived ones left out (ADR-0054)
     * @param office true for a project Floor42 created itself for an idea, under its data directory
     * @param git null when the folder is gone or no longer a git repository
     * @param lastMission the newest real mission on it not archived; {@code lastTitle} is its title; both null when
     *     every mission on it is archived
     * @param lastActivity when anything last happened to one of its missions (ISO-8601, from the journal)
     * @param pinned the director pinned it at the top of the recent list
     * @param forgotten the director took it off the recent list, and no mission was created on it since
     */
    public record ProjectView(
            String path,
            List<String> missions,
            String lastTitle,
            boolean office,
            boolean exists,
            RepositoryFacts git,
            String lastMission,
            String lastActivity,
            boolean pinned,
            boolean forgotten) {
        public ProjectView {
            missions = missions == null ? null : Collections.unmodifiableList(new ArrayList<>(missions));
        }
    }

    /** @param pinned true to pin, false to unpin */
    public record PinRequest(String path, boolean pinned) {}

    public record ForgetRequest(String path) {}

    /** @param repository true when the folder holds a {@code .git}: a candidate to work on */
    public record FolderEntry(String name, String path, boolean repository) {}

    /**
     * @param path null for the list of starting points (the home folder, the drives)
     * @param git what git says of this folder, null when it is not a repository
     * @param truncated true when the folder holds more than {@link #MAX_ENTRIES} folders
     */
    public record FolderView(
            String path, String parent, RepositoryFacts git, List<FolderEntry> folders, boolean truncated) {
        public FolderView {
            folders = folders == null ? null : Collections.unmodifiableList(new ArrayList<>(folders));
        }
    }

    @Inject
    OfficeService office;

    @Inject
    Workspaces workspaces;

    @Inject
    ProjectInit projectInit;

    @ConfigProperty(name = "floor42.data-dir")
    java.nio.file.Path dataDir;

    /**
     * The repositories real missions worked on, pinned ones first, then by their latest activity. Forgotten ones are
     * listed too, marked: the picker leaves them out.
     */
    @GET
    public List<ProjectView> list() {
        Map<String, Marks> marks = new java.util.HashMap<>();
        Map<String, List<Mission>> byPath = office.query(c -> byPath(c, marks));
        // Office's former data folder too: projects made for ideas before 1.5.0 stay there.
        List<java.nio.file.Path> data = dev.floor42.server.config.DataFolders.guarded(dataDir).stream()
                .map(d -> d.toAbsolutePath().normalize())
                .toList();
        return byPath.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, List<Mission>> e) ->
                                !marks.get(e.getKey()).pinned())
                        .thenComparing(
                                e -> marks.get(e.getKey()).last(), Comparator.nullsLast(Comparator.reverseOrder())))
                .map(e -> view(e.getKey(), e.getValue(), marks.get(e.getKey()), data))
                .toList();
    }

    /** A project's marks: pinned, forgotten, and its latest activity. */
    private record Marks(boolean pinned, boolean forgotten, java.time.Instant last) {}

    /** The real missions by repository, newest first; each repository's marks go to {@code marks}. */
    private static Map<String, List<Mission>> byPath(Company c, Map<String, Marks> marks) {
        Map<String, List<Mission>> m = new LinkedHashMap<>();
        c.missions().all().stream()
                .filter(x -> x.origin() == Origin.REAL
                        && x.workspace() != null
                        && !x.workspace().isBlank())
                .sorted(Comparator.comparingInt((Mission x) -> number(x.id().value()))
                        .reversed())
                .forEach(x ->
                        m.computeIfAbsent(x.workspace(), k -> new ArrayList<>()).add(x));
        m.forEach((path, ms) -> marks.put(
                path,
                new Marks(
                        c.projects()
                                .mark(path)
                                .map(dev.floor42.domain.ProjectMark::pinned)
                                .orElse(false),
                        c.projects().forgotten(path),
                        ms.stream()
                                .map(x -> c.queries().lastActivity(x.id()).orElse(x.createdAt()))
                                .max(java.time.Instant::compareTo)
                                .orElse(null))));
        return m;
    }

    private ProjectView view(String path, List<Mission> all, Marks mark, List<java.nio.file.Path> data) {
        java.nio.file.Path dir = java.nio.file.Path.of(path);
        boolean exists = Files.isDirectory(dir);
        // Archived missions are put away (ADR-0054): the project stays listed, its activity counts, they are not named.
        List<Mission> missions = all.stream().filter(x -> !x.archived()).toList();
        Mission last = missions.isEmpty() ? null : missions.getFirst();
        return new ProjectView(
                path,
                missions.stream().map(x -> x.id().value()).toList(),
                last == null ? null : last.title(),
                data.stream().anyMatch(dir.toAbsolutePath().normalize()::startsWith),
                exists,
                exists ? workspaces.facts(dir).orElse(null) : null,
                last == null ? null : last.id().value(),
                mark.last() == null ? null : mark.last().toString(),
                mark.pinned(),
                mark.forgotten());
    }

    /** Pins a project at the top of the recent list, or unpins it; journaled (ProjectPinned). */
    @POST
    @Path("/pin")
    @Consumes(MediaType.APPLICATION_JSON)
    public List<ProjectView> pin(PinRequest request) {
        if (request == null || request.path() == null) throw new BadRequestException("path is required");
        office.execute(
                c -> c.projects().pin(office.context(Actor.director(), Origin.REAL), request.path(), request.pinned()));
        return list();
    }

    /** Takes a project off the recent list, its missions untouched; journaled (ProjectForgotten). */
    @POST
    @Path("/forget")
    @Consumes(MediaType.APPLICATION_JSON)
    public List<ProjectView> forget(ForgetRequest request) {
        if (request == null || request.path() == null) throw new BadRequestException("path is required");
        office.execute(c -> c.projects().forget(office.context(Actor.director(), Origin.REAL), request.path()));
        return list();
    }

    @GET
    @Path("/folders")
    public FolderView folders(
            @Parameter(
                            description =
                                    "An absolute folder path on the machine of the daemon; the roots are listed when absent for folders, and it is required for init.")
                    @QueryParam("path")
                    String path) {
        if (path == null || path.isBlank()) return roots();
        java.nio.file.Path dir = java.nio.file.Path.of(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) throw new NotFoundException("Not a folder: " + path);
        List<FolderEntry> entries = new ArrayList<>();
        boolean truncated = false;
        try (Stream<java.nio.file.Path> children = Files.list(dir)) {
            var sorted = children.filter(ProjectsResource::visibleFolder)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .limit(MAX_ENTRIES + 1L)
                    .toList();
            truncated = sorted.size() > MAX_ENTRIES;
            sorted.stream()
                    .limit(MAX_ENTRIES)
                    .forEach(p ->
                            entries.add(new FolderEntry(p.getFileName().toString(), p.toString(), isRepository(p))));
        } catch (IOException | SecurityException e) {
            // Handled: A folder this account cannot read lists as empty: the director sees it, nothing is guessed.
        }
        java.nio.file.Path parent = dir.getParent();
        return new FolderView(
                dir.toString(),
                parent == null ? null : parent.toString(),
                isRepository(dir) ? workspaces.facts(dir).orElse(null) : null,
                entries,
                truncated);
    }

    /** @param parent the folder being browsed; @param name the one folder name to create in it */
    public record NewFolderRequest(String parent, String name) {}

    /**
     * Creates one empty folder in the folder being browsed and returns it listed, so the browser shows and selects it.
     * The checks are NewFolder's: a plain name, a parent judged by its real path, never a protected folder.
     */
    @POST
    @Path("/folders")
    @Consumes(MediaType.APPLICATION_JSON)
    public FolderView newFolder(NewFolderRequest request) {
        if (request == null) throw RefusedException.invalid(NewFolder.NAME_INVALID, "parent and name are required");
        return folders(
                NewFolder.create(request.parent(), request.name(), dataDir).toString());
    }

    /** Where browsing starts: the home folder, then every root of the file system. */
    private static FolderView roots() {
        List<FolderEntry> roots = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home != null) roots.add(new FolderEntry(home, home, isRepository(java.nio.file.Path.of(home))));
        for (java.io.File r : java.io.File.listRoots()) {
            roots.add(new FolderEntry(r.getPath(), r.getPath(), false));
        }
        return new FolderView(null, null, null, roots, false);
    }

    public record InitRequest(String path) {}

    /** What making a folder a repository would do (how many files the first commit would hold), changing nothing. */
    @GET
    @Path("/init")
    public ProjectInit.InitPreview previewInit(
            @Parameter(
                            description =
                                    "An absolute folder path on the machine of the daemon; the roots are listed when absent for folders, and it is required for init.")
                    @QueryParam("path")
                    String path) {
        if (path == null || path.isBlank()) throw new BadRequestException("path is required");
        return projectInit.preview(java.nio.file.Path.of(path));
    }

    /** Makes a folder a git repository with a first commit, so a mission can work on it. The director asks. */
    @POST
    @Path("/init")
    @Consumes(MediaType.APPLICATION_JSON)
    public RepositoryFacts init(InitRequest request) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            throw new BadRequestException("path is required");
        }
        try {
            return projectInit.init(java.nio.file.Path.of(request.path()));
        } catch (GitFailure | java.nio.file.InvalidPathException e) {
            // git itself failed on the folder, or the path is not one (Floor42's own refusals come as
            // RefusedException, with their status).
            throw RefusedException.conflict("INIT_REFUSED", e.getMessage(), e);
        }
    }

    private static boolean visibleFolder(java.nio.file.Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        try {
            return !name.startsWith(".") && !name.startsWith("$") && Files.isDirectory(p) && !Files.isHidden(p);
        } catch (IOException e) {
            // Handled: a folder whose attributes cannot be read is not offered
            return false;
        }
    }

    private static boolean isRepository(java.nio.file.Path p) {
        return Files.exists(p.resolve(".git"));
    }

    private static int number(String id) {
        try {
            return Integer.parseInt(id.substring(id.indexOf('-') + 1));
        } catch (RuntimeException e) {
            // Handled: an id without a number sorts first
            return 0;
        }
    }
}
