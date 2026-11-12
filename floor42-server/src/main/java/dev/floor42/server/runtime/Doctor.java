package dev.floor42.server.runtime;

import dev.floor42.domain.SandboxTools;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * {@code floor42 doctor}: the machine checklist from the command line, with no daemon running. It measures everything
 * now (Claude Code, git, WSL, then one run in the sandbox for its tools, in a temporary folder removed afterwards),
 * prints the checklist and exits 0 when every required item is present, 1 otherwise. It installs nothing, changes no
 * setting, and prints no secret: only versions, states and commands to copy.
 */
public final class Doctor {

    private Doctor() {}

    public static void main(String[] args) {
        System.exit(run(new HostChecks(), Doctor::claude, null, dataChecks(), System.out));
    }

    /** The data folder as the daemon resolves it: FLOOR42_DATA_DIR, else ~/.floor42; backups in FLOOR42_BACKUP_DIR. */
    private static List<DataChecks.Item> dataChecks() {
        String d = System.getenv("FLOOR42_DATA_DIR");
        Path data = d == null || d.isBlank() ? Path.of(System.getProperty("user.home"), ".floor42") : Path.of(d);
        String b = System.getenv("FLOOR42_BACKUP_DIR");
        Path backups = b == null || b.isBlank() ? data.resolve("backups") : Path.of(b);
        return DataChecks.check(data, backups, Instant.now(), DataChecks.MIN_FREE_BYTES, DataChecks::realPort);
    }

    /**
     * Measures, prints the checklist, and returns the exit code: 0 when every required item is present, 1 otherwise.
     *
     * @param host the machine's checks (tests give it recorded outputs)
     * @param claude Claude Code's availability, given whether the machine runs Windows
     * @param sandbox the process seam of the sandbox probe; null for the real wsl.exe
     */
    static int run(
            HostChecks host,
            Function<Boolean, ClaudeCodeProbe.Availability> claude,
            SandboxProbe.ProbeRunner sandbox,
            PrintStream out) {
        return run(host, claude, sandbox, List.of(), out);
    }

    /** As above, then the data folder's items (warnings only: they never change the exit code). */
    static int run(
            HostChecks host,
            Function<Boolean, ClaudeCodeProbe.Availability> claude,
            SandboxProbe.ProbeRunner sandbox,
            List<DataChecks.Item> data,
            PrintStream out) {
        String distro = distro();
        boolean windows = host.windows();
        ClaudeCodeProbe.Availability a = claude.apply(windows);
        String git = host.git();
        HostChecks.Wsl wsl = host.wsl();
        Measured m = SandboxHost.supported(SandboxHost.current()) ? measure(distro, sandbox) : null;
        MachineChecklist.Checklist c = m == null
                ? MachineChecklist.of(windows, a, git, wsl, distro, null)
                : MachineChecklist.of(windows, a, git, wsl, distro, m.tools(), m.docker());
        c = NativeChecks.apply(c, NativeChecks.measure());
        out.print(MachineChecklist.text(c));
        if (!data.isEmpty()) out.print(DataChecks.text(data));
        out.flush();
        return c.ready() ? 0 : 1;
    }

    /** The distribution the daemon would use: FLOOR42_SANDBOX_DISTRO as the daemon reads it, else Debian. */
    static String distro() {
        String d = System.getenv("FLOOR42_SANDBOX_DISTRO");
        return d == null || !d.matches("[A-Za-z0-9._-]{1,64}") ? "Debian" : d;
    }

    /** claude, then on Windows the npm launcher (claude.cmd) a ProcessBuilder does not find by itself. */
    private static ClaudeCodeProbe.Availability claude(boolean windows) {
        ClaudeCodeProbe.Availability a = ClaudeCodeProbe.probe(List.of("claude"));
        if (a.available() || !windows || a.version() != null) return a;
        ClaudeCodeProbe.Availability cmd = ClaudeCodeProbe.probe(List.of("claude.cmd"));
        return cmd.available() || cmd.version() != null ? cmd : a;
    }

    /** One run of the tools probe through the real sandbox script, network cut, in a scratch folder. */
    private static Measured measure(String distro, SandboxProbe.ProbeRunner runner) {
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("floor42-doctor"); // NOSONAR owner-only temp
            SandboxProbe probe = probe(distro, scratch, runner);
            SandboxProbe.Probe p = probe.probe(List.of());
            return new Measured(tools(p), probe.docker());
        } catch (IOException e) {
            // Handled: nothing measured, the checklist says so
            return new Measured(
                    new SandboxTools(distro, null, null, null, null, false, List.of(), e.getMessage(), Instant.now()),
                    DockerAccess.Measure.unknown(e.getMessage(), Instant.now()));
        } finally {
            delete(scratch);
        }
    }

    /** What one probe measured: the tools, and the Docker daemon (never journaled). */
    private record Measured(SandboxTools tools, DockerAccess.Measure docker) {}

    private static SandboxTools tools(SandboxProbe.Probe p) {
        return new SandboxTools(
                p.distro(),
                p.osId(),
                p.osLike(),
                p.osVersion(),
                p.osName(),
                p.measured(),
                p.tools().stream()
                        .map(t -> new SandboxTools.Tool(t.name(), t.path(), t.version()))
                        .toList(),
                p.reason(),
                Instant.now());
    }

    /** The daemon's own probe and sandbox script, built without injection, network cut, data in the scratch folder. */
    private static SandboxProbe probe(String distro, Path scratch, SandboxProbe.ProbeRunner runner) {
        Sandbox sandbox = new Sandbox();
        sandbox.distro = distro;
        sandbox.dataDir = scratch;
        sandbox.policy = new SandboxPolicy() {
            @Override
            public boolean sandboxEnabled() {
                return true;
            }

            @Override
            public boolean sandboxNetwork() {
                return false;
            }
        };
        SandboxProbe probe = new SandboxProbe();
        probe.sandbox = sandbox;
        probe.distro = distro;
        probe.dataDir = scratch;
        // Only called on Windows or Linux (the caller checked): the probe's own check agrees.
        probe.useForTests(runner, SandboxHost::current);
        return probe;
    }

    private static void delete(Path dir) {
        if (dir == null) return;
        try (Stream<Path> all = Files.walk(dir)) {
            all.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // Handled: a temporary folder left behind is the system's to clean
        }
    }
}
