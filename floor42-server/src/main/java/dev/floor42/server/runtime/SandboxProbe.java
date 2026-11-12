package dev.floor42.server.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Measures which tools the shell sandbox really has (ADR-0033): ONE {@code wsl.exe} through {@link Sandbox#argv}, so
 * the same namespaces and PATH as real work, in a scratch folder (no project path reaches it). It reads
 * /etc/os-release, then for each tool {@code command -v} and the first line of its version, and prints lines
 * {@code tool|path|version}. Anything that keeps it from measuring (not Windows, no wsl.exe, no distribution, the
 * 30-second limit) gives UNKNOWN for every tool, never MISSING: a tool is missing only when the probe ran and said so.
 */
@ApplicationScoped
public class SandboxProbe {

    /** The probe's whole run: a version command that hangs must not hold the director's click. */
    public static final Duration LIMIT = Duration.ofSeconds(30);

    /** Printed last: without it the output was cut and only complete lines before it count. */
    static final String END = "floor42-probe-end";

    /**
     * The tools always asked about, whatever the project: the usual build tools, then the essential ones
     * ({@link SandboxToolkit}) under each of their command names.
     */
    public static final List<String> BASELINE = baseline();

    private static List<String> baseline() {
        LinkedHashSet<String> all = new LinkedHashSet<>(List.of(
                "git", "java", "mvn", "gradle", "node", "npm", "python3", "pip3", "go", "cargo", "rustc", "make",
                "docker", "curl"));
        all.addAll(SandboxToolkit.commands());
        return List.copyOf(all);
    }

    /** What one run of the probe returned. */
    public record RunResult(String output, Integer exitCode, boolean timedOut) {}

    /** The seam that starts the process: tests give recorded outputs instead. */
    public interface ProbeRunner {
        RunResult run(List<String> argv, Duration limit) throws IOException;
    }

    /**
     * One measure.
     *
     * @param tools by name; only tools whose line was complete (a cut output leaves the others out: unknown)
     * @param reason why nothing was measured; null when measured
     */
    public record Probe(
            String distro,
            String osId,
            String osLike,
            String osVersion,
            String osName,
            boolean measured,
            List<Tool> tools,
            String reason) {

        static Probe unknown(String distro, String reason) {
            return new Probe(distro, null, null, null, null, false, List.of(), reason);
        }
    }

    /** @param path empty when the probe measured it missing */
    public record Tool(String name, String path, String version) {

        public String line() {
            return name + "|" + path + "|" + version;
        }
    }

    @Inject
    Sandbox sandbox;

    @ConfigProperty(name = "floor42.sandbox.distro", defaultValue = "Debian")
    String distro;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    private volatile ProbeRunner runner = SandboxProbe::start;
    private volatile Supplier<String> osName = () -> System.getProperty("os.name");
    private volatile DockerAccess.Measure docker = DockerAccess.Measure.unknown(null, null);

    /** For tests: the process seam and the operating system Floor42 believes it runs on. Null puts the real one back. */
    public void useForTests(ProbeRunner runner, Supplier<String> osName) {
        this.runner = runner == null ? SandboxProbe::start : runner;
        this.osName = osName == null ? () -> System.getProperty("os.name") : osName;
    }

    public String distro() {
        return distro;
    }

    /** Measures the given tools (plus the baseline) in the sandbox now. Never throws: what fails is UNKNOWN. */
    public synchronized Probe probe(Collection<String> wanted) {
        List<String> tools = names(wanted);
        String os = osName.get();
        if (!SandboxHost.supported(os)) {
            return unmeasured("Floor42 runs on neither Windows nor Linux here: there is no sandbox to measure");
        }
        RunResult r;
        try {
            Path dir = Files.createDirectories(dataDir.resolve("sandbox").resolve("probe"));
            r = runner.run(sandbox.argvOffline(dir, script(tools), LIMIT.toSeconds()), LIMIT);
        } catch (IOException | RuntimeException e) {
            // Handled: the probe says why it is unknown
            return unmeasured(SandboxHost.startFailure(os, e.getMessage()));
        }
        Probe p = read(distro, tools, r);
        docker = DockerAccess.measure(p, r, Instant.now());
        return p;
    }

    /** The Docker daemon as the last probe since the start saw it: in memory only, never journaled (ADR-0056). */
    public DockerAccess.Measure docker() {
        return docker;
    }

    private Probe unmeasured(String reason) {
        docker = DockerAccess.Measure.unknown(reason, Instant.now());
        return Probe.unknown(distro, reason);
    }

    /** The tool names asked about: the baseline first, then the others, each a plain command name. */
    static List<String> names(Collection<String> wanted) {
        LinkedHashSet<String> all = new LinkedHashSet<>(BASELINE);
        if (wanted != null) {
            for (String t : wanted) {
                if (t != null && t.matches("[a-z0-9][a-z0-9._+-]{0,39}")) all.add(t);
            }
        }
        return List.copyOf(all);
    }

    /**
     * The shell script run in the sandbox. Tool names are checked plain words, written as they are; stdin is closed so
     * no version command waits for input.
     */
    static String script(List<String> tools) {
        StringBuilder s = new StringBuilder();
        s.append("exec </dev/null\n");
        s.append(
                "cat /etc/os-release 2>/dev/null | grep -E '^(ID|ID_LIKE|VERSION_ID|PRETTY_NAME)=' | sed 's/^/os|/'\n");
        s.append("for t in ").append(String.join(" ", tools)).append("; do\n");
        s.append("  p=$(command -v \"$t\" 2>/dev/null)\n");
        s.append("  if [ -n \"$p\" ]; then\n");
        s.append("    case \"$t\" in\n");
        s.append("      java) v=$(\"$p\" -version 2>&1 | head -1) ;;\n");
        s.append("      go) v=$(\"$p\" version 2>&1 | head -1) ;;\n");
        s.append("      pdftotext) v=$(\"$p\" -v 2>&1 | head -1) ;;\n");
        s.append("      *) v=$(\"$p\" --version 2>/dev/null | head -1) ;;\n");
        s.append("    esac\n");
        s.append("    printf '%s|%s|%s\\n' \"$t\" \"$p\" \"$(printf '%s' \"$v\" | tr -d '\\r|')\"\n");
        s.append("  else\n");
        s.append("    printf '%s||\\n' \"$t\"\n");
        s.append("  fi\n");
        s.append("done\n");
        s.append(DockerAccess.SCRIPT);
        s.append("echo ").append(END).append('\n');
        return s.toString();
    }

    /** Reads a run's output (recorded outputs in tests). */
    static Probe read(String distro, List<String> asked, RunResult r) {
        if (r.timedOut()) {
            return Probe.unknown(distro, "The probe did not finish within " + LIMIT.toSeconds() + " s");
        }
        // wsl.exe writes its own messages in UTF-16: once decoded as UTF-8 they carry NUL bytes.
        String out = r.output() == null ? "" : r.output().replace("\u0000", "").replace("\r\n", "\n");
        boolean complete = out.contains("\n" + END) || out.startsWith(END);
        // Only lines ended by a line break count: a cut last line is not a fact.
        int lastBreak = out.lastIndexOf('\n');
        String body = lastBreak < 0 ? "" : out.substring(0, lastBreak);
        Map<String, String> os = new LinkedHashMap<>();
        Map<String, Tool> tools = new LinkedHashMap<>();
        for (String line : body.split("\n")) line(line, asked, os, tools);
        if (tools.isEmpty() && os.isEmpty()) return Probe.unknown(distro, silent(distro, out, r));
        List<Tool> ordered = new ArrayList<>();
        for (String name : asked) {
            Tool t = tools.get(name);
            if (t != null) ordered.add(t);
        }
        return new Probe(
                distro,
                os.get("ID"),
                os.get("ID_LIKE"),
                os.get("VERSION_ID"),
                os.get("PRETTY_NAME"),
                true,
                ordered,
                complete ? null : "The output was cut: tools after the last complete line stay unknown");
    }

    /** One line of the probe: an os-release field, or a tool asked about with its absolute path and version. */
    private static void line(String line, List<String> asked, Map<String, String> os, Map<String, Tool> tools) {
        if (line.startsWith("os|")) {
            int eq = line.indexOf('=');
            if (eq > 3) os.put(line.substring(3, eq), unquote(line.substring(eq + 1)));
            return;
        }
        String[] parts = line.split("\\|", 3);
        if (parts.length != 3 || !asked.contains(parts[0]) || tools.containsKey(parts[0])) return;
        String path = parts[1].strip();
        // command -v gives an absolute path (or a shell builtin's name): anything else is not a measure.
        if (!path.isEmpty() && !path.startsWith("/")) return;
        tools.put(parts[0], new Tool(parts[0], path, path.isEmpty() ? "" : clip(parts[2].strip())));
    }

    /** Why the sandbox said nothing usable, quoting what it said. */
    private static String silent(String distro, String out, RunResult r) {
        String said = out.strip();
        if (said.length() > 300) said = said.substring(0, 300) + "…";
        return said.toLowerCase(Locale.ROOT).contains("distribution")
                ? "WSL has no distribution " + distro + " (or it does not start): " + said
                : "The sandbox did not answer" + (r.exitCode() == null ? "" : " (exit " + r.exitCode() + ")")
                        + (said.isEmpty() ? "" : ": " + said);
    }

    private static String clip(String s) {
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static String unquote(String v) {
        String s = v.strip();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("|", "");
    }

    /** Starts wsl.exe, keeps at most 64 KB of its output, and stops it with its children at the limit. */
    static RunResult start(List<String> argv, Duration limit) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        SandboxEnv.scrub(pb.environment());
        pb.environment().put("WSL_UTF8", "1"); // wsl.exe's own messages in UTF-8, not UTF-16LE
        Process p = pb.start();
        p.getOutputStream().close();
        java.io.ByteArrayOutputStream text = new java.io.ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> keep(p, text));
        boolean finished;
        try {
            finished = p.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller; the probe is stopped as if timed out
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
        try {
            reader.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller; the output read so far is returned
            Thread.currentThread().interrupt();
        }
        // ByteArrayOutputStream locks itself, and the reader is its only writer.
        return new RunResult(text.toString(StandardCharsets.UTF_8), finished ? p.exitValue() : null, !finished);
    }

    /** Copies the process's output into {@code text}, at most 64 KB, until it ends. */
    private static void keep(Process p, java.io.ByteArrayOutputStream text) {
        byte[] buffer = new byte[8192];
        try (InputStream in = p.getInputStream()) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                if (text.size() < 64 * 1024) text.write(buffer, 0, n);
            }
        } catch (IOException ignored) {
            // Handled: the process was stopped
        }
    }
}
