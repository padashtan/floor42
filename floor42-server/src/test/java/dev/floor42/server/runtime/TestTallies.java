package dev.floor42.server.runtime;

import dev.floor42.server.runtime.AgentRuntime.Governor;
import java.nio.file.Path;

/** A governor wrapped in the session tally, as a real session has it, for tests outside this package. */
public final class TestTallies {

    private TestTallies() {}

    public static Governor wrap(Governor governor, Path workdir, Sandbox sandbox) {
        return new SessionTally(governor, workdir, sandbox);
    }
}
