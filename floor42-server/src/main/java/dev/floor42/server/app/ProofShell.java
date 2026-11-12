package dev.floor42.server.app;

import dev.floor42.server.runtime.Sandbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Runs one step of a delivery's proof (ADR-0058): a command an agent wrote, so only in the WSL sandbox, with the
 * network cut, never on the host. There is no fallback: when the sandbox does not work, nothing starts.
 */
@ApplicationScoped
public class ProofShell {

    /** What runs a step in tests, where the sandbox is not measured. */
    public interface Runner {
        BoundedProcess.Ran run(Path worktree, String command);
    }

    @Inject
    Sandbox sandbox;

    @ConfigProperty(name = "floor42.proof.step-timeout-seconds", defaultValue = "300")
    long timeoutSeconds;

    private volatile Runner forTests;
    private volatile Boolean worksForTests;

    /** For tests: steps go to {@code runner}, and the sandbox is taken as working or not, unmeasured. */
    public void useForTests(Runner runner, boolean works) {
        forTests = runner;
        worksForTests = works;
    }

    /** For tests: back to the real sandbox. */
    public void restore() {
        forTests = null;
        worksForTests = null;
    }

    /** Whether the sandbox works on this machine (measured by {@link Sandbox#available()}). */
    boolean works() {
        Boolean assumed = worksForTests;
        return assumed != null ? assumed : sandbox.available();
    }

    /** Runs the command in the worktree, in the sandbox; refused when the sandbox does not work. */
    BoundedProcess.Ran run(Path worktree, String command) {
        if (!works()) throw new IllegalStateException("The sandbox does not work: a proof step never runs on the host");
        Runner runner = forTests;
        if (runner != null) return runner.run(worktree, command);
        return BoundedProcess.run(BoundedProcess.builder(argv(worktree, command), worktree), timeoutSeconds, "step");
    }

    /** The same for a copy of {@code source}: its dependency folders (node_modules) are mounted read-only in it. */
    BoundedProcess.Ran run(Path worktree, String command, Path source) {
        if (!works()) throw new IllegalStateException("The sandbox does not work: a proof step never runs on the host");
        Runner runner = forTests;
        if (runner != null) return runner.run(worktree, command);
        List<String> argv = sandbox.argvOffline(worktree, command, timeoutSeconds, source);
        return BoundedProcess.run(BoundedProcess.builder(argv, worktree), timeoutSeconds, "step");
    }

    /** The only command line a step gets: the sandbox's, offline. */
    List<String> argv(Path worktree, String command) {
        return sandbox.argvOffline(worktree, command, timeoutSeconds);
    }
}
