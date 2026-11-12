package dev.floor42.server.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How the sandbox script is started on this operating system (ADR-0028, amended 2026-11-09). On Windows it goes through
 * {@code wsl.exe} into a WSL 2 distribution; on Linux the same script runs natively with {@code sh}: same namespaces,
 * same mounts, same checks. The operating system chooses; no setting does. macOS has no backend.
 */
final class SandboxHost {

    private SandboxHost() {}

    static String current() {
        return System.getProperty("os.name", "");
    }

    static boolean windows(String os) {
        return os != null && os.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static boolean linux(String os) {
        return os != null && os.toLowerCase(Locale.ROOT).startsWith("linux");
    }

    /** Whether a sandbox backend exists for this system. */
    static boolean supported(String os) {
        return windows(os) || linux(os);
    }

    /** The process to start for the script's arguments ({@code sh <run.sh> ...}): as they are on Linux, via wsl.exe else. */
    static List<String> argv(String os, String distro, List<String> tail) {
        if (linux(os)) return tail;
        List<String> args = new ArrayList<>(List.of("wsl.exe", "-d", distro, "--exec"));
        args.addAll(tail);
        return args;
    }

    /** The words before {@code sh} in the line Claude Code's Bash tool runs. */
    static String shellPrefix(String os, String distro) {
        // An empty WSLENV: wsl.exe forwards none of the shell's variables (secrets among them) into Linux. On Linux the
        // script itself starts again with an environment of only LANG, LC_ALL, TERM and TZ.
        return linux(os) ? "" : "WSLENV= MSYS_NO_PATHCONV=1 wsl.exe -d " + distro + " --exec ";
    }

    /** Why the probe could not start, in the words of the system's launcher. */
    static String startFailure(String os, String reason) {
        return (linux(os) ? "the sandbox script could not start: " : "wsl.exe could not start: ") + reason;
    }
}
