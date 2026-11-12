package dev.floor42.server.runtime;

/**
 * The sandbox's package-private measure seam, for the API tests in another package: a test that sets the director's
 * sandbox setting and fakes everything the sandbox would run must also say the sandbox works, or on a machine without
 * WSL (a GitHub Windows runner) the real probe fails and every real session is refused (fail closed, ADR-0063).
 */
public final class SandboxSeams {

    private SandboxSeams() {}

    /** What the next calls take as measured; null measures again. */
    public static void assume(Sandbox sandbox, Boolean works) {
        sandbox.setAvailable(works);
    }
}
