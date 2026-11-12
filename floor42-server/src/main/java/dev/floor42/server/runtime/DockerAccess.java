package dev.floor42.server.runtime;

import dev.floor42.server.runtime.MachineChecklist.ChecklistRow;
import dev.floor42.server.runtime.MachineChecklist.ChecklistState;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Whether work in the shell sandbox can use Docker, as measured from inside it (ADR-0028, ADR-0056). The client alone
 * says nothing: the sandbox hides {@code /run}, where the daemon's socket lives, and its user namespace drops the docker
 * group, so a client that is present still cannot reach the daemon. The tools probe asks the daemon itself, with a
 * short limit, in the same run as the tools. The answer is kept in memory only, never journaled: the journal's measure
 * is the tools', read by every version of Floor42. Floor42 never widens the sandbox for Docker.
 */
public final class DockerAccess {

    private DockerAccess() {}

    /** What a measure says of Docker inside the sandbox. */
    public enum InSandbox {
        /** No docker command in the distribution. */
        CLIENT_MISSING,
        /** The client is there, the daemon did not answer from inside the sandbox. */
        DAEMON_UNREACHABLE_IN_SANDBOX,
        /** The daemon answered from inside the sandbox. */
        DAEMON_REACHABLE,
        /** Not measured since Floor42 started, or the probe could not run or was cut. */
        UNKNOWN
    }

    /**
     * One measure.
     *
     * @param version the daemon's version when it answered, else the client's version line; null when none
     * @param at when it was measured; null when never since the start
     * @param reason why it is UNKNOWN; null otherwise
     */
    public record Measure(InSandbox state, String version, Instant at, String reason) {

        public static Measure unknown(String reason, Instant at) {
            return new Measure(InSandbox.UNKNOWN, null, at, reason);
        }
    }

    /** The line the script prints about the daemon. */
    static final String LINE = "docker-daemon|";

    /**
     * Appended to the probe's script: asks the daemon for its version, 5 s at most, and prints one line {@code
     * docker-daemon|<client path>|<server version>} when it answered, {@code docker-daemon||} when it did not, nothing
     * when there is no client.
     */
    static final String SCRIPT = """
            p=$(command -v docker 2>/dev/null)
            if [ -n "$p" ]; then
              if v=$(timeout 5 "$p" version --format '{{.Server.Version}}' 2>/dev/null) && [ -n "$v" ]; then
                printf '%s|%s|%s\\n' docker-daemon "$p" "$(printf '%s' "$v" | head -1 | tr -d '\\r|')"
              else
                printf 'docker-daemon||\\n'
              fi
            fi
            """;

    /** Reads the daemon's state from a probe and its raw output; only complete lines count. */
    static Measure measure(SandboxProbe.Probe p, SandboxProbe.RunResult r, Instant at) {
        if (!p.measured()) return Measure.unknown(p.reason(), at);
        Optional<SandboxProbe.Tool> client =
                p.tools().stream().filter(t -> t.name().equals("docker")).findFirst();
        if (client.isEmpty()) return Measure.unknown("the probe's output was cut before docker", at);
        if (client.get().path().isEmpty()) return new Measure(InSandbox.CLIENT_MISSING, null, at, null);
        String out = r.output() == null ? "" : r.output().replace("\u0000", "").replace("\r\n", "\n");
        int lastBreak = out.lastIndexOf('\n');
        for (String line : (lastBreak < 0 ? "" : out.substring(0, lastBreak)).split("\n")) {
            if (!line.startsWith(LINE)) continue;
            String[] parts = line.split("\\|", 3);
            if (parts.length == 3 && parts[1].startsWith("/")) {
                return new Measure(InSandbox.DAEMON_REACHABLE, parts[2].strip(), at, null);
            }
            if (parts.length >= 2 && parts[1].isEmpty()) {
                return new Measure(
                        InSandbox.DAEMON_UNREACHABLE_IN_SANDBOX, client.get().version(), at, null);
            }
        }
        return Measure.unknown("the probe's output was cut before the daemon's line", at);
    }

    /**
     * The sandbox panel's note for a docker line: {@code docker.unreachable} when measured out of reach, {@code
     * docker.daemon} when not measured since the start, {@code docker.reachable} (a warning) when it answered.
     */
    public static String note(Measure m) {
        return switch (m.state()) {
            case DAEMON_UNREACHABLE_IN_SANDBOX -> "docker.unreachable";
            case DAEMON_REACHABLE -> "docker.reachable";
            case CLIENT_MISSING, UNKNOWN -> "docker.daemon";
        };
    }

    private static final Map<String, String> NOTES = Map.of(
            "dockerUnreachable",
            "the sandbox cannot reach the Docker daemon (its socket is hidden there); installing will not help: run"
                    + " the Docker commands yourself, outside the sandbox",
            "dockerClientMissing",
            "no docker command; even installed, its daemon is out of the sandbox's reach: run the Docker commands"
                    + " yourself, outside the sandbox",
            "dockerReachable",
            "WARNING: the sandbox can drive Docker: an agent could mount your disks through it",
            "dockerNotMeasured",
            "not measured since Floor42 started: measure again");

    /** A Docker note's English for {@code floor42 doctor}; the code itself for any other. */
    static String note(String code) {
        return NOTES.getOrDefault(code, code);
    }

    /**
     * The machine checklist's row: never required, with when it was measured. Out of reach, it names the director's
     * step (run the Docker commands outside the sandbox) and offers no install command, since installing does not
     * change it.
     */
    static ChecklistRow row(boolean windows, Measure m) {
        if (!windows) return row(ChecklistState.OPTIONAL, null, "notWindows", m);
        return switch (m.state()) {
            case DAEMON_REACHABLE -> row(ChecklistState.PRESENT, m.version(), "dockerReachable", m);
            case DAEMON_UNREACHABLE_IN_SANDBOX -> row(ChecklistState.OPTIONAL, m.version(), "dockerUnreachable", m);
            case CLIENT_MISSING -> row(ChecklistState.OPTIONAL, null, "dockerClientMissing", m);
            case UNKNOWN -> row(ChecklistState.UNKNOWN, null, "dockerNotMeasured", m);
        };
    }

    private static ChecklistRow row(ChecklistState state, String version, String note, Measure m) {
        String at = m.at() == null ? null : m.at().toString();
        return new ChecklistRow("docker", true, state, false, version, note, m.reason(), null, at);
    }
}
