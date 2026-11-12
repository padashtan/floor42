package dev.floor42.server.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What the machine itself has for Floor42, outside the sandbox: whether it runs Windows, git, and WSL with its
 * distributions ({@code wsl.exe -l -v}). Each check is one short process, read-only; nothing is installed or changed.
 * Usable without the daemon ({@code floor42 doctor}): no injection.
 */
@ApplicationScoped
public class HostChecks {

    static final Duration LIMIT = Duration.ofSeconds(15);

    /** WSL as {@code wsl.exe -l -v} lists it. */
    public record Wsl(boolean ran, Map<String, Integer> distros) {
        public Wsl {
            distros = distros == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(distros));
        }
    }

    private volatile SandboxProbe.ProbeRunner runner = SandboxProbe::start;
    private volatile Supplier<String> osName = () -> System.getProperty("os.name");

    /** For tests: the process seam and the operating system; null puts the real one back. */
    public void useForTests(SandboxProbe.ProbeRunner runner, Supplier<String> osName) {
        this.runner = runner == null ? SandboxProbe::start : runner;
        this.osName = osName == null ? () -> System.getProperty("os.name") : osName;
    }

    public boolean windows() {
        String os = osName.get();
        return os != null && os.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** The first line of {@code git --version} on the machine, or null when git does not run. */
    public String git() {
        try {
            SandboxProbe.RunResult r = runner.run(List.of("git", "--version"), LIMIT);
            if (r.timedOut() || r.exitCode() == null || r.exitCode() != 0 || r.output() == null) return null;
            String line = r.output().strip().lines().findFirst().orElse("");
            return line.isEmpty() ? null : line;
        } catch (IOException | RuntimeException e) {
            // Handled: git does not run here, which the checklist says
            return null;
        }
    }

    /** WSL's distributions and their WSL version; not ran when wsl.exe is absent or fails (never on another system). */
    public Wsl wsl() {
        if (!windows()) return new Wsl(false, Map.of());
        try {
            SandboxProbe.RunResult r = runner.run(List.of("wsl.exe", "-l", "-v"), LIMIT);
            if (r.timedOut() || r.exitCode() == null || r.exitCode() != 0) return new Wsl(false, Map.of());
            return new Wsl(true, distros(r.output()));
        } catch (IOException | RuntimeException e) {
            // Handled: no WSL here, which the checklist says
            return new Wsl(false, Map.of());
        }
    }

    /**
     * Reads {@code wsl.exe -l -v}: a header line (in the system's language), then one line per distribution, the
     * default one marked with '*': name, state, WSL version. wsl.exe writes UTF-16, read here as UTF-8: NULs dropped.
     */
    static Map<String, Integer> distros(String output) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (output == null) return out;
        char bom = 0xFEFF;
        List<String> lines = output.replace("\u0000", "")
                .replace(String.valueOf(bom), "")
                .lines()
                .toList();
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).replace("*", " ").strip().split("\\s+");
            if (parts.length < 3 || !parts[parts.length - 1].matches("\\d")) continue;
            out.put(parts[0], Integer.parseInt(parts[parts.length - 1]));
        }
        return out;
    }
}
