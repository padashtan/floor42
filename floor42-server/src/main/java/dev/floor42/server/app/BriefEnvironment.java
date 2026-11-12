package dev.floor42.server.app;

import dev.floor42.domain.Company;
import dev.floor42.domain.SandboxTools;
import dev.floor42.domain.Setting;
import dev.floor42.server.runtime.SandboxToolkit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Where a developer's or a reviewer's tools run, stated in its brief so it does not guess: the operating system and the
 * shell, the sandbox and its network, the path form each tool takes, and how the working directory maps between them.
 * The host is read once per daemon ({@code os.name}); the sandbox's system is the one its readiness probe last measured
 * and journaled (ADR-0033), never a new probe at launch.
 */
final class BriefEnvironment {

    private static final String HOST = System.getProperty("os.name", "");
    private static volatile String pinned;

    private BriefEnvironment() {}

    /** For tests: the operating system the briefs say Floor42 runs on; null puts the real one back. */
    static void pinHostForTests(String osName) {
        pinned = osName;
    }

    static String of(Company c) {
        String os = pinned != null ? pinned : HOST;
        boolean windows = os.toLowerCase(Locale.ROOT).startsWith("windows");
        String facts;
        if (OfficeSettings.sandboxed(c)) facts = sandboxed(c, windows, os);
        else if (windows) facts = windowsHost();
        else facts = posixHost(os.isBlank() ? "an unnamed operating system" : os);
        return "Execution environment (stated by Floor42: do not guess another):\n" + facts;
    }

    /** Bash in WSL, the file tools on Windows: the same folder under two path forms. */
    private static String sandboxed(Company c, boolean windows, String os) {
        boolean net = "on".equals(setting(c, Setting.SANDBOX_NETWORK));
        String files = windows
                ? "- Floor42 and your file tools (Read, Glob, Grep, Write, Edit) run on Windows. Give them paths"
                        + " relative to your working directory, or absolute Windows paths inside it (C:\\...). A Linux"
                        + " path such as /media/work/... or /mnt/c/... is not a path there: it is refused.\n"
                : "- Floor42 and your file tools (Read, Glob, Grep, Write, Edit) run on "
                        + (os.isBlank() ? "the host" : os) + ". Give them paths relative to your working directory,"
                        + " or absolute host paths inside it. A sandbox path such as /media/work/... is not a path"
                        + " there.\n";
        return files
                + "- Bash runs in a Linux sandbox (" + sandboxSystem(c) + "), with bash as its shell. There your"
                + " working directory is /media/work: the same files the file tools see. Use relative paths in"
                + " commands.\n"
                + "- Sandbox: on. Network in the sandbox: " + (net ? "open" : "cut") + ". A browser, a desktop"
                + " program or a Windows script (.cmd, .ps1, .exe) cannot run there.\n"
                + "- Maven: the director's local Maven repository, when there is one, is shown read-only (MAVEN_ARGS"
                + " sets it as the tail); with the network cut, build offline with the distribution's `mvn -o`, since"
                + " the Maven wrapper (mvnw) cannot download its own Maven there.\n"
                + tools(c);
    }

    /**
     * The essential tools (SandboxToolkit) present and absent in the sandbox, as last measured; empty when the sandbox
     * is off (the tools are then the machine's own, which Floor42 does not measure).
     */
    static String tools(Company c) {
        if (!OfficeSettings.sandboxed(c)) return "";
        SandboxTools last =
                c.preferences().sandboxTools().filter(SandboxTools::measured).orElse(null);
        if (last == null) {
            return "- Tools in the sandbox: not measured yet. Floor42 installs nothing there.\n";
        }
        List<String> present = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (SandboxToolkit.Essential e : SandboxToolkit.ESSENTIALS) {
            switch (SandboxToolkit.state(last, e.name())) {
                case PRESENT -> present.add(e.name());
                case ABSENT -> absent.add(e.name());
                case UNKNOWN -> unknown.add(e.name());
            }
        }
        return "- Tools in the sandbox, measured by Floor42 on " + last.measuredAt() + ": present "
                + (present.isEmpty() ? "none" : String.join(", ", present)) + "; absent "
                + (absent.isEmpty() ? "none" : String.join(", ", absent))
                + (unknown.isEmpty() ? "" : "; not measured " + String.join(", ", unknown))
                + ". Floor42 installs nothing: a step that needs an absent tool cannot run there.\n";
    }

    private static String windowsHost() {
        return "- Floor42 runs on Windows, with no sandbox: your tools and Bash run on this machine, as the"
                + " director's account. File tools take Windows paths; relative to your working directory is best.\n"
                + "- Bash runs in the shell Claude Code uses on Windows (Git Bash), where C:\\ reads /c/: prefer"
                + " relative paths there too.\n"
                + "- Sandbox: off. Network: not cut by Floor42; WebFetch and WebSearch follow the permission table.\n";
    }

    private static String posixHost(String os) {
        return "- Floor42 runs on " + os + ", with no sandbox: your tools and Bash run on this machine, as the"
                + " director's account, with the same paths; relative to your working directory is best.\n"
                + "- Sandbox: off. Network: not cut by Floor42; WebFetch and WebSearch follow the permission table.\n";
    }

    /** The distribution the probe measured, or the one configured when no measure holds. */
    private static String sandboxSystem(Company c) {
        Optional<SandboxTools> last = c.preferences().sandboxTools().filter(SandboxTools::measured);
        if (last.isPresent()
                && last.get().osName() != null
                && !last.get().osName().isBlank()) return last.get().osName();
        return last.map(SandboxTools::distro)
                .filter(d -> d != null && !d.isBlank())
                .orElse("not measured yet");
    }

    private static String setting(Company c, Setting setting) {
        return c.preferences().setting(setting).orElse("off");
    }
}
