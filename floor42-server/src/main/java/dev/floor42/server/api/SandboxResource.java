package dev.floor42.server.api;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Origin;
import dev.floor42.domain.SandboxTools;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.OfficeSettings;
import dev.floor42.server.app.ProjectToolchain;
import dev.floor42.server.app.ProjectToolchain.Need;
import dev.floor42.server.runtime.ClaudeCodeProbe;
import dev.floor42.server.runtime.DockerAccess;
import dev.floor42.server.runtime.HostChecks;
import dev.floor42.server.runtime.InstallRecipes;
import dev.floor42.server.runtime.MachineChecklist;
import dev.floor42.server.runtime.SandboxProbe;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

/**
 * Sandbox readiness (ADR-0033): which tools a project needs, which ones the WSL sandbox really has, and how the director
 * installs the missing ones. Reading never measures: it shows the last measure the journal holds. Measuring is the
 * director's click, one run in the sandbox, journaled. No route installs anything: the commands are for the director
 * to copy.
 */
@Path("/api/sandbox")
@Produces(MediaType.APPLICATION_JSON)
public class SandboxResource {

    /** What a tool's state can be: MISSING only when a measure said so. */
    public enum ToolState {
        PRESENT,
        OLDER,
        MISSING,
        UNKNOWN
    }

    /**
     * One tool.
     *
     * @param wanted the version the project's files ask for; null when none is pinned
     * @param because the project's files that need it; empty for a tool the project does not need but the sandbox has
     * @param path where the probe found it; null unless PRESENT or OLDER
     * @param version the first line of its version output; null unless measured
     * @param note a code the screen explains: {@code docker.unreachable} (the daemon measured out of reach), {@code
     *     docker.reachable} (the daemon answered: a warning), {@code docker.daemon} (the daemon not measured since the start), {@code nvm} (a Node from nvm is invisible in the
     *     sandbox), {@code mvnw} (the wrapper downloads Maven, each time); null otherwise
     */
    public record ToolLine(
            String tool,
            String wanted,
            List<String> because,
            ToolState state,
            String path,
            String version,
            String note) {}

    /**
     * @param project the folder read, null when none was given
     * @param enabled whether the director turned the sandbox on (SHELL_SANDBOX=wsl)
     * @param network whether the sandbox reaches the network (SANDBOX_NETWORK)
     * @param measuredAt when the last measure was taken; null when there is none (since the sandbox was turned on)
     * @param measured false when nothing is measured, or the last probe could not run: every state is UNKNOWN
     * @param reason why the probe could not run, or what it could not finish; null otherwise
     * @param recipes whether Floor42 has install recipes for this distribution (Debian, Ubuntu)
     * @param commands what the director copies into a WSL terminal of their own; Floor42 never runs them
     * @param manual tools to install that Floor42 has no recipe for
     */
    public record SandboxReadiness(
            String project,
            boolean enabled,
            boolean network,
            String distro,
            String os,
            String measuredAt,
            boolean measured,
            String reason,
            boolean recipes,
            List<ToolLine> tools,
            List<String> commands,
            List<String> manual) {}

    /** Tools whose recipe follows the wanted version; for the others the distribution's package is what it is. */
    private static final Set<String> VERSIONED = Set.of("java", "node", "npm");

    @Inject
    OfficeService office;

    @Inject
    OfficeSettings settings;

    @Inject
    SandboxProbe probe;

    @Inject
    dev.floor42.server.runtime.Sandbox sandbox;

    @Inject
    HostChecks host;

    @Inject
    ClaudeCodeProbe claude;

    @GET
    @Path("/readiness")
    public SandboxReadiness readiness(
            @Parameter(
                            description =
                                    "An absolute folder path of the project whose tools are read from its files; the checks command alone when absent.")
                    @QueryParam("project")
                    String project,
            @Parameter(
                            description =
                                    "The checks command of the project; its first word names a tool the sandbox needs.")
                    @QueryParam("check")
                    String check) {
        List<Need> needs = needs(project, check);
        SandboxTools last = office.query(c -> c.preferences().sandboxTools()).orElse(null);
        return view(project, needs, last);
    }

    /**
     * What is missing on this machine: Claude Code (as checked at startup), git and WSL (read now, one short process
     * each), the isolation and the sandbox's tools (its last measure; "measure again" takes a new one). Installs
     * nothing.
     */
    @GET
    @Path("/checklist")
    public MachineChecklist.Checklist checklist() {
        SandboxTools last = office.query(c -> c.preferences().sandboxTools()).orElse(null);
        return dev.floor42.server.runtime.NativeChecks.apply(
                MachineChecklist.of(
                        host.windows(),
                        claude.availability(),
                        host.git(),
                        host.wsl(),
                        probe.distro(),
                        last,
                        probe.docker()),
                dev.floor42.server.runtime.NativeChecks.measure());
    }

    /** Measures the sandbox now (one wsl.exe, 30 s at most), journals it, and returns the readiness. */
    @POST
    @Path("/readiness/probe")
    public SandboxReadiness probe(
            @Parameter(
                            description =
                                    "An absolute folder path of the project whose tools are read from its files; the checks command alone when absent.")
                    @QueryParam("project")
                    String project,
            @Parameter(
                            description =
                                    "The checks command of the project; its first word names a tool the sandbox needs.")
                    @QueryParam("check")
                    String check) {
        List<Need> needs = needs(project, check);
        SandboxProbe.Probe p = probe.probe(needs.stream().map(Need::tool).toList());
        office.execute(c -> c.preferences()
                .recordSandboxProbe(
                        office.context(Actor.system(), Origin.REAL),
                        p.distro(),
                        p.osId(),
                        p.osLike(),
                        p.osVersion(),
                        p.osName(),
                        p.measured(),
                        p.tools().stream().map(SandboxProbe.Tool::line).toList(),
                        p.reason()));
        // A sandbox repaired while the daemon runs is seen at once, without a restart.
        if (sandbox.enabled()) sandbox.recheck();
        return readiness(project, check);
    }

