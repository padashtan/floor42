package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CheckRun;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Runs the director's checks (build, tests) in an agent's workspace and records what Floor42 measured: exit code,
 * duration, output (spec section 12: verification backed by reproducible evidence, not by an agent's word).
 *
 * <p>The command is the director's, never an agent's. It still runs code the agent may have written: with the shell
 * sandbox on (ADR-0028) it runs isolated in WSL, seeing only the worktree; off, on the director's machine as is.
 */
@ApplicationScoped
public class Checks {

    @Inject
    OfficeService office;

    @Inject
    Workshop workshop;

    @Inject
    dev.floor42.server.runtime.Sandbox sandbox;

    @ConfigProperty(name = "floor42.checks.timeout-seconds", defaultValue = "900")
    long timeoutSeconds;

    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /** Runs the checks now and returns the recorded run. */
    public CheckRun run(MissionId mission, AgentId agent, String trigger) {
        Origin origin = office.query(c -> c.missions()
                .find(mission)
                .orElseThrow(() -> new DomainException("UNKNOWN_MISSION", "No mission " + mission))
                .origin());
        office.query(c -> {
            c.verification().checkChecksCanRun(office.context(Actor.director(), origin), mission, agent);
            return null;
        });
        String key = mission + "/" + agent;
        if (!running.add(key)) throw new DomainException("CHECKS_RUNNING", "Checks are already running on " + key);
        try {
            Workspace w = workshop.workspace(mission, agent);
            String command =
                    office.query(c -> c.missions().find(mission).orElseThrow().checkCommand());
            String head = workshop.head(w);
            Outcome o = executeClean(w, command);
            office.execute(c -> c.verification()
                    .recordChecks(
                            office.context(Actor.system(), origin),
                            mission,
                            agent,
                            command,
                            o.exitCode(),
                            o.timedOut(),
                            o.durationMs(),
                            o.output(),
                            head,
                            trigger));
            return office.query(c -> c.verification().checkRuns(mission).getLast());
        } finally {
            running.remove(key);
        }
    }

    /** Checks must not change the work under review: what they create (build output) is removed afterwards. */
    private Outcome executeClean(Workspace w, String command) {
        java.util.Set<String> before = workshop.untracked(w);
        Outcome executed = execute(command, Path.of(w.path()));
        List<String> removed = workshop.removeCreatedBy(w, before);
        if (removed.isEmpty()) return executed;
        return new Outcome(
                executed.exitCode(),
                executed.timedOut(),
                executed.durationMs(),
                executed.output() + "\n[Floor42 removed " + removed.size()
                        + " file(s) the checks created, so they never mix with the agent's work: "
                        + String.join(", ", removed.subList(0, Math.min(5, removed.size())))
                        + (removed.size() > 5 ? ", …" : "") + "]");
    }

    record Outcome(Integer exitCode, boolean timedOut, long durationMs, String output) {}

    Outcome execute(String command, Path dir) {
        boolean sandboxed = sandbox != null && sandbox.enabled();
        if (sandboxed && !sandbox.available()) {
            return new Outcome(
                    null,
                    false,
                    0,
                    "The checks did not run: the director requires the WSL sandbox and it does not work on this machine"
                            + " right now.");
        }
        // The checks see neither Claude Code's settings nor Floor42's own secrets (BoundedProcess.builder).
        BoundedProcess.Ran r = BoundedProcess.run(
                BoundedProcess.builder(argv(sandboxed, command, dir), dir), timeoutSeconds, "checks");
        return new Outcome(r.exitCode(), r.timedOut(), r.durationMs(), r.output());
    }

    private List<String> argv(boolean sandboxed, String command, Path dir) {
        return sandboxed
                ? sandbox.argv(dir, command, timeoutSeconds)
                : System.getProperty("os.name").startsWith("Windows")
                        ? List.of("cmd.exe", "/d", "/s", "/c", command)
                        : List.of("/bin/sh", "-c", command);
    }
}
