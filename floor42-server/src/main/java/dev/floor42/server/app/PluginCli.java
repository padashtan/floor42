package dev.floor42.server.app;

import dev.floor42.server.app.PluginViews.CliStatus;
import dev.floor42.server.app.PluginViews.Step;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Claude Code's own {@code claude plugin} commands (ADR-0040), run one command at a time and only on the director's
 * confirmed click; its version asked at most once a minute. Tests swap the command line for a fake one.
 */
@ApplicationScoped
public class PluginCli {

    /** What the commands did: each step as it ran, the last exit code (null after a time-out), all exited 0. */
    record Ran(List<Step> steps, List<String> shown, Integer lastExit, boolean ok) {}

    private final List<String> command;
    private final long timeoutSeconds;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ClaudeCli cli;
    private volatile long limit;
    private volatile CliStatus status;
    private volatile Instant statusAt = Instant.EPOCH;

    @Inject
    public PluginCli(
            @ConfigProperty(name = "floor42.plugins.claude-command", defaultValue = "claude") List<String> command,
            @ConfigProperty(name = "floor42.plugins.timeout-seconds", defaultValue = "300") long timeoutSeconds) {
        this.command = command;
        this.timeoutSeconds = timeoutSeconds;
        this.cli = new ClaudeCli.Process(command);
        this.limit = timeoutSeconds;
    }

    void useForTests(ClaudeCli fake, Long seconds) {
        cli = fake == null ? new ClaudeCli.Process(command) : fake;
        limit = seconds == null ? timeoutSeconds : seconds;
        statusAt = Instant.EPOCH;
    }

    /** Claude Code's command line, asked for its version at most once a minute. */
    CliStatus status() {
        if (status != null && Duration.between(statusAt, Instant.now()).toSeconds() < 60) return status;
        ClaudeCli.Result r = cli.run(List.of("--version"), null, 30);
        CliStatus s = r.missing()
                ? new CliStatus(false, null, "Claude Code's command line (claude) could not be started: " + r.err())
                : r.ok()
                        ? new CliStatus(
                                true, r.out().strip().lines().findFirst().orElse(""), null)
                        : new CliStatus(false, null, "claude --version failed: " + said(r));
        status = s;
        statusAt = Instant.now();
        return s;
    }

    /** The next {@link #status} asks the command line again (after an install, the version may differ). */
    void forgetStatus() {
        statusAt = Instant.EPOCH;
    }

    /** Runs the steps in order, stopping at the first that fails; refused while another run goes on. */
    Ran run(List<List<String>> steps, Path config) {
        if (!running.compareAndSet(false, true)) {
            throw RefusedException.conflict("PLUGIN_COMMAND_RUNNING", "A plugin command is already running");
        }
        try {
            List<Step> done = new ArrayList<>();
            List<String> shown = new ArrayList<>();
            Integer last = null;
            for (List<String> args : steps) {
                String line = ClaudeCli.shown(args, config);
                ClaudeCli.Result r = cli.run(args, config, limit);
                shown.add(line);
                last = r.timedOut() || r.missing() ? null : r.code();
                done.add(new Step(line, last, r.timedOut(), output(r)));
                if (!r.ok()) return new Ran(done, shown, last, false);
            }
            return new Ran(done, shown, last, true);
        } finally {
            running.set(false);
        }
    }

    private static String output(ClaudeCli.Result r) {
        String out = r.out() == null ? "" : r.out().strip();
        String err = r.err() == null ? "" : r.err().strip();
        String both = out.isEmpty() ? err : err.isEmpty() ? out : out + "\n" + err;
        if (r.timedOut()) both = both + (both.isEmpty() ? "" : "\n") + "(stopped by Floor42 after the time limit)";
        if (r.missing()) both = "Could not start claude: " + err;
        return both;
    }

    private static String said(ClaudeCli.Result r) {
        String e = r.err() == null ? "" : r.err().strip();
        return e.isEmpty() ? (r.out() == null ? "" : r.out().strip()) : e;
    }
}
