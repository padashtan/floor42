package dev.floor42.server.runtime;

import dev.floor42.domain.SandboxTools;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What is missing on this machine for Floor42, one row per thing: Claude Code, git, WSL 2, the sandbox's distribution,
 * the isolation, then the essential tools inside the sandbox ({@link SandboxToolkit}). Each row says whether it is
 * present, missing (required), optional (absent, not required) or unknown, and the command the director copies to
 * install it. Floor42 never runs any of these commands. Shared by the screen and {@code floor42 doctor}.
 */
public final class MachineChecklist {

    private MachineChecklist() {}

    public enum ChecklistState {
        PRESENT,
        MISSING,
        OPTIONAL,
        UNKNOWN
    }

    /**
     * One row.
     *
     * @param id {@code claude}, {@code git}, {@code wsl}, {@code distro}, {@code isolation}, or an essential tool's
     *     name: the screen explains it by this code
     * @param sandbox true for a tool inside the sandbox
     * @param version what the check read (a version line); null when nothing was read
     * @param note a code the screen explains ({@code old}, {@code wsl1}, {@code notWindows}, {@code notMeasured},
     *     {@code probeFailed}); null otherwise
     * @param detail the probe's own words when it failed (technical output, not a sentence for the screen); else null
     * @param command what the director copies to install or fix it; null when there is none
     * @param measuredAt when a row measured apart from the tools was measured (the Docker daemon); null otherwise
     */
    public record ChecklistRow(
            String id,
            boolean sandbox,
            ChecklistState state,
            boolean required,
            String version,
            String note,
            String detail,
            String command,
            String measuredAt) {
        public ChecklistRow(
                String id,
                boolean sandbox,
                ChecklistState state,
                boolean required,
                String version,
                String note,
                String detail,
                String command) {
            this(id, sandbox, state, required, version, note, detail, command, null);
        }
    }

    /**
     * @param ready true when every required row is present
     * @param measuredAt when the sandbox's tools were measured; null when never
     */
    public record Checklist(
            boolean windows, String distro, String measuredAt, boolean ready, List<ChecklistRow> rows) {}

    public static Checklist of(
            boolean windows,
            ClaudeCodeProbe.Availability claude,
            String git,
            HostChecks.Wsl wsl,
            String distro,
            SandboxTools last) {
        return of(windows, claude, git, wsl, distro, last, DockerAccess.Measure.unknown(null, null));
    }

    /** The checklist from what was read: the sandbox's part from its last measure (null when none). */
    public static Checklist of(
            boolean windows,
            ClaudeCodeProbe.Availability claude,
            String git,
            HostChecks.Wsl wsl,
            String distro,
            SandboxTools last,
            DockerAccess.Measure docker) {
        List<ChecklistRow> rows = new ArrayList<>();
        rows.add(claude(windows, claude));
        rows.add(new ChecklistRow(
                "git",
                false,
                git == null ? ChecklistState.MISSING : ChecklistState.PRESENT,
                true,
                git,
                null,
                null,
                git == null && windows ? "winget install --id Git.Git -e" : null));
        rows.add(wslRow(windows, wsl));
        rows.add(distroRow(windows, wsl, distro));
        rows.add(isolation(windows, wsl, distro, last));
        for (SandboxToolkit.Essential e : SandboxToolkit.ESSENTIALS) rows.add(tool(windows, e, distro, last));
        // Measured through the sandbox, which also runs natively on Linux: a measure makes the row real.
        rows.add(DockerAccess.row(windows || last != null, docker));
        boolean ready = rows.stream().filter(ChecklistRow::required).allMatch(r -> r.state() == ChecklistState.PRESENT);
        String at = last == null || last.measuredAt() == null
                ? null
                : last.measuredAt().toString();
        return new Checklist(windows, distro, at, ready, List.copyOf(rows));
    }

    private static ChecklistRow claude(boolean windows, ClaudeCodeProbe.Availability a) {
        if (a != null && a.available())
            return new ChecklistRow("claude", false, ChecklistState.PRESENT, true, a.version(), null, null, null);
        boolean old = a != null && a.version() != null;
        String install =
                windows ? "irm https://claude.ai/install.ps1 | iex" : "curl -fsSL https://claude.ai/install.sh | bash";
        return new ChecklistRow(
                "claude",
                false,
                ChecklistState.MISSING,
                true,
                a == null ? null : a.version(),
                old ? "old" : "notFound",
                null,
                old ? "claude update" : install);
    }

