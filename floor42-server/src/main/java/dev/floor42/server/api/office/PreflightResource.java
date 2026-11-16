package dev.floor42.server.api.office;

import dev.floor42.domain.Mission;
import dev.floor42.domain.Origin;
import dev.floor42.domain.SandboxTools;
import dev.floor42.server.app.core.OfficeService;
import dev.floor42.server.app.core.OfficeSettings;
import dev.floor42.server.app.mission.Preflight;
import dev.floor42.server.app.mission.ProjectProfile;
import dev.floor42.server.app.mission.ProjectToolchain.Need;
import dev.floor42.server.app.workspace.Workspaces;
import dev.floor42.server.app.workspace.Workspaces.RepositoryFacts;
import dev.floor42.server.runtime.ClaudeCodeProbe;
import dev.floor42.server.runtime.Sandbox;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

/**
 * What stands in the way of a real mission on a folder, before the click (ADR-0070): the folder's profile read from its
 * files, a proposed checks command, and one list of blockers and warnings. Read only: it measures nothing (the sandbox
 * tools come from the last measure) and journals nothing. The two blockers mirror refusals the server makes anyway, at
 * the creation of the mission.
 */
@Path("/api/preflight")
@Produces(MediaType.APPLICATION_JSON)
public class PreflightResource {

    /**
     * @param buildTool null when no build file names one
     * @param files the files that say so
     * @param branch null when the folder is not a git work tree or HEAD is detached
     * @param suggestedChecks the table's command for the build tool: proposed, never run; null when none
     * @param lastChecks the checks command of the newest real mission on this folder; null when none
     */
    public record ProfileView(
            String buildTool, List<String> files, String branch, String suggestedChecks, String lastChecks) {}

    /**
     * @param level BLOCK, WARN, INFO or OK
     * @param params the words the screen puts in the line's text
     */
    public record LineView(String code, String level, Map<String, String> params) {}

    /** @param verdict BLOCKED, WARNINGS or READY */
    public record PreflightView(String project, String verdict, ProfileView profile, List<LineView> lines) {}

    @Inject
    OfficeService office;

    @Inject
    OfficeSettings settings;

    @Inject
    Workspaces workspaces;

    @Inject
    ClaudeCodeProbe claude;

    @Inject
    Sandbox sandbox;

    @GET
    public PreflightView preflight(
            @Parameter(description = "An absolute folder path: the project a mission would work on.")
                    @QueryParam("project")
                    String project,
            @Parameter(description = "The checks command typed in the form; empty when none.") @QueryParam("check")
                    String check) {
        if (project == null || project.isBlank()) throw new BadRequestException("project is required");
        List<Need> needs = SandboxResource.needs(project, check); // 404 for a path that is not a folder
        java.nio.file.Path dir = java.nio.file.Path.of(project).toAbsolutePath().normalize();
        ProjectProfile.Profile profile = ProjectProfile.read(dir);
        Optional<RepositoryFacts> git = workspaces.facts(dir);
        String last = lastChecks(dir);
        Preflight.Result result = Preflight.of(facts(needs, profile, git, last, check));
        return new PreflightView(
                dir.toString(),
                result.verdict().name(),
                new ProfileView(
                        profile.buildTool(),
                        profile.files(),
                        git.map(RepositoryFacts::branch)
                                .filter(b -> !b.isBlank())
                                .orElse(null),
                        profile.suggestedChecks(),
                        last),
                result.lines().stream()
                        .map(l -> new LineView(l.code(), l.level().name(), l.params()))
                        .toList());
    }

    /** What the sandbox, Claude Code and the folder say, as the rules of {@link Preflight} read them. */
    private Preflight.Facts facts(
            List<Need> needs,
            ProjectProfile.Profile profile,
            Optional<RepositoryFacts> git,
            String last,
            String check) {
        boolean given = check != null && !check.isBlank();
        boolean inSandbox = sandbox.enabled();
        ClaudeCodeProbe.Availability runtime = claude.availability();
        return new Preflight.Facts(
                runtime.available(),
                runtime.reason(),
                runtime.code(),
                inSandbox,
                !inSandbox || sandbox.available(),
                git.isPresent(),
                git.map(g -> g.commit() != null).orElse(false),
                git.map(RepositoryFacts::uncommitted).orElse(0),
                settings.sandboxNetwork(),
                profile.downloads(),
                toolsOf(needs),
                given || last != null || profile.suggestedChecks() != null);
    }

    /** Each need with its state in the sandbox, from the last measure. */
    private List<Preflight.Tool> toolsOf(List<Need> needs) {
        SandboxTools measure = office.query(c -> c.preferences().sandboxTools()).orElse(null);
        List<Preflight.Tool> tools = new ArrayList<>();
        for (Need n : needs) {
            Optional<SandboxTools.Tool> t =
                    measure != null && measure.measured() ? measure.tool(n.tool()) : Optional.empty();
            SandboxResource.ToolState s = SandboxResource.stateOf(n, t);
            tools.add(new Preflight.Tool(
                    n.tool(),
                    n.wanted(),
                    n.because(),
                    Preflight.ToolState.valueOf(s.name()),
                    t.filter(SandboxTools.Tool::present)
                            .map(SandboxTools.Tool::version)
                            .orElse(null)));
        }
        return tools;
    }

    /** The checks command of the newest real mission on {@code dir}, from the journal's state. */
    private String lastChecks(java.nio.file.Path dir) {
        return office.query(c -> c.missions().all().stream()
                .filter(m -> m.origin() == Origin.REAL
                        && m.workspace() != null
                        && !m.workspace().isBlank()
                        && java.nio.file.Path.of(m.workspace())
                                .toAbsolutePath()
                                .normalize()
                                .equals(dir)
                        && m.checkCommand() != null
                        && !m.checkCommand().isBlank())
                .max(Comparator.comparing(Mission::createdAt))
                .map(Mission::checkCommand)
                .orElse(null));
    }
}