    private static List<Need> needs(String project, String check) {
        if (project == null || project.isBlank()) return ProjectToolchain.read(null, check);
        java.nio.file.Path dir = java.nio.file.Path.of(project).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) throw new NotFoundException("Not a folder: " + project);
        return ProjectToolchain.read(dir, check);
    }

    private SandboxReadiness view(String project, List<Need> needs, SandboxTools last) {
        boolean measured = last != null && last.measured();
        String osId = last == null ? null : last.osId();
        String osLike = last == null ? null : last.osLike();
        String osVersion = last == null ? null : last.osVersion();
        List<ToolLine> lines = new ArrayList<>();
        List<InstallRecipes.Recipe> toInstall = new ArrayList<>();
        List<String> manual = new ArrayList<>();
        for (Need n : needs) {
            Optional<SandboxTools.Tool> t = measured ? last.tool(n.tool()) : Optional.empty();
            ToolState state = stateOf(n, t);
            lines.add(needLine(n, t, state, probe.docker()));
            if (state == ToolState.MISSING || state == ToolState.OLDER) {
                InstallRecipes.Recipe r = InstallRecipes.recipe(n.tool(), n.wanted(), osId, osLike, osVersion);
                // An older tool is only fixed by a recipe that follows the version: else the director decides.
                if (r.manual() || state == ToolState.OLDER && !VERSIONED.contains(n.tool())) manual.add(n.tool());
                else toInstall.add(r);
            }
        }
        if (measured) lines.addAll(extras(last, needs, probe.docker()));
        return new SandboxReadiness(
                project == null || project.isBlank() ? null : project,
                settings.sandboxEnabled() || settings.sandboxRequired(),
                settings.sandboxNetwork(),
                last == null ? probe.distro() : last.distro(),
                last == null ? null : last.osName(),
                last == null ? null : last.measuredAt().toString(),
                measured,
                last == null ? null : last.reason(),
                measured && InstallRecipes.supported(osId, osLike),
                lines,
                InstallRecipes.commands(toInstall),
                manual);
    }

    private static ToolState stateOf(Need n, Optional<SandboxTools.Tool> t) {
        if (t.isEmpty()) return ToolState.UNKNOWN;
        if (!t.get().present()) return ToolState.MISSING;
        return older(n.wanted(), t.get().version()) ? ToolState.OLDER : ToolState.PRESENT;
    }

    private static ToolLine needLine(
            Need n, Optional<SandboxTools.Tool> t, ToolState state, DockerAccess.Measure docker) {
        Optional<SandboxTools.Tool> present = t.filter(SandboxTools.Tool::present);
        return new ToolLine(
                n.tool(),
                n.wanted(),
                n.because(),
                state,
                present.map(SandboxTools.Tool::path).orElse(null),
                present.map(SandboxTools.Tool::version).orElse(null),
                note(n, state, docker));
    }

    /** What the sandbox has beyond the project's needs: shown, never asked for. */
    private static List<ToolLine> extras(SandboxTools last, List<Need> needs, DockerAccess.Measure docker) {
        List<ToolLine> lines = new ArrayList<>();
        for (SandboxTools.Tool t : last.tools()) {
            if (!t.present() || needs.stream().anyMatch(n -> n.tool().equals(t.name()))) continue;
            lines.add(new ToolLine(
                    t.name(),
                    null,
                    List.of(),
                    ToolState.PRESENT,
                    t.path(),
                    t.version(),
                    t.name().equals("docker") ? DockerAccess.note(docker) : null));
        }
        return lines;
    }

    private static String note(Need n, ToolState state, DockerAccess.Measure docker) {
        if (n.tool().equals("docker") && state != ToolState.MISSING) return DockerAccess.note(docker);
        if (n.tool().equals("node") && n.because().contains(".nvmrc")) return "nvm";
        if (n.tool().equals("java") && n.because().contains("mvnw")) return "mvnw";
        return null;
    }

    private static final Pattern NUMBER = Pattern.compile("\\d{1,9}+(?:\\.\\d{1,9}+)*+");

    /**
     * Whether the measured version is below the wanted one, compared number by number on the wanted's precision ("21"
     * against "25.0.1" compares 21 with 25). A wanted range bounded from above only, or a channel name, compares
     * nothing.
     */
    static boolean older(String wanted, String version) {
        if (wanted == null || version == null) return false;
        if (wanted.contains("<") && !wanted.contains(">")) return false;
        Matcher w = NUMBER.matcher(wanted);
        Matcher v = NUMBER.matcher(version);
        if (!w.find() || !v.find()) return false;
        String[] want = w.group().split("\\.");
        String[] have = v.group().split("\\.");
        for (int i = 0; i < want.length; i++) {
            long a = Long.parseLong(want[i]);
            long b = i < have.length ? Long.parseLong(have[i]) : 0;
            if (b != a) return b < a;
        }
        return false;
    }
}
