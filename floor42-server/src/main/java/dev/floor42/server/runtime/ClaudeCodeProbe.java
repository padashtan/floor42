package dev.floor42.server.runtime;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * Checks once at startup that Claude Code is installed and recent enough for Floor42's governance (fail-closed HTTP
 * hooks need 2.1.295 or later). Real missions are refused when it is not.
 */
@ApplicationScoped
public class ClaudeCodeProbe {

    public record Availability(boolean available, String version, String reason) {}

    static final int[] MINIMUM = {2, 1, 295};
    private static final Logger LOG = Logger.getLogger(ClaudeCodeProbe.class);
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    @Inject
    ClaudeCodeConfig config;

    private volatile Availability availability = new Availability(false, null, "not checked yet");

    /** A kind of stream line Floor42 does not know, with the shape of its first occurrence (keys and types only). */
    public record UnfamiliarLine(String kind, String shape) {}

    /** Kinds of stream line Claude Code sent that Floor42 does not know, since this daemon started: a protocol change. */
    private final java.util.concurrent.ConcurrentSkipListMap<String, String> unfamiliar =
            new java.util.concurrent.ConcurrentSkipListMap<>();

    @Inject
    AcceptedLines accepted;

    /**
     * Records an unknown kind of stream line; true the first time it is seen, when it is logged. A kind the director
     * accepted is recorded by the runtime instead, and not reported here.
     */
    public boolean accepted(String kind) {
        return accepted.acceptedStreamLines().contains(kind);
    }

    public boolean unfamiliar(String kind, String shape, String model) {
        if (accepted.acceptedStreamLines().contains(kind)) return false;
        boolean first = unfamiliar.putIfAbsent(kind, shape == null ? "" : shape) == null;
        if (first) {
            LOG.warnf(
                    "Claude Code %s sent a stream line Floor42 does not know (%s, %s, shape %s): its content is not"
                            + " recorded",
                    availability.version(), kind, model, shape);
        }
        return first;
    }

    /** The unknown kinds seen since startup, but those the director accepted since. */
    public List<UnfamiliarLine> unfamiliar() {
        Set<String> quieted = accepted.acceptedStreamLines();
        return unfamiliar.entrySet().stream()
                .filter(e -> !quieted.contains(e.getKey()))
                .map(e -> new UnfamiliarLine(e.getKey(), e.getValue()))
                .toList();
    }

    void check(@Observes StartupEvent ignored) {
        availability = probe();
        LOG.infof("Claude Code: %s", availability.available() ? availability.version() : availability.reason());
    }

    public Availability availability() {
        return availability;
    }

    Availability probe() {
        return probe(config.command());
    }

    /** Runs {@code <command> --version} (20 s at most) and reads whether it is recent enough. */
    public static Availability probe(List<String> command) {
        List<String> cmd = new ArrayList<>(command);
        cmd.add("--version");
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Availability(false, null, "claude --version did not answer");
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            Matcher m = VERSION.matcher(out);
            if (p.exitValue() != 0 || !m.find()) return new Availability(false, null, "unexpected answer: " + out);
            int[] v = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
            if (compare(v, MINIMUM) < 0) {
                return new Availability(false, m.group(), "Claude Code " + m.group() + " is older than 2.1.295");
            }
            return new Availability(true, m.group(), null);
        } catch (IOException e) {
            // Handled: the availability says why claude cannot run
            return new Availability(false, null, "claude not found: " + e.getMessage());
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller; the availability says so
            Thread.currentThread().interrupt();
            return new Availability(false, null, "interrupted");
        }
    }

    static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }
}
