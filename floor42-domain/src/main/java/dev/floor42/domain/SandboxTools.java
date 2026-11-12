package dev.floor42.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What Floor42 last measured in the shell sandbox (ADR-0033): the Linux distribution and, per tool asked about, whether
 * {@code command -v} found it and the first line of its version. The journal's {@code SandboxToolsProbed} is the cache:
 * nothing is assumed between two measures, and turning the sandbox on forgets the last one.
 *
 * @param measured false when the probe could not run (not Windows, no WSL, no distribution, timeout): then every tool's
 *     state is unknown, never missing
 * @param reason why the probe could not run; null when measured
 * @param tools the tools asked about, in the order asked; empty when not measured
 */
public record SandboxTools(
        String distro,
        String osId,
        String osLike,
        String osVersion,
        String osName,
        boolean measured,
        List<Tool> tools,
        String reason,
        Instant measuredAt) {

    public SandboxTools {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * One tool as the probe saw it.
     *
     * @param path empty when {@code command -v} did not find it: measured missing
     * @param version the first line of its version output; empty when missing or silent
     */
    public record Tool(String name, String path, String version) {

        public boolean present() {
            return path != null && !path.isEmpty();
        }

        /** The probe's line, {@code name|path|version}, as journaled. */
        public String line() {
            return name + "|" + (path == null ? "" : path) + "|" + (version == null ? "" : version);
        }

        /** Reads a journaled line; the version is last, so a '|' in it is kept. */
        public static Optional<Tool> parse(String line) {
            if (line == null) return Optional.empty();
            String[] parts = line.split("\\|", 3);
            if (parts.length < 2 || parts[0].isBlank()) return Optional.empty();
            return Optional.of(new Tool(parts[0], parts[1], parts.length > 2 ? parts[2] : ""));
        }
    }

    /** The tool as measured; empty when it was not asked about (or nothing was measured): unknown. */
    public Optional<Tool> tool(String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst();
    }
}