    private static ChecklistRow wslRow(boolean windows, HostChecks.Wsl wsl) {
        if (!windows)
            return new ChecklistRow("wsl", false, ChecklistState.OPTIONAL, false, null, "notWindows", null, null);
        return wsl.ran()
                ? new ChecklistRow("wsl", false, ChecklistState.PRESENT, true, null, null, null, null)
                : new ChecklistRow(
                        "wsl",
                        false,
                        ChecklistState.MISSING,
                        true,
                        null,
                        null,
                        null,
                        "wsl --install --no-distribution");
    }

    private static ChecklistRow distroRow(boolean windows, HostChecks.Wsl wsl, String distro) {
        if (!windows)
            return new ChecklistRow("distro", false, ChecklistState.OPTIONAL, false, distro, "notWindows", null, null);
        if (!wsl.ran())
            return new ChecklistRow("distro", false, ChecklistState.UNKNOWN, true, distro, null, null, null);
        Optional<Map.Entry<String, Integer>> found = wsl.distros().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(distro))
                .findFirst();
        if (found.isEmpty()) {
            return new ChecklistRow(
                    "distro", false, ChecklistState.MISSING, true, distro, null, null, "wsl --install -d " + distro);
        }
        if (found.get().getValue() < 2) {
            return new ChecklistRow(
                    "distro",
                    false,
                    ChecklistState.MISSING,
                    true,
                    distro,
                    "wsl1",
                    null,
                    "wsl --set-version " + distro + " 2");
        }
        return new ChecklistRow("distro", false, ChecklistState.PRESENT, true, distro, null, null, null);
    }

    /** The isolation works when a measure ran through the sandbox's namespaces. */
    private static ChecklistRow isolation(boolean windows, HostChecks.Wsl wsl, String distro, SandboxTools last) {
        // On Linux the sandbox runs natively: a measure makes the row real, none leaves it out as before.
        if (!windows && last == null)
            return new ChecklistRow("isolation", false, ChecklistState.OPTIONAL, false, null, "notWindows", null, null);
        if (last == null)
            return new ChecklistRow("isolation", false, ChecklistState.UNKNOWN, true, null, "notMeasured", null, null);
        if (last.measured())
            return new ChecklistRow("isolation", false, ChecklistState.PRESENT, true, last.osName(), null, null, null);
        boolean noDistro =
                windows && (!wsl.ran() || wsl.distros().keySet().stream().noneMatch(d -> d.equalsIgnoreCase(distro)));
        return new ChecklistRow(
                "isolation",
                false,
                noDistro ? ChecklistState.UNKNOWN : ChecklistState.MISSING,
                true,
                null,
                "probeFailed",
                last.reason(),
                null);
    }

    private static ChecklistRow tool(boolean windows, SandboxToolkit.Essential e, String distro, SandboxTools last) {
        String name = e.name();
        return switch (SandboxToolkit.state(last, name)) {
            case PRESENT -> sandboxRow(name, ChecklistState.PRESENT, SandboxToolkit.version(last, name), null, null);
            case ABSENT ->
                sandboxRow(
                        name,
                        ChecklistState.OPTIONAL,
                        null,
                        null,
                        SandboxToolkit.installCommandHere(distro, List.of(name), last.osId(), last.osLike())
                                .orElse(null));
            case UNKNOWN ->
                sandboxRow(
                        name,
                        ChecklistState.UNKNOWN,
                        null,
                        windows || last != null ? "notMeasured" : "notWindows",
                        null);
        };
    }

    private static ChecklistRow sandboxRow(
            String name, ChecklistState state, String version, String note, String command) {
        return new ChecklistRow(name, true, state, false, version, note, null, command);
    }

    /** What each row is for, in English: the command line's text (the screen has its own, translated). */
    static final Map<String, String> PURPOSE = Map.ofEntries(
            Map.entry("claude", "runs the agents"),
            Map.entry("git", "worktrees, branches and the director's merges"),
            Map.entry("wsl", "runs the agents' commands isolated from Windows"),
            Map.entry("distro", "the Linux system the sandbox runs in"),
            Map.entry("isolation", "the sandbox's namespaces: only the worktree is visible"),
            Map.entry("pasta", "network access for the agents' commands (user-mode networking, Linux)"),
            Map.entry("python3", "scripts and Python projects"),
            Map.entry("node", "JavaScript projects and their tools"),
            Map.entry("java", "Java projects and their builds"),
            Map.entry("make", "Makefile builds"),
            Map.entry("chromium", "renders web pages, screenshots and PDFs headless"),
            Map.entry("pdftotext", "reads the text of PDF files"),
            Map.entry("pandoc", "converts documents (Markdown, Word, HTML, PDF)"),
            Map.entry("libreoffice", "converts and renders office documents"),
            Map.entry("docker", "builds and runs containers"));

    private static final Map<String, String> LABEL = Map.of(
            "claude", "Claude Code",
            "git", "git",
            "wsl", "WSL 2",
            "distro", "WSL distribution",
            "isolation", "Sandbox isolation");

    private static final Map<String, String> NOTES = Map.of(
            "old",
            "older than Floor42 needs",
            "notFound",
            "does not run here",
            "wsl1",
            "runs on WSL 1: the sandbox needs WSL 2",
            "notWindows",
            "only on Windows",
            "notMeasured",
            "not measured yet",
            "probeFailed",
            "the sandbox could not run",
            "userns",
            "the system refuses unprivileged user namespaces (Ubuntu: kernel.apparmor_restrict_unprivileged_userns=1):"
                    + " run the command below to allow unshare in a dedicated AppArmor profile",
            "hiddenTool",
            "found on your PATH, not shown in the sandbox: install it system-wide, or with sdkman, nvm, pyenv, asdf, fnm,"
                    + " uv or mise");

    /** The checklist as {@code floor42 doctor} prints it: one line per row, then the command to copy, if any. */
    public static String text(Checklist c) {
        StringBuilder s = new StringBuilder();
        s.append("Floor42 doctor: what this machine has for Floor42. Nothing is installed or changed.\n\n");
        for (ChecklistRow r : c.rows()) s.append(line(r, c.distro()));
        long missing = c.rows().stream()
                .filter(r -> r.required() && r.state() != ChecklistState.PRESENT)
                .count();
        s.append('\n')
                .append(
                        missing == 0
                                ? "Every required item is present."
                                : missing + " required item(s) missing or not confirmed.")
                .append('\n');
        return s.toString();
    }

    /** One row as printed: state, name, purpose, version and note; then the probe's words and the command, if any. */
    private static String line(ChecklistRow r, String distro) {
        String label = r.sandbox() ? r.id() + " (in the sandbox)" : LABEL.getOrDefault(r.id(), r.id());
        if (r.id().equals("distro")) label = label + " " + distro;
        if (r.id().startsWith("hidden.")) label = r.id().substring(7) + " (not shown in the sandbox)";
        String purpose =
                r.sandbox() && r.id().equals("git") ? "git inside the sandbox" : PURPOSE.getOrDefault(r.id(), "");
        StringBuilder s = new StringBuilder("  ").append(STATES.get(r.state())).append(' ');
        s.append(pad(label, 32)).append(purpose);
        if (r.version() != null && !r.version().isBlank() && !r.id().equals("distro")) {
            s.append(" (").append(r.version()).append(')');
        }
        if (r.note() != null) s.append("; ").append(NOTES.getOrDefault(r.note(), DockerAccess.note(r.note())));
        s.append('\n');
        if (r.detail() != null && !r.detail().isBlank()) {
            s.append("               ")
                    .append(r.detail().replaceAll("\\s+", " ").strip())
                    .append('\n');
        }
        if (r.command() != null && r.state() != ChecklistState.PRESENT) {
            s.append("               to install, copy: ").append(r.command()).append('\n');
        }
        return s.toString();
    }

    private static final Map<ChecklistState, String> STATES = Map.of(
            ChecklistState.PRESENT, "[ok]      ",
            ChecklistState.MISSING, "[MISSING] ",
            ChecklistState.OPTIONAL, "[optional]",
            ChecklistState.UNKNOWN, "[unknown] ");

    private static String pad(String s, int width) {
        return s.length() >= width ? s + " " : s + " ".repeat(width - s.length());
    }
}
